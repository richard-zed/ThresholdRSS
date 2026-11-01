package dev.daisy.core

/**
 * A subscribed feed.
 *
 * [url] is the FEED url (the thing we fetch), not the site's home page.
 * [siteUrl] is where a human would go, used to derive [faviconUrl].
 */
data class Feed(
    val id: Long,
    val url: String,
    val title: String,
    val siteUrl: String? = null,
    val faviconUrl: String? = null,
    /** Epoch millis of the last successful fetch, or null if never fetched. */
    val lastFetched: Long? = null,
)

/**
 * One entry from one feed.
 *
 * [guid] is the raw, opaque identity string from the feed. It is stored as-is:
 * RSS <guid> and Atom <id> are not interchangeable and neither is a URL in
 * practice, so nothing here parses it.
 *
 * Uniqueness is (feedId, guid). Never guid alone -- a guid is only unique
 * within its own feed, and two feeds legitimately syndicating the same
 * article must produce two rows.
 */
data class Item(
    val id: Long,
    val feedId: Long,
    val guid: String,
    val title: String,
    val link: String? = null,
    /** Feed-provided body, usually truncated. HTML. */
    val summary: String? = null,
    val imageUrl: String? = null,
    /** Epoch millis, or null. Null sorts to the BOTTOM, never to epoch 0. */
    val published: Long? = null,
    /** When we stored it. Not displayed; kept for debugging and staleness. */
    val fetchedAt: Long,
)

/** An item with the identity already resolved. This is what the parser hands to the store. */
data class IncomingItem(
    val guid: String,
    val title: String,
    val link: String? = null,
    val summary: String? = null,
    val imageUrl: String? = null,
    val published: Long? = null,
)
