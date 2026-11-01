package dev.daisy.core

/**
 * What happened when the user pasted a URL and we went looking for a feed.
 *
 * Deliberately NOT a pair of booleans. The indicator needs to tell apart "still
 * working" from "found it" from "gave up", and a `Boolean isLoading` collapses
 * the first into the third whenever something else is also loading -- the exact
 * shape of the Kai scroll bug, where a flag that stays true for a whole
 * operation got read as "it just started".
 *
 * [Attempt] is carried so a failure can say what was tried. "No feed found" is
 * unactionable; "tried /feed -> 404, /rss -> HTML" is a bug report.
 */
sealed interface DiscoverState {

    /** Nothing has been tried. The idle indicator state. */
    data object Idle : DiscoverState

    /** A fetch is in flight. The spinner state. */
    data class Looking(val current: String? = null) : DiscoverState

    /** A feed URL was found and verified to parse. The green state. */
    data class Found(val feedUrl: String, val title: String?) : DiscoverState

    /**
     * Every candidate failed. The red state.
     *
     * [tried] is never empty by the time this is reachable -- an empty list here
     * would mean we gave up without asking anything, which is a bug in the
     * caller, not a result.
     */
    data class NotFound(val tried: List<Attempt>) : DiscoverState
}

/** One URL we fetched, and how it went. */
data class Attempt(
    val url: String,
    val outcome: Outcome,
    /** Short human-readable detail, e.g. "404" or "not a feed". */
    val detail: String? = null,
) {
    enum class Outcome { FEED, NOT_A_FEED, HTTP_ERROR, NETWORK_ERROR, SKIPPED }
}

/** Reasons a candidate could not even be tried. */
sealed interface DiscoverError {
    data object MalformedUrl : DiscoverError
    /** Nothing to try: the URL is neither a feed, a known pattern, nor a site. */
    data object NothingToTry : DiscoverError
}

/**
 * Builds the ordered list of URLs worth fetching for [input].
 *
 * Pure: no network, no clock, no coroutines. That is what lets the ordering
 * rules -- the part that is easy to get subtly wrong -- be tested directly.
 *
 * Order is by COST. A rule that costs zero extra requests goes ahead of one
 * that costs a fetch, regardless of how confident we are:
 *
 *   1. the URL exactly as given (the user may already know the feed URL)
 *   2. known-suffix rules: Mastodon, Lemmy, GitHub, Tumblr, Medium -- append
 *      `.rss` / `.atom`, no extra request
 *   3. known-pattern rules: YouTube -- needs one HTML fetch to resolve a handle
 *   4. generic discovery: the site's own <link rel=alternate>, HTTP Link header,
 *      then conventional paths
 *
 * The exact input comes first even though it will usually fail, because "try
 * what they typed" is one request and is the only path that works for feeds
 * behind a path no rule would guess.
 */
object UrlCandidates {

    /** Conventional feed paths, cheapest/most common first. */
    private val CONVENTIONAL_PATHS = listOf(
        "/feed", "/rss", "/feed.xml", "/rss.xml", "/atom.xml", "/index.xml",
        "/feeds/posts/default", "/?feed=rss2",
    )

    /**
     * Returns candidates in the order they should be tried.
     *
     * Duplicates are removed keeping the FIRST occurrence, so a URL that both
     * matches a rule and appears in the conventional list is not fetched twice.
     */
    fun forInput(input: String, siteHandlesDiscovery: Boolean = true): List<Candidate> {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || !looksLikeUrl(trimmed)) return emptyList()

        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()

        fun add(url: String, kind: Candidate.Kind) {
            if (seen.add(url)) out.add(Candidate(url, kind))
        }

        add(trimmed, Candidate.Kind.AS_GIVEN)

        // Zero-cost suffix rules: the site exposes a feed at <url>.rss or
        // equivalent. No discovery request is needed, so these beat everything.
        for (suffix in suffixRules(trimmed)) add(suffix, Candidate.Kind.SUFFIX_RULE)

        // One request to resolve, then the answer is a url.
        youtubeCandidates(trimmed).forEach { add(it, Candidate.Kind.PATTERN_RULE) }

        if (siteHandlesDiscovery) {
            // No separate discovery-page candidate: the as-given url is already
            // that fetch. If it returns HTML we hold the body, so reading
            // <link rel=alternate> off it costs nothing extra.
            val base = origin(trimmed)
            if (base != null) {
                for (p in CONVENTIONAL_PATHS) add(base + p, Candidate.Kind.CONVENTIONAL_PATH)
            }
        }

