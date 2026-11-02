package dev.daisy.core

/**
 * YouTube channel ids, pulled out of the several URL spellings that mean the
 * same channel.
 *
 * This is the only discovery rule that needs a network round trip to answer,
 * and the reason is structural: `youtube.com/@handle`, `/c/name`, `/user/name`
 * and `/channel/UC...` are four names for one feed, and only the last carries
 * the id. The other three have to be turned into `UC...` by reading the page.
 *
 * Split from [UrlCandidates] because this half is pure text work. The fetch
 * lives in the driver; everything here can be tested without a clock, a
 * network or a coroutine.
 *
 * The one fetched page is also the cheapest thing to read: `/channel/UC...`
 * answers with a redirect to the handle form, and the id is in the final URL
 * as well as in the body -- see [idFromResolvedUrl], which is tried first.
 */
object YoutubeChannel {

    /** Canonical feed URL for a channel id. */
    fun feedUrl(channelId: String): String =
        "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"

    /** True if the id looks like the `UC...` form the feed endpoint accepts. */
    fun isChannelId(candidate: String): Boolean =
        candidate.length > 2 && candidate.startsWith("UC") &&
            candidate.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    /**
     * The `UC...` id out of a URL whose PATH contains `/channel/<id>`.
     *
     * Used for both the typed URL and the post-redirect URL, so a `/@handle`
     * link that resolves to `/channel/UC...` costs one fetch and no body parse.
     */
    fun idFromChannelPath(url: String): String? {
        val idx = url.indexOf("/channel/")
        if (idx < 0) return null
        val rest = url.substring(idx + "/channel/".length)
        val id = rest.takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
        return id.takeIf(::isChannelId)
    }

    /**
     * True if the URL names a channel in a form that has no id in it.
     *
     * `/@handle`, `/c/name`, `/user/name`. Also `/channel/...` when the id did
     * not parse, so a malformed id falls through to the page fetch rather than
     * silently giving up -- the page will either have a better id or 404.
     */
    fun needsResolution(url: String): Boolean {
        if (!UrlCandidates.isYoutube(url)) return false
        if (idFromChannelPath(url) != null) return false
        val path = url.substringAfter("://", "").substringAfter('/', "")
            .substringBefore('?').substringBefore('#')
        val first = path.split('/').firstOrNull { it.isNotEmpty() } ?: return false
        // Anything else at the top of a YouTube path is a video, a playlist or
        // the front page -- none of which is a channel.
        // "users" (plural) is not a real YouTube path, but it is what people
        // type, and leaving it out meant no candidate was generated for the
        // page -- so discovery fell through to the url as given and subscribed
        // to an error page as an empty feed. Any of these prefixes marks the
        // url as a channel ATTEMPT, which is what stops the fall-through; the
        // resolution below then decides whether a real channel id exists.
        return first.startsWith("@") ||
            first == "c" ||
            first == "user" ||
            first == "users" ||
            first == "channel"
    }

    /**
     * The channel id from a fetched channel page.
     *
     * Three places, cheapest first, because YouTube has changed this markup
     * more than once and none of the three has been stable forever:
     *
     *  1. `<link rel="canonical" href=".../channel/UC...">` -- the intended
     *     machine-readable statement of "this page is that channel".
     *  2. `"externalId":"UC..."` in the embedded JSON blob -- survives markup
     *     churn because it is data, not presentation.
     *  3. `<meta itemprop="channelId" content="UC...">` -- the oldest form,
     *     still emitted.
     *
     * Returns null rather than a guess if all three miss. A wrong channel id
     * silently subscribes the user to somebody else's feed, which is worse than
     * reporting that discovery failed.
     */
    fun idFromPage(html: String): String? {
        canonicalId(html)?.let { return it }
        externalId(html)?.let { return it }
        itempropId(html)?.let { return it }
        return null
    }

    private fun canonicalId(html: String): String? {
        val tag = Regex("""<link[^>]+rel\s*=\s*["']canonical["'][^>]*>""", RegexOption.IGNORE_CASE)
            .find(html)?.value ?: return null
        val href = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1) ?: return null
        return idFromChannelPath(href)
    }

    private fun externalId(html: String): String? {
        val m = Regex(""""externalId"\s*:\s*"([^"]+)"""")
            .find(html) ?: return null
        return m.groupValues[1].takeIf(::isChannelId)
    }

    private fun itempropId(html: String): String? {
        val tag = Regex("""<meta[^>]+itemprop\s*=\s*["']channelId["'][^>]*>""", RegexOption.IGNORE_CASE)
            .find(html)?.value ?: return null
        val content = Regex("""content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1) ?: return null
        return content.takeIf(::isChannelId)
    }
}
