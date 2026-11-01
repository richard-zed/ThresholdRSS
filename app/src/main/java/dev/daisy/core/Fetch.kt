package dev.daisy.core

/**
 * One HTTP GET, reduced to what feed discovery actually needs.
 *
 * Narrow on purpose. Discovery needs the body, the content type, and the final
 * URL after redirects -- nothing else. Keeping it this small means the HTTP
 * client (OkHttp, HttpURLConnection, a test double) is a swap at one call site
 * rather than something woven through the logic.
 *
 * suspend because every implementation blocks on IO. The interface does not
 * know about coroutine dispatchers; that is the caller's business.
 */
fun interface Fetcher {
    suspend fun get(url: String): FetchResult
}

/** Outcome of one GET. Never throws for ordinary HTTP failures -- see [FetchResult.Failed]. */
sealed interface FetchResult {

    data class Ok(
        /** Final URL after redirects. Relative resolution needs this, not the requested one. */
        val finalUrl: String,
        val contentType: String?,
        val body: String,
        val status: Int,
    ) : FetchResult

    /**
     * A failure worth reporting but not worth crashing on.
     *
     * `status` is null for transport-level failures (DNS, TLS, timeout), which
     * are a different animal from a 404 and should not be reported as one.
     */
    data class Failed(
        val status: Int?,
        val reason: String,
    ) : FetchResult

    /** Maps to an [Attempt.Outcome] for the indicator's "what did you try" list. */
    fun outcome(): Attempt.Outcome = when (this) {
        is Ok -> Attempt.Outcome.FEED
        is Failed -> if (status == null) Attempt.Outcome.NETWORK_ERROR else Attempt.Outcome.HTTP_ERROR
    }

    fun detail(): String? = when (this) {
        is Ok -> "HTTP $status"
        is Failed -> status?.let { "HTTP $it" } ?: reason
    }
}

/**
 * Sniffs whether a body is a feed, without parsing it.
 *
 * A full parse is the only authority, but a cheap prefix scan answers the one
 * question discovery asks -- "is this worth handing to the parser" -- and does
 * it without allocating a document for every HTML page we fetch.
 *
 * Both formats are checked because RSS 2.0 and Atom are both feeds and neither
 * is legacy. Namespaces are tolerated (`<rss version=`, `<feed xmlns=`), and
 * the scan skips leading whitespace and an XML prolog, which is normal.
 */
object FeedSniffer {

    /**
     * True if [body] starts like a feed.
     *
     * Deliberately not a validity check. A feed snipped in half passes here and
     * fails in the parser; that is the parser's job to report, and reporting it
     * twice would mean two different error messages for one problem.
     */
    fun looksLikeFeed(body: String): Boolean {
        val head = body.trimStart()
        if (head.isEmpty()) return false
        if (head.startsWith("{")) return false // JSON, never a feed
        if (head.startsWith("<!DOCTYPE html", ignoreCase = true)) return false
        if (head.startsWith("<html", ignoreCase = true)) return false

        // Skip an XML prolog and comments, which can precede the root element.
        var i = 0
        while (i < head.length) {
            if (head.startsWith("<?", i)) {
                val end = head.indexOf("?>", i)
                if (end < 0) return false
                i = end + 2
                continue
            }
            if (head.startsWith("<!--", i)) {
                val end = head.indexOf("-->", i)
                if (end < 0) return false
                i = end + 3
                continue
            }
            break
        }

        val root = head.substring(i).trimStart()
        return root.startsWith("<rss", ignoreCase = true) ||
            root.startsWith("<feed", ignoreCase = true) ||
            root.startsWith("<rdf", ignoreCase = true)
    }
}

/**
 * Extracts `<link rel="alternate" type="application/rss+xml" href="...">` from
 * an HTML page.
 *
 * Hand-rolled rather than a DOM parse: discovery runs this on pages we are not
 * going to otherwise read, and pulling in an HTML parser to look for one tag is
 * a large dependency for a small question. It is deliberately forgiving --
 * attribute order varies, quotes may be single or double, and rel can list
 * several values.
 *
 * Returns candidates in document order. The FIRST is not necessarily the best:
 * sites list multiple type attributes, and choosing between "rss" and "atom" is
 * the caller's call, not this function's.
 */
object LinkDiscovery {

    private val LINK_TAG = Regex(
        """<link\b([^>]*)>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val ATTR = Regex(
        """([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
    )

    /** Fraction of the document scanned. Feed links live in <head>. */
    private const val SCAN_LIMIT = 128 * 1024

    fun rssLinksFrom(html: String, baseUrl: String): List<String> {
        val head = if (html.length > SCAN_LIMIT) html.substring(0, SCAN_LIMIT) else html
        val out = ArrayList<String>()

        for (m in LINK_TAG.findAll(head)) {
            val attrs = parseAttrs(m.groupValues[1])
            val rel = attrs["rel"]?.lowercase() ?: continue
            if (!rel.split(' ', '\t').contains("alternate")) continue

            val type = attrs["type"]?.lowercase() ?: continue
            if (!isFeedType(type)) continue

            val href = attrs["href"]?.trim() ?: continue
            if (href.isEmpty()) continue

            out.add(UrlResolve.resolve(baseUrl, href))
        }
        return out
    }

    /** The same question, for an HTTP Link: header. */
    fun rssLinksFromHeader(header: String, baseUrl: String): List<String> {
        val out = ArrayList<String>()
        // Link: <https://a/rss>; rel="alternate"; type="application/rss+xml", <...>; ...
        for (part in header.split(',')) {
            val urlStart = part.indexOf('<')
            val urlEnd = part.indexOf('>')
            if (urlStart < 0 || urlEnd < urlStart) continue
            val href = part.substring(urlStart + 1, urlEnd).trim()
            val params = part.substring(urlEnd + 1).lowercase()
            val isAlt = params.contains("alternate")
            val isFeed = isFeedType(params)
            if (href.isNotEmpty() && isAlt && isFeed) {
                out.add(UrlResolve.resolve(baseUrl, href))
            }
        }
        return out
    }

    private fun isFeedType(type: String): Boolean =
        type.contains("rss") || type.contains("atom") || type.contains("application/xml")

    private fun parseAttrs(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in ATTR.findAll(s)) {
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2].ifEmpty {
                m.groupValues[3].ifEmpty { m.groupValues[4] }
            }
            if (value.isNotEmpty()) out[key] = value
        }
        return out
    }
}

/**
 * Resolves a possibly-relative URL against a base.
 *
 * Minimal on purpose -- handles the cases feed links actually use rather than
 * implementing RFC 3986. Android's java.net.URI could do this, but keeping it
 * here makes discovery testable without the Android stack and keeps the rule
 * visible instead of buried inside a JDK class.
 */
object UrlResolve {

    fun resolve(base: String, ref: String): String {
        val r = ref.trim()
        if (r.startsWith("http://") || r.startsWith("https://")) return r
        if (r.startsWith("//")) {
            val scheme = base.substringBefore("://", "https")
            return "$scheme:$r"
        }
        if (r.startsWith("/")) {
            val origin = UrlCandidates.origin(base) ?: return r
            return origin + r
        }

        // Relative to the containing directory.
        val noFragment = base.substringBefore('#')
        val cut = noFragment.lastIndexOf('/')
        val dir = if (cut >= 0) noFragment.substring(0, cut + 1) else "$base/"
        return if (r.isEmpty()) noFragment else dir + r
    }
}
