package dev.daisy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.daisy.android.AndroidSpace
import dev.daisy.android.OkHttpFetcher
import dev.daisy.core.DayBucket
import dev.daisy.core.DayBuckets
import dev.daisy.core.DiscoverState
import dev.daisy.core.Discoverer
import dev.daisy.core.Feed
import dev.daisy.core.FeedParser
import dev.daisy.core.FetchResult
import dev.daisy.core.Fetcher
import dev.daisy.core.Item
import dev.daisy.core.Opml
import dev.daisy.core.Store
import dev.daisy.core.Sync
import dev.daisy.core.TimeZoneInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * One screen's worth of state.
 *
 * A single immutable class rather than several flows: the feed list, the item
 * list and the busy flag change together (a sync replaces two of them and
 * flips the third), and separate flows would let a frame render a half-applied
 * sync.
 */
data class UiState(
    val feeds: List<Feed> = emptyList(),
    val buckets: List<DayBucket> = emptyList(),
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val discover: DiscoverState = DiscoverState.Idle,
    /** Feed id -> why its last refresh failed. Cleared by the next success. */
    val failures: Map<Long, String> = emptyMap(),
    /** Shown once, then cleared by the UI. */
    val message: String? = null,
) {
    val isEmpty: Boolean get() = feeds.isEmpty() && !loading
}

/**
 * The app's single source of truth.
 *
 * AndroidViewModel rather than a plain ViewModel with a factory: the store needs
 * `filesDir`, and this gets an Application without every composition site
 * wiring a factory. Construction touches nothing but a path, and all real I/O
 * is explicitly moved to Dispatchers.IO.
 *
 * Nothing here tracks read state, by design. There is no "mark seen" call and
 * no unread counter, because Daisy is stateless on purpose.
 */
class DaisyViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(AndroidSpace(File(app.filesDir, "store")))
    private val fetcher: Fetcher = OkHttpFetcher()

    /**
     * Serialises every write to meta.json.
     *
     * The item files are per-feed and need no lock. The feed list is one file
     * rewritten by both sync and remove, so an interleaved read-modify-write
     * would silently drop one of them -- that is the bug this exists to stop.
     */
    private val writeLock = Mutex()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Resolved per call, not captured, so a timezone change while the app is
     * backgrounded still lands items on the right day.
     */
    private val zone = object : TimeZoneInfo {
        override fun startOfDay(epochMillis: Long): Long {
            val cal = Calendar.getInstance(TimeZone.getDefault())
            cal.timeInMillis = epochMillis
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }

    init {
        load()
    }

    /**
     * Read everything from disk and publish it.
     *
     * Takes NO lock, deliberately. This is a pure read with nothing to
     * interleave: it loads a snapshot and publishes that same snapshot, so a
     * writer landing mid-read can only mean the next load shows the newer list.
     * A writer that must read back its OWN write is a different case and reads
     * inside [writeLock] (see [removeFeed]).
     *
     * Note it does not suspend -- it launches. Anything that needs the result
     * before moving on must not call this.
     */
    fun load() {
        viewModelScope.launch {
            // loadAllItems is already newest-first (Merge sorted it);
            // DayBuckets requires that order and deliberately does not sort.
            val snapshot = withContext(Dispatchers.IO) { snapshotLocked() }
            _state.update {
                it.copy(feeds = snapshot.first, buckets = snapshot.second, loading = false)
            }
        }
    }

    /**
     * Refresh every feed.
     *
     * Sequential on purpose: twenty feeds in parallel is more sockets than the
     * pool wants, and it makes the failure list nondeterministic. Each feed's
     * ITEMS are saved as it finishes, so a sync killed halfway keeps the
     * articles it already fetched. The feed list is written once at the end,
     * reconciled against whatever is on disk by then.
     */
    fun sync() {
        if (_state.value.syncing) return
        val feeds = _state.value.feeds
        if (feeds.isEmpty()) {
            _state.update { it.copy(message = "No feeds yet") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(syncing = true, message = null) }
            try {
                runSync(feeds)
            } finally {
                // Belt and braces. runSync clears this on every normal path, and
                // each feed is already guarded -- but a stuck `true` here makes
                // Refresh permanently dead with no visible cause, so the flag is
                // cleared unconditionally.
                _state.update { it.copy(syncing = false) }
            }
        }
    }

    private suspend fun runSync(feeds: List<Feed>) {
        val now = System.currentTimeMillis()
        val failures = LinkedHashMap<Long, String>()
        var inserted = 0

        // Snapshot the feed list ONCE and keep a running copy. Each feed
        // used to re-read and rewrite meta.json inside the loop, which is a
        // read-modify-write of the entire list per feed; the last writer
        // otherwise wins and silently discards the others' lastFetched.
        var working = feeds

        for (feed in feeds) {
            // A feed that throws must not take the whole sync down with it.
            // Without this, one pathological body kills the coroutine, and
            // `syncing` stays true forever because the code that clears it
            // never runs -- the app then looks dead and refuses every
            // later refresh.
            val outcome = try {
                refreshOne(feed, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Sync.Outcome.Failed(feed.id, e.message ?: e.javaClass.simpleName)
            }
            when (outcome) {
                is Sync.Outcome.Updated -> {
                    working = working.map {
                        if (it.id == feed.id) {
                            it.copy(
                                title = outcome.title ?: it.title,
                                lastFetched = Sync.stamp(now),
                            )
                        } else {
                            it
                        }
                    }
                    withContext(Dispatchers.IO) { store.saveItems(feed.id, outcome.items) }
                    inserted += outcome.inserted
                }
                is Sync.Outcome.Failed -> failures[feed.id] = outcome.reason
            }
        }

        // One write for the whole sync, after every feed has settled.
        //
        // Reconciled rather than written blind: `working` is a snapshot taken
        // before the first request, and a sync can run for a minute. A feed
        // removed or added during that window must survive the merge, so the
        // current list is authoritative for which feeds exist and `working`
        // only supplies the refreshed fields.
        //
        // The re-read is INSIDE the lock, with the write it follows. Reading
        // after releasing it left a window: a remove could take the lock, delete
        // a feed and publish a clean list, and this re-read -- still holding the
        // list from a moment earlier -- would then publish the feed back. The
        // row returned from nowhere and stayed until the next load.
        //
        // Re-reading at all (rather than publishing `working`) is still right:
        // the write above reconciled against disk, so the UI must show THAT
        // list, not the one this sync started from.
        val snapshot = withContext(Dispatchers.IO) {
            writeLock.withLock {
                val refreshed = working.associateBy { it.id }
                val current = store.loadFeeds().map { stored ->
                    refreshed[stored.id]?.let { new ->
                        stored.copy(title = new.title, lastFetched = new.lastFetched)
                    } ?: stored
                }
                store.saveFeeds(current)
                snapshotLocked()
            }
        }

        val summary = when {
            failures.isEmpty() -> if (inserted == 0) "No new items" else "$inserted new"
            failures.size == feeds.size -> "All feeds failed"
            else -> "$inserted new, ${failures.size} failed"
        }

        _state.update {
            it.copy(
                feeds = snapshot.first,
                buckets = snapshot.second,
                failures = failures,
                message = summary,
            )
        }
    }

    /** One feed: fetch, parse, merge. Free of UI concerns. */
    private suspend fun refreshOne(feed: Feed, now: Long): Sync.Outcome {
        val fetched = withContext(Dispatchers.IO) { fetcher.get(feed.url) }
        if (fetched is FetchResult.Failed) {
            return Sync.Outcome.Failed(feed.id, fetched.detail())
        }
        // Safe by exhaustive-ness of the sealed interface: Failed returned
        // above, and Ok is the only other branch. Left as a smart cast rather
        // than `as` so adding a third subtype is a compile error here, not a
        // ClassCastException at runtime.
        val ok = fetched as? FetchResult.Ok
            ?: return Sync.Outcome.Failed(feed.id, "unexpected fetch result")
        return when (val parsed = FeedParser.parse(ok.body, ok.finalUrl)) {
            is FeedParser.Result.Error ->
                Sync.Outcome.Failed(feed.id, "parse: ${parsed.reason}")

            is FeedParser.Result.Ok -> withContext(Dispatchers.IO) {
                Sync.apply(
                    feedId = feed.id,
                    title = parsed.title,
                    existing = store.loadItems(feed.id),
                    incoming = parsed.items,
                    now = now,
                )
            }
        }
    }

    /**
     * Discover, then subscribe.
     *
     * Discovery reports per-candidate progress, so the state stream is the UI's
     * only liveness signal -- there is nothing to await in the composable. The
     * guard on `Looking` stops a second tap starting a competing walk.
     */
    fun addFeed(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        if (_state.value.discover is DiscoverState.Looking) return

        viewModelScope.launch {
            val found = Discoverer(fetcher).discover(
                input = trimmed,
                onState = { s -> _state.update { it.copy(discover = s) } },
                now = { System.currentTimeMillis() },
            )

            when (found) {
                is DiscoverState.Found -> {
                    val added = withContext(Dispatchers.IO) {
                        writeLock.withLock { subscribe(found.feedUrl, found.title) }
                    }
                    _state.update {
                        it.copy(
                            discover = DiscoverState.Idle,
                            message = if (added) "Added" else "Already subscribed",
                        )
                    }
                    load()
                }
                is DiscoverState.NotFound -> _state.update { it.copy(discover = found) }
                else -> _state.update { it.copy(discover = DiscoverState.Idle) }
            }
        }
    }

    fun dismissDiscover() {
        _state.update { it.copy(discover = DiscoverState.Idle) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    /** True if added, false if already subscribed. */
    /**
     * Adds a feed unless its url is already subscribed.
     *
     * Reads the list ONCE and derives the new id from that same read. Calling
     * [Store.nextFeedId] here would load the list a second time, and the two
     * reads are not guaranteed to agree inside a single critical section --
     * the id could collide with a feed added between them. Caller holds
     * [writeLock].
     */
    private fun subscribe(feedUrl: String, title: String?): Boolean {
        val feeds = store.loadFeeds()
        if (feeds.any { it.url == feedUrl }) return false

        // Ids are never reused, so a stale items file cannot be adopted by a
        // different feed after a remove/re-add cycle.
        val id = (feeds.maxOfOrNull { it.id } ?: 0L) + 1L
        store.saveFeeds(
            feeds + Feed(
                id = id,
                url = feedUrl,
                title = title ?: feedUrl,
            ),
        )
        // An empty items file, so a feed with no items yet still reads as
        // "known and empty" rather than missing.
        store.saveItems(id, emptyList())
        return true
    }

    /**
     * Removes a feed and its items.
     *
     * The meta.json rewrite is the risky half: it is a read-modify-write of the
     * whole list, and a sync writing the list at the same moment would put the
     * deleted feed back. Both paths therefore run under [writeLock], which is
     * the only thing ordering them -- the per-feed item files need no lock
     * because each feed owns its own file. The read-back runs under the same
     * lock as the writes, so the list published here is the list just written.
     */
    fun removeFeed(feedId: Long) {
        viewModelScope.launch {
            // The re-read happens INSIDE the lock, and the state is published
            // from that read.
            //
            // It used to call load() afterwards, which was wrong twice over:
            // load() does not suspend, it launches -- so it returned at once and
            // its body raced the writes just made above -- and the read itself
            // was outside writeLock, so a sync could slip in between the delete
            // and the re-read and publish the feed straight back. The row stayed
            // on screen and the only way to clear it was to leave and return.
            val snapshot = withContext(Dispatchers.IO) {
                writeLock.withLock {
                    store.deleteFeed(feedId)
                    store.saveFeeds(store.loadFeeds().filterNot { it.id == feedId })
                    snapshotLocked()
                }
            }
            _state.update {
                it.copy(feeds = snapshot.first, buckets = snapshot.second, loading = false)
            }
        }
    }

    /**
     * Feeds plus day buckets, read from disk.
     *
     * MUST be called under [writeLock] by any writer: it is the read half of a
     * read-modify-write, and an unlocked read here is what let a removed feed
     * reappear. Dispatchers.IO is the caller's job, not this function's, so it
     * can be used inside a lock without nesting dispatchers.
     */
    private fun snapshotLocked(): Pair<List<Feed>, List<DayBucket>> {
        val feeds = store.loadFeeds()
        val buckets =
            if (feeds.isEmpty()) emptyList() else DayBuckets.group(store.loadAllItems(), zone)
        return feeds to buckets
    }

    // --- OPML ---------------------------------------------------------------

    /**
     * The current subscriptions as an OPML document.
     *
     * Read from disk rather than from UiState: the file the user exports should
     * be what is actually stored, not whatever a screen last rendered.
     */
    suspend fun exportOpml(): String = withContext(Dispatchers.IO) {
        Opml.export(store.loadFeeds())
    }

    /**
     * Subscribes to every feed in an OPML document.
     *
     * NO discovery walk. An OPML file lists xmlUrl values -- urls that were
     * already resolved by whatever reader exported them -- so running each one
     * through discovery would spend a request per feed re-deriving an answer we
     * were handed. A url that turns out not to be a feed shows up as an empty
     * feed on the next sync rather than being rejected here, which is the same
     * trade the app makes everywhere else.
     *
     * One lock hold for the whole import, so a sync cannot interleave and a
     * half-imported list is never visible.
     */
    suspend fun importOpml(body: String) {
        val entries = Opml.parse(body)
        if (entries.isEmpty()) {
            _state.update { it.copy(message = "No feeds found in that file") }
            return
        }

        // Read back inside the lock, with the writes it follows -- the same
        // ordering removeFeed needs, and for the same reason: load() launches,
        // so calling it here would return before the subscribe writes landed
        // and the new feeds would not appear until the next load.
        var added = 0
        val snapshot = withContext(Dispatchers.IO) {
            writeLock.withLock {
                added = entries.count { e -> subscribe(e.url, e.title) }
                snapshotLocked()
            }
        }
        _state.update {
            it.copy(
                feeds = snapshot.first,
                buckets = snapshot.second,
                loading = false,
                message = when (added) {
                    0 -> "All ${entries.size} already subscribed"
                    1 -> "Added 1 feed"
                    else -> "Added $added of ${entries.size} feeds"
                },
            )
        }
    }

    /** One feed's items. Reads from disk; one canonical copy beats a cache. */
    suspend fun itemsFor(feedId: Long): List<Item> = withContext(Dispatchers.IO) {
        store.loadItems(feedId)
    }

    suspend fun feedById(feedId: Long): Feed? = withContext(Dispatchers.IO) {
        store.loadFeeds().firstOrNull { it.id == feedId }
    }
}
