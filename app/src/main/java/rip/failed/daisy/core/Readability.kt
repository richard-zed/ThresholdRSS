package rip.failed.daisy.core

/**
 * Pulls the article out of a full web page.
 *
 * Feeds carry a truncated body; the rest lives at the link, wrapped in site
 * chrome the reader must not show. A selector is not enough, which is measured
 * rather than assumed: of four real pages checked, one has `<article>`, one has
 * `<main>` that also wraps the sidebar, and two have neither. So candidates are
 * scored by how much they look like prose instead of trusting a tag name.
 *
 * The scoring follows Mozilla's Readability closely enough to behave the same
 * way, because that algorithm is what every browser reader mode has already
 * been tuned against. Matching it means matching the expectations users
 * already have about where an article starts and stops.
 */
object Readability {

    /** An extracted article. */
    data class Article(
        val title: String?,
        val byline: String?,
        val html: String,
        /** Visible characters, for deciding whether the result is worth showing. */
        val textLength: Int,
    )

    /** Subtrees that are never article content. */
    private val UNLIKELY = setOf(
        "nav", "header", "footer", "aside", "script", "style", "noscript",
        "form", "button", "input", "select", "textarea", "iframe", "svg",
        "menu", "dialog", "template",
    )

    /**
     * Tags that wrap other elements rather than hold prose.
     *
     * Skipped as scoring candidates, not as content: their text is counted
     * through the blocks inside them. `div` and `span` are absent on purpose,
     * because a div holding bare text is a legitimate article body.
     */
    private val CONTAINERS = setOf(
        "html", "body", "head", "main", "article", "section", "li", "ul", "ol",
        "dl", "dd", "dt", "table", "thead", "tbody", "tr", "td", "th", "figure",
        "nav", "aside", "header", "footer", "form", "blockquote", "details",
    )

    /**
     * Containers that may score when they hold bare text.
     *
     * These are where an article lives. Some pages put the body directly
     * inside one with no paragraph tags, and a paragraph-only scorer returns
     * the page chrome instead. Measured on a live site: the whole article sat
     * as bare text in `<main>`, so excluding it left nothing to pick.
     */
    private val BARE_HOSTS = setOf("main", "article")

    /** An element counts as prose if its text reaches this. */
    private const val MIN_PARAGRAPH = 25

    /**
     * Fewer characters than this and the caller should show the feed summary
     * instead. Roughly one sentence: below that it is a caption or a stray
     * label, not an article.
     */
    const val MIN_USEFUL_CHARS = 200

    /**
     * Extract the article.
     *
     * @return null when nothing paragraph-shaped was found. Callers fall back
     *   to the feed's own summary; an empty reader is worse than a short one.
     */
    fun extract(pageHtml: String, pageUrl: String): Article? {
        val roots = Html.parse(pageHtml)
        if (roots.isEmpty()) return null

        prune(roots)

        val parents = HashMap<Html.Node, Html.Node?>()
        val all = ArrayList<Html.Node>()
        fun collect(n: Html.Node) {
            all.add(n)
            for (c in n.children) { parents[c] = n; collect(c) }
        }
        roots.forEach { collect(it) }

        val scores = HashMap<Html.Node, Double>()
        val counts = HashMap<Html.Node, Int>()

        for (n in all) {
            // A paragraph is any block that holds prose. Requiring <p> misses
            // the pages that matter most: one measured article puts its body as
            // bare text directly inside <main>, with no paragraph tags at all,
            // and a <p>-only scorer returns the page chrome instead.
            //
            // Container tags are excluded here because they collect their
            // descendants' text -- scoring those directly double-counts the
            // children, which are already being scored themselves.
            if (n.name in CONTAINERS && n.name !in BARE_HOSTS) continue

            // A container usually collects its descendants' text, so scoring
            // it double-counts children that score themselves. A handful of
            // containers are special: they are where an article lives, and
            // some pages put the body directly inside them with no paragraph
            // tags at all. Those may score, but only for text sitting in the
            // node itself -- otherwise they are back to double-counting.
            if (n.name in BARE_HOSTS && n.text().trim().length < MIN_PARAGRAPH) continue

            val text = n.innerText()
            if (text.length < MIN_PARAGRAPH) continue

            // Credit the PARENT only, never the whole ancestor chain.
            //
            // Scoring every ancestor is the bug that made this return whole
            // pages: each one inherits the same points, so <body> out-scores
            // the article it contains. Climbing one level keeps the real
            // container (a div wrapping its paragraphs) and leaves the page
            // wrapper competing with everything else instead of riding on top.
            val parent = parents[n] ?: continue

            // Commas and length together separate prose from lists. A list item
            // is long but comma-free; navigation is short and link-dense.
            val commas = text.count { it == ',' }
            val base = 1.0 + commas + (text.length / 100.0)
            // Link density penalises a paragraph that is mostly anchors, which
            // is what a tag cloud or a related-posts strip looks like.
            val linkDensity = linkDensity(n)
            val weighted = base * (1.0 - linkDensity)

            scores[parent] = (scores[parent] ?: 0.0) + weighted
            counts[parent] = (counts[parent] ?: 0) + 1
        }

        if (scores.isEmpty()) return null

        // A node boosted by its own class or id, not by its contents. This is
        // what picks `<div class="post-content">` over the div wrapping it.
        val best = scores.entries.maxByOrNull { (n, s) -> s * hintBoost(n) }?.key ?: return null

        val title = documentTitle(roots) ?: headingIn(best)
        val byline = bylineIn(best)

        // The reader prints the title itself, above the body. When the page's
        // own <h1> survives into the fragment the article opens with the
        // headline twice, and on Phoronix the author div sits between the two
        // halves, so the duplicate reads as a second headline separated by
        // gibberish. Drop the leading heading once it has been promoted.
        dropLeadingHeading(best, title)
        dropBylineNode(best)

        val html = serialise(best, pageUrl)
        if (html.isEmpty()) return null

        return Article(
            title = title,
            byline = byline,
            html = html,
            textLength = best.innerText().length,
        )
    }

