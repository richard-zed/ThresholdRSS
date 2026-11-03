package rip.failed.daisy.core

/**
 * Resolves an item's identity string.
 *
 * Priority:
 *  1. the feed's own id (RSS <guid>, Atom <id>) -- opaque, taken verbatim
 *  2. a hash of the link, when the feed gives no id
 *  3. a hash of title + published, when there is no id and no link
 *
 * The last two are prefixed so a synthesised key can never collide with a real
 * one. A feed that gives ids for some items and not others is common; without
 * the prefix, a site whose guid happens to equal another item's link hash would
 * silently merge the two.
 */
object Identity {

    private const val PREFIX_HASH = "hash:"
    private const val PREFIX_BODY = "body:"

    fun resolve(
        guid: String?,
        link: String?,
        title: String,
        published: Long?,
    ): String {
        val g = guid?.trim()
        if (!g.isNullOrEmpty()) return g

        val l = link?.trim()
        if (!l.isNullOrEmpty()) return PREFIX_HASH + stableHash(l)

        // No id and no link. Publishing nothing at all is bad but legal, and the
        // alternative -- dropping the item -- is worse.
        return PREFIX_BODY + stableHash((published ?: 0L).toString() + "\u0000" + title)
    }

    /**
     * 64-bit FNV-1a over UTF-8, rendered as lowercase hex.
     *
     * Not a security hash. It only has to be stable across process restarts and
     * across app versions, which rules out String.hashCode() -- that is not a
     * documented guarantee in Kotlin, and a change would silently re-insert
     * every item in every feed.
     */
    fun stableHash(s: String): String {
        var h = -0x340d631b7bdddcdbL // 14695981039346656037 unsigned
        for (b in s.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L // 1099511628211
        }
        // Unsigned, zero-padded so the rendering is fixed-width and sorts sanely.
        return java.lang.Long.toUnsignedString(h, 16).padStart(16, '0')
    }
}
