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
import dev.daisy.core.Store
import dev.daisy.core.Sync
import dev.daisy.core.TimeZoneInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    /** Read everything from disk and publish it. */
    fun load() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val feeds = store.loadFeeds()
                // loadAllItems is already newest-first (Merge sorted it);
                // DayBuckets requires that order and deliberately does not sort.
                val buckets =
                    if (feeds.isEmpty()) emptyList() else DayBuckets.group(store.loadAllItems(), zone)
                feeds to buckets
            }
            _state.update {
                it.copy(feeds = snapshot.first, buckets = snapshot.second, loading = false)
            }
        }
    }

    /**
     * Refresh every feed.
     *
     * Sequential on purpose: twenty feeds in parallel is more sockets than the
     * pool wants, and it makes the failure list nondeterministic. Each feed is
     * saved as it finishes, so a sync killed halfway keeps what it already got.
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
            val now = System.currentTimeMillis()
            val failures = LinkedHashMap<Long, String>()
            var inserted = 0

            for (feed in feeds) {
                when (val outcome = refreshOne(feed, now)) {
                    is Sync.Outcome.Updated -> {
                        withContext(Dispatchers.IO) {
                            store.saveItems(feed.id, outcome.items)
                            store.saveFeeds(
                                store.loadFeeds().map {
                                    if (it.id == feed.id) {
                                        it.copy(
                                            title = outcome.title ?: it.title,
                                            lastFetched = Sync.stamp(now),
                                        )
                                    } else {
                                        it
                                    }
                                },
                            )
                        }
                        inserted += outcome.inserted
                    }
                    is Sync.Outcome.Failed -> failures[feed.id] = outcome.reason
                }
            }

            val feedsNow = withContext(Dispatchers.IO) { store.loadFeeds() }
            val buckets = withContext(Dispatchers.IO) {
                if (feedsNow.isEmpty()) emptyList() else DayBuckets.group(store.loadAllItems(), zone)
            }

            val summary = when {
                failures.isEmpty() -> if (inserted == 0) "No new items" else "$inserted new"
                failures.size == feeds.size -> "All feeds failed"
                else -> "$inserted new, ${failures.size} failed"
            }

            _state.update {
                it.copy(
                    feeds = feedsNow,
                    buckets = buckets,
                    syncing = false,
                    failures = failures,
                    message = summary,
                )
            }
        }
    }

    /** One feed: fetch, parse, merge. Free of UI concerns. */
    private suspend fun refreshOne(feed: Feed, now: Long): Sync.Outcome {
        val fetched = withContext(Dispatchers.IO) { fetcher.get(feed.url) }
        if (fetched is FetchResult.Failed) {
            return Sync.Outcome.Failed(feed.id, fetched.detail())
        }
        val ok = fetched as FetchResult.Ok
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
                        subscribe(found.feedUrl, found.title)
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
    private fun subscribe(feedUrl: String, title: String?): Boolean {
        val feeds = store.loadFeeds()
        if (feeds.any { it.url == feedUrl }) return false

        val id = store.nextFeedId()
        store.saveFeeds(
            feeds + Feed(
                id = id,
                url = feedUrl,
                title = title ?: feedUrl,
            ),
        )
        store.saveItems(id, emptyList())
        return true
    }

    fun removeFeed(feedId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.deleteFeed(feedId) }
            load()
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