    /** Fraction of a node's text that sits inside links. */
    private fun linkDensity(n: Html.Node): Double {
        val total = n.innerText().length
        if (total == 0) return 0.0
        return (n.linkTextLength().toDouble() / total).coerceIn(0.0, 1.0)
    }

    /**
     * Adjusts a candidate by what it calls itself.
     *
     * Clamped hard: a class string can contain several matches and the text
     * score is what actually identifies prose. This breaks ties, it does not
     * decide.
     */
    private fun hintBoost(n: Html.Node): Double {
        val id = (n.attrs["id"].orEmpty() + " " + n.attrs["class"].orEmpty()).lowercase()
        if (id.isEmpty()) return 1.0
        var boost = 1.0
        for (hint in LIKELY_HINTS) if (id.contains(hint)) boost += 0.25
        for (hint in UNLIKELY_HINTS) if (id.contains(hint)) boost -= 0.5
        // A negative score would make "most negative" the winner, so the floor
        // is a positive multiplier rather than zero.
        return boost.coerceIn(0.25, 2.0)
    }

    private val LIKELY_HINTS = listOf(
        "article", "post", "content", "entry", "main", "body", "story", "blog", "text",
    )

    private val UNLIKELY_HINTS = listOf(
        "nav", "menu", "sidebar", "comment", "footer", "header", "banner",
        "share", "social", "promo", "advert", "sponsor", "cookie", "consent",
        "subscribe", "newsletter", "related", "recommend", "widget", "popup",
        "modal", "breadcrumb", "pagination", "pager", "masthead", "toolbar",
    )

    /** Removes unwanted subtrees in place, before any scoring runs. */
    private fun prune(nodes: List<Html.Node>) {
        for (n in nodes) {
            if (n.name in UNLIKELY) {
                n.children.clear()
                n.text = null
                continue
            }
            prune(n.children)
        }
    }

    private fun documentTitle(roots: List<Html.Node>): String? {
        fun find(n: Html.Node): Html.Node? {
            if (n.name == "title") return n
            for (c in n.children) find(c)?.let { return it }
            return null
        }
        for (r in roots) find(r)?.let { t ->
            // "Post title | Site name" and "Post title - Site" are both common;
            // the article is the longer side of the split.
            val raw = t.innerText().trim()
            if (raw.isEmpty()) return null
            val parts = raw.split(" | ", " - ", " — ", " · ").map { it.trim() }.filter { it.isNotEmpty() }
            return when {
                parts.size < 2 -> raw
                else -> parts.maxByOrNull { it.length }
            }
        }
        return null
    }

    /**
     * Removes the opening heading when it repeats the title the reader is
     * about to print.
     *
     * Only the first heading is considered, and only if it appears before any
     * real prose -- a heading further down is a section header, not a title.
     */
    private fun dropLeadingHeading(root: Html.Node, title: String?) {
        if (title == null) return
        val want = normalise(title)
        if (want.isEmpty()) return

        val limit = minOf(root.children.size, 3)
        for (i in 0 until limit) {
            val c = root.children[i]
            if (c.name !in HEADING_TAGS) return
            if (normalise(c.innerText()) != want) return
            root.removeChild(c)
            return
        }
    }

    /**
     * Removes the page's own byline block from the body.
     *
     * The reader prints the byline separately, so leaving the block in place
     * renders it twice -- and because these are usually divs of running text
     * with links ("Written by NAME in CATEGORY on DATE"), the leftover reads
     * as a stray line under the headline rather than as an obvious duplicate.
     *
     * Matched on class, not on the byline text: by the time we know the name
     * the text is flattened and the node it came from is no longer locatable.
     */
    private fun dropBylineNode(root: Html.Node) {
        val doomed = ArrayList<Html.Node>()
        fun walk(n: Html.Node, depth: Int) {
            if (depth > 4) return
            for (c in n.children) {
                val cls = c.attrs["class"].orEmpty()
                if (c.name in BLOCK_TAGS && BYLINE_CLASS.containsMatchIn(cls)) {
                    doomed.add(c)
                } else {
                    walk(c, depth + 1)
                }
            }
        }
        walk(root, 0)
        for (d in doomed) d.parent?.removeChild(d)
    }

