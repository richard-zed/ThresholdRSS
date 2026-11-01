package dev.daisy.core

/**
 * Where the store's bytes live.
 *
 * An interface, not java.io.File, so the store is testable in build.sh with an
 * in-memory fake. Android supplies a directory from getFilesDir(); that one
 * adapter is the only Android-specific code in the persistence path, and it is
 * small enough to read rather than test.
 *
 * Every method takes a path RELATIVE to the store root ("meta.json",
 * "feeds/42.json"). The implementation decides what the root is, so nothing here
 * can escape it by building a path from user data.
 */
interface FileSpace {
    fun read(path: String): String?
    fun write(path: String, content: String)

    /**
     * Writes to a temp path then renames over the target.
     *
     * A half-written meta.json is the difference between "app opens slowly" and
     * "app is empty forever", and a phone is killed mid-write often enough to
     * matter. The default is a plain write; adapters that can rename atomically
     * should override it.
     */
    fun writeAtomically(path: String, content: String) = write(path, content)

    fun delete(path: String)
    fun list(dir: String): List<String>
}

/**
 * The whole persisted state, as two kinds of file.
 *
 * meta.json holds the feed list -- the launch case, which must be small because
 * it is the first thing read. Each feed's items live in their own file under
 * feeds/, so a fetch rewrites one feed's history rather than every feed's. That
 * split is the entire reason this is not one document: the write cost of a
 * refresh then scales with the feed being refreshed, not with the library.
 *
 * The trade, accepted deliberately: cross-feed ordering (which the date
 * separators need) means reading every file under feeds/ once at launch. At twenty
 * feeds that is twenty small reads; at two hundred it would be worth an index,
 * and an index can be added later without changing this shape.
 *
 * Nothing here tracks read state. Daisy is stateless on purpose; if a field
 * would answer "has the user seen this", it does not belong in these files.
 */
class Store(private val fs: FileSpace) {

    companion object {
        const val META = "meta.json"
        const val FEEDS_DIR = "feeds"
        private const val VERSION = 1

        fun feedPath(id: Long) = "$FEEDS_DIR/$id.json"
    }

    // --- feed list -----------------------------------------------------------

    /**
     * The feed list, in the stored order.
     *
     * A corrupt or unreadable meta.json returns an empty list rather than
     * throwing. An app that cannot read its feed list should come up empty and
     * let the user re-subscribe, not crash on launch -- and the file is small
     * and rewritten on every subscribe, so losing it means losing the list, not
     * the articles, which are still in feeds/.
     */
    fun loadFeeds(): List<Feed> {
        val raw = fs.read(META) ?: return emptyList()
        val root = Json.parse(raw) ?: return emptyList()

        // A version we do not understand is treated as empty rather than parsed
        // optimistically. Guessing at a future shape is how a store silently
        // half-loads and then overwrites what it could not read.
        val version = root.get("version")?.long()?.toInt()
        if (version != VERSION) return emptyList()

        val feeds = root.get("feeds")?.arr() ?: return emptyList()
        return feeds.mapNotNull { v ->
            val url = v.get("url")?.str() ?: return@mapNotNull null
            val id = v.get("id")?.long() ?: return@mapNotNull null
            val title = v.get("title")?.str() ?: ""
            Feed(
                id = id,
                url = url,
                title = title,
                siteUrl = v.get("siteUrl")?.str(),
                faviconUrl = v.get("faviconUrl")?.str(),
                lastFetched = v.get("lastFetched")?.long(),
            )
        }
    }

    fun saveFeeds(feeds: List<Feed>) {
        val objs = feeds.map { f ->
            Json.Obj()
                .put("id", f.id)
                .put("url", f.url)
                .put("title", f.title)
                .put("siteUrl", f.siteUrl)
                .put("faviconUrl", f.faviconUrl)
                .put("lastFetched", f.lastFetched)
        }
        val doc = Json.Obj()
            .put("version", VERSION.toLong())
            .objs("feeds", objs)
            .render()
        fs.writeAtomically(META, doc)
    }

    // --- one feed's items ----------------------------------------------------

    /**
     * Items for [feedId], oldest-first as stored.
     *
     * Deliberately NOT sorted here. [Merge] owns ordering and has tests for it;
     * sorting in two places means two answers to "which came first" the first
     * time they disagree. This returns what was written.
     */
    fun loadItems(feedId: Long): List<Item> {
        val raw = fs.read(feedPath(feedId)) ?: return emptyList()
        val root = Json.parse(raw) ?: return emptyList()
        if (root.get("version")?.long()?.toInt() != VERSION) return emptyList()

        val items = root.get("items")?.arr() ?: return emptyList()
        return items.mapNotNull { v ->
            val guid = v.get("guid")?.str() ?: return@mapNotNull null
            Item(
                id = v.get("id")?.long() ?: 0L,
                feedId = feedId,
                guid = guid,
                title = v.get("title")?.str() ?: "",
                link = v.get("link")?.str(),
                summary = v.get("summary")?.str(),
                imageUrl = v.get("imageUrl")?.str(),
                published = v.get("published")?.long(),
                fetchedAt = v.get("fetchedAt")?.long() ?: 0L,
            )
        }
    }

    fun saveItems(feedId: Long, items: List<Item>) {
        val objs = items.map { it ->
            Json.Obj()
                .put("id", it.id)
                .put("guid", it.guid)
                .put("title", it.title)
                .put("link", it.link)
                .put("summary", it.summary)
                .put("imageUrl", it.imageUrl)
                .put("published", it.published)
                .put("fetchedAt", it.fetchedAt)
        }
        val doc = Json.Obj()
            .put("version", VERSION.toLong())
            .put("feedId", feedId)
            .objs("items", objs)
            .render()
        fs.writeAtomically(feedPath(feedId), doc)
    }

    /** Removes a feed's items. The meta entry is the caller's to update. */
    fun deleteFeed(feedId: Long) {
        fs.delete(feedPath(feedId))
    }

    // --- whole-library reads -------------------------------------------------

    /**
     * Every item from every feed, in one list.
     *
     * What the list screen actually needs, and the cost of the one-file-per-feed
     * split. Order is per-file; combine with [Merge.merge] or sort at the call
     * site, because ordering rules live there.
     */
    fun loadAllItems(): List<Item> =
        loadFeeds().flatMap { loadItems(it.id) }

    /**
     * The next free feed id.
     *
     * Derived from the stored list rather than a counter, so it cannot drift if
     * a delete lands partway through. Ids are never reused: a re-subscribe gets
     * a new one, which keeps a stale items file from being adopted by a
     * different feed.
     */
    fun nextFeedId(): Long = (loadFeeds().maxOfOrNull { it.id } ?: 0L) + 1L
}
