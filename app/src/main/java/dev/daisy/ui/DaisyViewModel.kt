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
import dev.daisy.core.Merge
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
    /**
     * When set, [buckets] holds ONLY this feed's items.
     *
     * Filtering happens in the snapshot rather than in the composable so the
     * merged and filtered views go through the same grouping code -- the day
     * headers, ordering and dedupe are identical because it is the same
     * function, not a second implementation that can drift.
     */
    val feedFilterId: Long? = null,
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
            val filter = _state.value.feedFilterId
            val snapshot = withContext(Dispatchers.IO) { snapshotLocked(filter) }
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
    /**
     * Refresh feeds.
     *
     * When a single feed is being viewed, only that feed is fetched. Pressing
     * the list was doing a full sync, which is wrong in two ways: it hits
     * every subscription for one screen's worth of content, and a failure on
     * an unrelated feed reports an error under a list that did not ask for it.
     * The filter is read here rather than passed in so every caller -- pull,
     * tab, gesture -- inherits the same rule without remembering it.
     */
    fun sync() {
        if (_state.value.syncing) return
        val filter = _state.value.feedFilterId

        viewModelScope.launch {
            _state.update { it.copy(syncing = true, message = null) }
            // The feed list is read from DISK here, under the lock, not off
            // state before the coroutine starts. subscribe() writes meta.json
            // and the state publish that follows it is a separate update, so
            // a refresh fired immediately after adding a feed could capture
            // the pre-add list from state, find nothing new to fetch, and
            // skip the feed it was asked about. It worked on the second try
            // because state had caught up by then. Disk under the lock is the
            // only read that cannot be behind a pending write.
            val feeds = withContext(Dispatchers.IO) {
                writeLock.withLock {
                    val all = store.loadFeeds()
                    if (filter == null) all else all.filter { it.id == filter }
                }
            }
            if (feeds.isEmpty()) {
                _state.update { it.copy(message = "No feeds yet", syncing = false) }
                return@launch
            }
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
                // The FILTER matters. snapshotLocked() with no argument
                // returns the merged stream, so a sync started from one
                // feed's view rebuilt the buckets for every feed and the
                // screen silently became the all-entries list. Read the
                // filter from state at this point rather than trusting a
                // captured one: it is what the UI is displaying now.
                snapshotLocked(_state.value.feedFilterId)
            }
        }

        // Only claim everything failed when everything was actually tried.
        // A single-feed refresh whose one feed threw hit this branch and
        // reported "All feeds failed", which reads as a whole-app outage
        // for a one-feed error.
        val summary = when {
            failures.isEmpty() -> if (inserted == 0) "No new items" else "$inserted new"
            feeds.size == 1 -> "Refresh failed: ${failures.values.first()}"
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
     * Discover what lives at an address, without subscribing to anything.
     *
     * Deliberately does NOT add on success. A found feed lands in the state as
     * [DiscoverState.Found] and waits there; the user reads the resolved URL
     * and title and calls [confirmAdd]. Subscribing straight off the back of a
     * walk meant the app could silently attach to the wrong feed -- a site
     * offers several, and which one the sniffer reached first was invisible.
     *
     * Discovery reports per-candidate progress, so the state stream is the UI's
     * only liveness signal -- there is nothing to await in the composable. The
     * guard on `Looking` stops a second tap starting a competing walk.
     */
    fun findFeed(input: String) {
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
                is DiscoverState.Found -> _state.update { it.copy(discover = found) }
                is DiscoverState.NotFound -> _state.update { it.copy(discover = found) }
                else -> _state.update { it.copy(discover = DiscoverState.Idle) }
            }
        }
    }

    /**
     * Subscribe to what [findFeed] resolved.
     *
     * Reads the URL from the state rather than taking it as a parameter, so
     * what gets added is exactly what the UI displayed for confirmation. A
     * parameter would let a stale composition hand back a URL the user never
     * saw.
     */
    fun confirmAdd() {
        val found = _state.value.discover as? DiscoverState.Found ?: return

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val added = withContext(Dispatchers.IO) {
                writeLock.withLock { subscribe(found.feedUrl, found.title) }
            }
            // Fetch it NOW. subscribe() leaves an empty items file, and nothing
            // else fills it until the next sync -- so a newly added feed showed
            // "No entries" and looked broken until the user refreshed by hand.
            //
            // One correction after that: refreshOne MERGES but does not save.
            // The save lives in runSync's loop, which this path never enters, so
            // calling it and dropping the outcome wrote nothing and the feed
            // stayed empty no matter how good the feed was. Persist here, under
            // the same lock, from the outcome refreshOne returns.
            var firstFailure: String? = null
            if (added != null) {
                val outcome = withContext(Dispatchers.IO) {
                    try {
                        refreshOne(added, now)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Sync.Outcome.Failed(added.id, e.message ?: e.javaClass.simpleName)
                    }
                }
                when (outcome) {
                    is Sync.Outcome.Updated -> withContext(Dispatchers.IO) {
                        writeLock.withLock {
                            store.saveItems(added.id, outcome.items)
                            // The title comes from the feed itself, which is
                            // better than the one discovery guessed from HTML.
                            if (outcome.title != null) {
                                store.saveFeeds(
                                    store.loadFeeds().map {
                                        if (it.id == added.id) {
                                            it.copy(title = outcome.title, lastFetched = now)
                                        } else it
                                    },
                                )
                            }
                        }
                    }
                    // A first fetch that fails must not lose the feed: it is
                    // subscribed and empty, and the next sync retries. But say
                    // so, rather than reporting success and showing nothing.
                    is Sync.Outcome.Failed -> firstFailure = outcome.reason
                }
            }
            _state.update {
                it.copy(
                    discover = DiscoverState.Idle,
                    message = when {
                        added == null -> "Already subscribed"
                        firstFailure != null -> "Added, but fetch failed: $firstFailure"
                        else -> "Added"
                    },
                )
            }
            // Read back inside the lock, like every other write in this class.
            val snapshot = withContext(Dispatchers.IO) {
                writeLock.withLock { snapshotLocked() }
            }
            _state.update {
                it.copy(feeds = snapshot.first, buckets = snapshot.second, loading = false)
            }
        }
    }

    /**
     * Show all feeds, or only [feedId]'s entries.
     *
     * Re-reads rather than filtering the buckets already in state, because a
     * filtered view must reflect what is on disk for THAT feed -- including
     * items a sync added since the merged list was built.
     */
    fun selectFeed(feedId: Long?) {
        if (_state.value.feedFilterId == feedId) return
        _state.update { it.copy(feedFilterId = feedId, loading = true) }
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { snapshotLocked(feedId) }
            _state.update {
                it.copy(feeds = snapshot.first, buckets = snapshot.second, loading = false)
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
    private fun subscribe(feedUrl: String, title: String?): Feed? {
        val feeds = store.loadFeeds()
        if (feeds.any { it.url == feedUrl }) return null

        // Ids are never reused, so a stale items file cannot be adopted by a
        // different feed after a remove/re-add cycle.
        val id = (feeds.maxOfOrNull { it.id } ?: 0L) + 1L
        val feed = Feed(
            id = id,
            url = feedUrl,
            title = title ?: feedUrl,
            // Model.kt claims faviconUrl is "derived from siteUrl" but
            // nothing ever derived it, so the field was permanently null
            // and every avatar check was dead. A feed URL is not the same
            // as the site's origin, so derive it here: nitter-style URLs
            // and /feed paths both have to resolve back to a host.
            faviconUrl = faviconFor(feedUrl),
        )
        store.saveFeeds(feeds + feed)
        // An empty items file, so a feed with no items yet still reads as
        // "known and empty" rather than missing. The caller fills it straight
        // away -- see confirmAdd.
        store.saveItems(id, emptyList())
        return feed
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
    /**
     * Remove a feed.
     *
     * The list is NOT edited optimistically. An earlier version filtered the
     * feed out of the published state before touching disk, which gave
     * animateItem() nothing to animate -- the row simply vanished -- and raced
     * the write: two quick removals could re-read a snapshot taken before the
     * second and publish the first feed straight back.
     *
     * All the waiting lives in the UI instead (DaisyApp's confirmValueChange
     * holds the call back for the dismissal's duration), so by the time this
     * runs the row has already played its animation. What is left is the disk
     * work, done under writeLock and published from the read that follows it, so
     * concurrent removals serialise rather than overwrite each other.
     */
    fun removeFeed(feedId: Long) {
        viewModelScope.launch {
            // The write is the only thing that needs the lock and the IO
            // dispatcher. The state update afterwards is in-memory.
            //
            // This used to finish by calling snapshotLocked(), which re-read
            // every feed's items from disk after deleting one feed. That read
            // is what made the row snap back and then vanish a moment later:
            // the swipe committed, the delete hit disk, and the list only
            // changed once every other file had been re-parsed.
            val removed = withContext(Dispatchers.IO) {
                writeLock.withLock {
                    // Read under the lock: an unlocked read here is what once
                    // let a concurrent sync resurrect the deleted feed.
                    val feeds = store.loadFeeds().filterNot { it.id == feedId }
                    store.deleteFeed(feedId)
                    store.saveFeeds(feeds)
                    feeds
                }
            }

            // Drop the removed feed's entries from buckets we already hold.
            // Its items were deleted with it, so leaving them would strand
            // entries whose feed no longer exists -- they are not re-read
            // here, so nothing else would clear them.
            _state.update { s ->
                s.copy(
                    feeds = removed,
                    buckets = s.buckets
                        .map { b -> b.copy(items = b.items.filterNot { it.feedId == feedId }) }
                        .filter { b -> b.items.isNotEmpty() },
                    loading = false,
                )
            }
        }
    }

    /**
     * Reorders the feed list, moving the feed at [from] to [to].
     *
     * The list is the stored order -- there is no sort key -- so this is a
     * plain rewrite of the collection and the new order survives a restart
     * for free. Same lock as [removeFeed]: it is a read-modify-write of
     * meta.json, and an unlocked read here would resurrect a removed feed or
     * drop a concurrent subscribe.
     *
     * Guarded rather than trusting the caller: a lazy list can send an index
     * that no longer exists after an unrelated removal, and the shift below
     * would silently move the wrong feed.
     */
    fun moveFeed(from: Int, to: Int) {
        if (from == to) return
        viewModelScope.launch {
            val reordered = withContext(Dispatchers.IO) {
                writeLock.withLock {
                    // Reorder the list ALREADY in state, not one re-read from
                    // disk: the caller just saw this order, and a stale disk
                    // read would move a different feed than the one dragged.
                    val feeds = _state.value.feeds.toMutableList()
                    if (from !in feeds.indices || to !in feeds.indices) return@withLock null
                    feeds.add(to, feeds.removeAt(from))
                    store.saveFeeds(feeds)
                    feeds
                }
            } ?: return@launch

            // Publish the new order WITHOUT rebuilding buckets.
            //
            // This used to call snapshotLocked(), which re-reads every items
            // file for every feed and re-parses, re-sorts and re-buckets them
            // -- for a gesture that changed no item at all. That read was the
            // visible lag: the row snapped back, the disk write finished, then
            // the list jumped. Buckets are unaffected by feed order: they are
            // grouped by day and filtered by feed id, and the ids did not move.
            _state.update { it.copy(feeds = reordered) }
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
    private fun snapshotLocked(filterId: Long? = null): Pair<List<Feed>, List<DayBucket>> {
        val feeds = store.loadFeeds()
        val buckets = if (feeds.isEmpty()) {
            emptyList()
        } else {
            val items = if (filterId == null) {
                store.loadAllItems()
            } else {
                // loadItems, not loadAllItems().filter: the filtered view reads
                // exactly one file, and a filter over the merged list would
                // sort it again after the fact.
                store.loadItems(filterId)
            }
            // loadAllItems is a flatMap over feeds, so it arrives grouped BY
            // FEED. capAndSort ran per feed at merge time and says nothing
            // about the merged list, and DayBuckets.group deliberately does
            // not sort -- it documents that its input must already be newest
            // first. Without this the separators stride backwards and forwards
            // through the calendar and entries interleave by subscription
            // order.
            DayBuckets.group(items.sortedWith(Merge.ITEM_ORDER), zone)
        }
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
                // subscribe returns the new Feed (null when already present),
                // so the count is over non-null results rather than a Boolean.
                added = entries.count { e -> subscribe(e.url, e.title) != null }
                snapshotLocked()
            }
        }
        _state.update {
            it.copy(
                // feeds as well as buckets. Publishing only the buckets left
                // state.feeds missing every imported feed, so callers reading
                // the subscription list -- the picker, and any code deciding
                // what to fetch -- saw the pre-import list.
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

/**
 * Google's favicon endpoint for the host of [feedUrl].
 *
 * Deriving the icon from the SITE rather than fetching /favicon.ico ourselves
 * keeps this off the network at subscribe time and works for hosts that serve
 * the icon from a CDN path we would not guess. Returns null for anything
 * unparseable, which then renders as no avatar rather than a broken image.
 */
private fun faviconFor(feedUrl: String): String? {
    val host = runCatching { java.net.URI(feedUrl).host }.getOrNull() ?: return null
    if (host.isBlank()) return null
    return "https://www.google.com/s2/favicons?domain=" + host + "&sz=64"
}
}