    private val HEADING_TAGS = setOf("h1", "h2")
    private val BLOCK_TAGS = setOf("div", "p", "span", "section", "header", "footer")
    private val BYLINE_CLASS = Regex("(?i)\\b(author|byline|posted-by)\\b")

    private fun normalise(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun headingIn(root: Html.Node): String? {
        fun find(n: Html.Node, depth: Int): Html.Node? {
            if (n.name == "h1" || n.name == "h2") {
                val t = n.innerText()
                if (t.length > 3) return n
            }
            if (depth > 4) return null
            for (c in n.children) find(c, depth + 1)?.let { return it }
            return null
        }
        return find(root, 0)?.innerText()
    }

    /**
     * The byline, or null.
     *
     * The old pattern had a second alternative that matched any lowercase word
     * followed by a date, so body prose answered it: a sentence containing
     * "on October 1, 2026" returned "October 1, 2026" as the author. Two
     * lookalikes are rejected now: a bare date, which no longer matches at all,
     * and "by the team", where the word after "by" is an article.
     */
    private fun bylineIn(root: Html.Node): String? {
        // Requires a capitalised name after by/written by, and refuses to start
        // on a common English word, which is what "by the team" trips.
        val re = Regex("(?i)\\b(?:written\\s+)?by[:\\s]+([A-Z][\\w.'-]+(?:\\s+(?:[A-Z][\\w.'-]*|[A-Z]\\.)){0,3})")
        for (m in re.findAll(root.innerText())) {
            val name = m.groupValues[1].trim()
            if (name.substringBefore(' ').lowercase() in NOT_A_NAME) continue
            return name
        }
        return null
    }

    /** Words that follow "by" in prose but never name an author. */
    private val NOT_A_NAME = setOf(
        "the", "a", "an", "this", "that", "these", "those",
        "our", "their", "your", "his", "her", "its", "my",
    )

    /**
     * Re-emits an element as an HTML fragment.
     *
     * Whitelist only: an article needs paragraphs, headings, lists, quotes,
     * code, images and links. Everything else on a modern page is a script or a
     * wrapper whose class means nothing once the surrounding CSS is gone, so
     * unknown tags are dropped while their contents are kept -- the div stack
     * is scaffolding, the text inside it is the article.
     *
     * Links and images are resolved to absolute URLs here rather than at render
     * time, because the fragment is later shown from a `data:` document with no
     * base URL to resolve against.
     */
    private fun serialise(root: Html.Node, pageUrl: String): String {
        val sb = StringBuilder()

        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun attr(s: String) = esc(s).replace("\"", "&quot;")

        fun go(n: Html.Node) {
            // Walk `order`, not "all my text, then my children".
            //
            // The flat text buffer has no idea where the children sat, so
            // emitting it first pushes every inline link to the end of its
            // paragraph: <strong><a>Qt</a> 6.12.0</strong> came out as
            // "6.12.0 Qt" with the link trailing the sentence. `order`
            // records what arrived when, so the run below stays in place.
            for (c in n.order) {
                if (c.isTextRun) {
                    val t = c.text()
                    if (t.isNotBlank()) sb.append(esc(t))
                    continue
                }
                when (c.name) {
                    "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
                    "blockquote", "pre", "code", "em", "strong", "b", "i", "u",
                    "figure", "figcaption", "hr", "table", "thead", "tbody",
                    "tr", "th", "td", "sup", "sub", "dl", "dt", "dd",
                    -> {
                        sb.append('<').append(c.name).append('>')
                        go(c)
                        sb.append("</").append(c.name).append('>')
                    }

                    "br" -> sb.append("<br>")

                    "a" -> {
                        val href = c.attrs["href"]
                        val abs = if (href != null) UrlResolve.resolve(pageUrl, href) else null
                        if (abs.isNullOrBlank() || abs.startsWith("javascript:", true)) {
                            go(c)
                        } else {
                            sb.append("<a href=\"").append(attr(abs)).append("\">")
                            go(c)
                            sb.append("</a>")
                        }
                    }

                    "img" -> {
                        // data-src and srcset carry the real image on lazy
                        // pages; src is frequently a placeholder pixel.
                        val raw = c.attrs["src"]?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                            ?: c.attrs["data-src"]
                            ?: c.attrs["srcset"]?.substringBefore(' ')?.substringBefore(',')
                        val abs = if (raw != null) UrlResolve.resolve(pageUrl, raw.trim()) else null
                        if (!abs.isNullOrBlank() && !abs.startsWith("data:")) {
                            sb.append("<img src=\"").append(attr(abs)).append('"')
                            c.attrs["alt"]?.let { sb.append(" alt=\"").append(attr(it)).append('"') }
                            sb.append('>')
                        }
                    }

                    // Unknown wrapper: drop the tag, keep the contents.
                    else -> go(c)
                }
            }
        }

        go(root)
        return sb.toString().trim()
    }
}
