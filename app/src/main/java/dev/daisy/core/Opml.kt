package dev.daisy.core

/**
 * OPML export and import.
 *
 * OPML is the only interchange format feed readers agree on, and it is trivial:
 * a nested outline of feed urls. The interesting parts are escaping on the way
 * out and tolerance on the way in, so both live here as pure functions with no
 * file access.
 */
object Opml {

    /**
     * Renders [feeds] as an OPML 2.0 document.
     *
     * Every attribute is escaped. A feed titled `AT&T` or `R&D <notes>` is
     * ordinary, and an unescaped `&` makes the file unparseable by every reader
     * the user might import into -- a silent failure at the far end, which is
     * the worst kind.
     *
     * xmlUrl is the feed (what we fetch); htmlUrl is the site a human visits.
     * Both are written because importers differ in which they use, and omitting
     * htmlUrl makes the imported list look anonymous in some readers.
     */
    fun export(feeds: List<Feed>, title: String = "ThresholdRSS subscriptions"): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<opml version="2.0">""").append('\n')
        sb.append("  <head>").append('\n')
        sb.append("    <title>").append(esc(title)).append("</title>").append('\n')
        sb.append("  </head>").append('\n')
        sb.append("  <body>").append('\n')
        for (f in feeds) {
            val name = f.title.ifBlank { f.url }
            sb.append("""    <outline type="rss" """)
            sb.append("""text="""").append(esc(name)).append('"')
            sb.append(""" title="""").append(esc(name)).append('"')
            sb.append(""" xmlUrl="""").append(esc(f.url)).append('"')
            if (!f.siteUrl.isNullOrBlank()) {
                sb.append(""" htmlUrl="""").append(esc(f.siteUrl)).append('"')
            }
            sb.append("/>").append('\n')
        }
        sb.append("  </body>").append('\n')
        sb.append("</opml>").append('\n')
        return sb.toString()
    }

    /**
     * Escapes the five XML entities, for use inside a double-quoted attribute.
     *
     * Quotes are escaped as &quot; because these values are always attributes;
     * a literal " would end the attribute early. The apostrophe is escaped too
     * even though it is safe inside double quotes -- it costs nothing and stops
     * this from being wrong if the output shape ever changes.
     */
    fun esc(s: String): String {
        if (s.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return s
        val out = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** One feed url recovered from an OPML document. */
    data class Entry(val url: String, val title: String?, val siteUrl: String?)

    /**
     * Pulls every feed out of an OPML document.
     *
     * Accepts `xmlUrl` in any attribute order and any case, because exporters
     * are inconsistent and some write `xmlurl`. Nested outlines (OPML folders)
     * are flattened: a reader has one flat list, and preserving folders would
     * mean inventing folders in an app that has none.
     *
     * A url appearing twice is returned twice; de-duplication is [Merge]'s call,
     * not this function's, because "same url" and "same feed" are different
     * questions (a url can be normalised, a feed can move).
     */
    fun parse(body: String): List<Entry> {
        val roots = FeedParser.scan(body)
        val out = ArrayList<Entry>()

        fun walk(el: FeedParser.El) {
            if (el.name.substringAfterLast(':').equals("outline", true)) {
                val attrs = el.attrs.mapKeys { it.key.lowercase() }
                val url = attrs["xmlurl"]?.trim()
                if (!url.isNullOrEmpty()) {
                    out.add(
                        Entry(
                            url = url,
                            title = attrs["title"]?.trim()?.takeIf { it.isNotEmpty() }
                                ?: attrs["text"]?.trim()?.takeIf { it.isNotEmpty() },
                            siteUrl = attrs["htmlurl"]?.trim()?.takeIf { it.isNotEmpty() },
                        ),
                    )
                }
            }
            for (c in el.children) walk(c)
        }

        for (r in roots) walk(r)
        return out
    }
}