        return out
    }

    /**
     * Feed URLs derivable from the input by appending a known suffix. Every
     * one of these costs exactly one request, the same as the as-given attempt,
     * which is why they all sit in the zero-cost tier.
     *
     * DELIBERATELY NO `youtu.be`-style host rewriting here: a suffix rule must
     * produce a url on the SAME origin, or it is a pattern rule in disguise and
     * belongs in its own tier with its own cost.
     */
    private fun suffixRules(url: String): List<String> {
        val noTrailing = url.trimEnd('/')
        return when {
            // Reddit: both spellings are real endpoints. NOTE: Reddit ends RSS
            // support on 2026-11-13, after which these 404 -- harmless, one
            // cheap request, and the fallback path picks up whatever replaces
            // it. Kept because the cost of being wrong is one request.
            noTrailing.contains("reddit.com/r/") && !noTrailing.contains(".rss") ->
                listOf("$noTrailing/.rss", "$noTrailing.rss")

            // GitHub: user activity and repo feeds are Atom, not RSS.
            // github.com/<user>.atom, and a repo page adds releases/commits.
            hostOf(noTrailing) == "github.com" && !noTrailing.endsWith(".atom") ->
                when (pathSegments(noTrailing)) {
                    1 -> listOf("$noTrailing.atom")
                    2 -> listOf("$noTrailing/releases.atom", "$noTrailing/commits.atom")
                    else -> emptyList()
                }

            // Medium: medium.com/feed/@user, and <user>.medium.com/feed.
            hostOf(noTrailing)?.endsWith("medium.com") == true && !noTrailing.endsWith("/feed") ->
                listOf("$noTrailing/feed")

            // Tumblr: /<blog>/rss.
            hostOf(noTrailing)?.endsWith("tumblr.com") == true && !noTrailing.endsWith("/rss") ->
                listOf("$noTrailing/rss")

            // Hacker News front page. User profiles need an id lookup, so they
            // are not a suffix rule.
            noTrailing == "news.ycombinator.com" || noTrailing == "https://news.ycombinator.com" ->
                listOf("https://news.ycombinator.com/rss")

            // Mastodon and friends: /@user.rss. This is a SHAPE rule, not a host
            // rule -- fediverse instances do not share a domain, so there is no
            // host to match on. It therefore goes LAST, after every rule that
            // knows its own host: a Medium profile is also /@name, and this
            // branch used to claim it and produce medium.com/@someone.rss.
            //
            // Being last means a host with a more specific rule wins, and only
            // an @-shaped path nobody claimed falls through to here. The cost of
            // being wrong is one request.
            noTrailing.contains("/@") && !noTrailing.endsWith(".rss") ->
                listOf("$noTrailing.rss")

            else -> emptyList()
        }
    }

    /**
     * YouTube: every form reduces to a channel id, then one canonical feed url.
     *
     * /channel/UC... carries the id directly. /@handle and /c/name and /user/name
     * do not, and need an HTML fetch -- see YoutubeChannel. This returns the
     * canonical feed for the direct case only; a handle has to be resolved
     * first, so it is represented as the page to fetch.
     */
    private fun youtubeCandidates(url: String): List<String> {
        if (!isYoutube(url)) return emptyList()
        val id = channelIdInPath(url) ?: return emptyList()
        return listOf("https://www.youtube.com/feeds/videos.xml?channel_id=$id")
    }

    /** Extracts `UC...` from a /channel/ path, or null. */
    fun channelIdInPath(url: String): String? {
        val idx = url.indexOf("/channel/")
        if (idx < 0) return null
        val rest = url.substring(idx + "/channel/".length)
        val id = rest.takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }
        return if (id.startsWith("UC") && id.length > 2) id else null
    }

    private fun looksLikeUrl(s: String): Boolean =
        s.startsWith("http://") || s.startsWith("https://")

    /** `https://a.b/c` -> `https://a.b`. Null if not parseable. */
    fun origin(url: String): String? {
        val host = hostOf(url) ?: return null
        val scheme = url.substringBefore("://", "https")
        return "$scheme://$host"
    }

    /**
     * Lowercased host without port, or null.
     *
     * youtu.be is folded to youtube.com so short links classify as YouTube,
     * but note that [origin] rebuilds from THIS value -- which is why origin
     * is only used for conventional-path guessing, never for identity.
     */
    /**
     * Number of non-empty path segments, ignoring query, fragment and a
     * trailing slash. `example.com` -> 0, `example.com/a` -> 1,
     * `example.com/a/b/` -> 2.
     *
     * Separate from [hostOf] because hostOf returns a bare host -- counting
     * slashes in it always yields zero, which silently picks the wrong branch.
     */
    fun pathSegments(url: String): Int {
        val path = url.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#')
        return path.split('/').count { it.isNotEmpty() }
    }

    fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null
        val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val bare = host.substringBefore(':').lowercase()
        if (bare.isEmpty()) return null
        return if (bare == "youtu.be") "youtube.com" else bare
    }

    /** True if this URL should be treated as a YouTube channel. */
    fun isYoutube(url: String): Boolean {
        val h = hostOf(url) ?: return false
        return h == "youtube.com" || h.endsWith(".youtube.com")
    }
}

/** A URL to try, and why it is on the list. */
data class Candidate(val url: String, val kind: Kind) {
    enum class Kind {
        /** Exactly what the user pasted. */
        AS_GIVEN,
        /** Derived by appending a known suffix. No extra request beyond the fetch. */
        SUFFIX_RULE,
        /** Derived from a known site pattern. */
        PATTERN_RULE,
        /** A guessed conventional path. */
        CONVENTIONAL_PATH,
        /** Declared by the page itself via <link rel=alternate> or Link:. */
        ADVERTISED,
        /** A url that resolved this feed on a previous attempt. Tried first. */
        REMEMBERED,
    }
}
