package dev.daisy.core

/**
 * A tolerant HTML tree builder, for reading pages rather than feeds.
 *
 * [FeedParse] tokenises XML, which is right for feeds and wrong for pages.
 * HTML lets `<p>`, `<li>`, `<td>` and `<option>` open without a close tag, and
 * a page that trips that rule mis-nests the whole rest of the document. For a
 * feed the damage is local; here it is fatal, because Readability scores a
 * candidate by its text density and a mis-nested tree silently attributes text
 * to the wrong ancestor -- the extractor then confidently returns the wrong
 * block, with no error anywhere.
 *
 * So this does the two things XML scanning will not:
 *
 *  - It honours the optional-end-tag rules for the elements that matter. An
 *    opening `<p>` closes a `p` left open, a `<li>` closes a `li`, and a block
 *    element closes a `p` because a `p` cannot contain one.
 *  - It treats void elements as void. `<br>`, `<img>`, `<hr>`, `<meta>` and
 *    friends never take a close tag, so pushing them would leave them on the
 *    stack forever and swallow the remainder of the document.
 *
 * Everything here is best-effort: nothing throws on malformed input, because
 * the input is a stranger's page.
 */
object Html {

    /** Elements that never have content and never a close tag. */
    private val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr",
    )

    /**
     * Elements whose open tag implicitly closes an open `<p>`.
     *
     * This is the HTML rule that matters most in practice. Without it a page
     * written as `<p>one<p>two` nests the second paragraph inside the first,
     * and every score after that point counts text twice.
     */
    private val CLOSES_P = setOf(
        "address", "article", "aside", "blockquote", "div", "dl", "fieldset",
        "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4",
        "h5", "h6", "header", "hr", "main", "nav", "ol", "p", "pre", "section",
        "table", "ul",
    )

    /** Elements that close themselves of the same name when reopened. */
    private val SELF_CLOSING_SIBLINGS = setOf("li", "td", "th", "tr", "option", "dt", "dd")

    /** Tags whose content is literal text. */
    private val RAW_TEXT = setOf("script", "style", "textarea", "title")

    /** Name marker for the text-run nodes kept in [Node.order]. Not a real tag. */
    private const val TEXT_RUN = "\u0000text"

    /** One element. `text` is direct only; `order` preserves document order. */
    class Node(val name: String, val attrs: Map<String, String>, val parent: Node?) {
        val children = ArrayList<Node>()
        var text: StringBuilder? = null

        /**
         * What this node holds, in the order it appeared.
         *
         * A node needs this because a buffer of its own text plus a list of
         * its children loses the interleaving. `<strong><a>Qt</a> 6.12.0
         * </strong>` keeps "Qt" and " 6.12.0" in separate places, so anything
         * re-emitting the node has no way to know the link came first and
         * every inline link slides to the end of its paragraph.
         *
         * Entries are either a [Node] or a run of text appended under a node
         * created for that purpose, marked by [isTextRun].
         */
        val order = ArrayList<Node>()

        /** True for the pseudo-nodes that carry a text run in [order]. */
        val isTextRun: Boolean get() = name == TEXT_RUN

        /** Direct text, not including descendants. */
        fun text(): String = text?.toString().orEmpty()

        /** Records direct text, both in the flat buffer and in [order]. */
        fun appendText(t: String) {
            (text ?: StringBuilder().also { text = it }).append(t)
            val last = order.lastOrNull()
            val run = if (last != null && last.isTextRun) {
                last
            } else {
                Node(TEXT_RUN, emptyMap(), this).also { order.add(it) }
            }
            val rb = run.text ?: StringBuilder().also { run.text = it }
            rb.append(t)
        }

        /** Records a child, both in the child list and in [order]. */
        fun appendChild(c: Node) {
            children.add(c)
            order.add(c)
        }

        /**
         * Removes a child from BOTH the child list and [order].
         *
         * Dropping it from [children] alone is not enough: [order] holds the
         * same objects, and anything re-emitting the node walks `order`, so a
         * half-removal leaves the child in the output while every other
         * traversal believes it is gone. Removal lives here so the two lists
         * cannot drift apart at a call site.
         */
        fun removeChild(c: Node) {
            children.remove(c)
            order.remove(c)
        }

        /** All text under this node, whitespace collapsed. */
        fun innerText(): String {
            val sb = StringBuilder()
            fun go(n: Node) {
                sb.append(n.text())
                for (c in n.children) go(c)
            }
            go(this)
            return sb.toString().replace(Regex("\\s+"), " ").trim()
        }

        /** Link text length, for link density. */
        fun linkTextLength(): Int = innerTextIn(this) { it.name == "a" }

        private fun innerTextIn(start: Node, pred: (Node) -> Boolean): Int {
            var total = 0
            fun go(n: Node) {
                if (pred(n)) { total += n.innerText().length; return }
                for (c in n.children) go(c)
            }
            for (c in start.children) go(c)
            return total
        }

        /** Walks up looking for an ancestor with this tag name. */
        fun ancestor(name: String): Node? {
            var p = parent
            while (p != null) {
                if (p.name == name) return p
                p = p.parent
            }
            return null
        }
    }

    /** Parses a document and returns its top-level nodes. */
    fun parse(src: String): List<Node> {
        val roots = ArrayList<Node>()
        val stack = ArrayList<Node>()
        val text = StringBuilder()
        var i = 0
        val n = src.length

        fun appendText(s: String) {
            if (s.isEmpty()) return
            text.append(decodeEntities(s))
        }

        // Text belongs to the innermost open element, and nowhere else. An
        // ancestor that kept a copy of its descendants' text would report the
        // same characters twice, which inflates exactly the density score this
        // whole file exists to feed.
        fun flush() {
            if (text.isEmpty()) return
            val t = text.toString()
            text.setLength(0)
            val cur = stack.lastOrNull() ?: return
            cur.appendText(t)
        }

        fun push(node: Node) {
            if (stack.isEmpty()) roots.add(node) else stack.last().appendChild(node)
            stack.add(node)
        }

        fun closeTo(idx: Int) {
            while (stack.size > idx) stack.removeAt(stack.size - 1)
        }

        /** Pops an open element of this name if one is on the stack near the top. */
        fun implicitClose(names: Set<String>, limit: Int = 6) {
            for (k in stack.indices.reversed()) {
                if (stack[k].name in names) { closeTo(k); return }
                if (stack.size - k > limit) return
            }
        }

        while (i < n) {
            val lt = src.indexOf('<', i)
            if (lt < 0) { appendText(src.substring(i)); break }
            if (lt > i) appendText(src.substring(i, lt))

            // Comments, doctype, CDATA, processing instructions.
            if (src.startsWith("<!--", lt)) {
                val end = src.indexOf("-->", lt)
                if (end < 0) break
                i = end + 3
                continue
            }
            if (src.startsWith("<![CDATA[", lt)) {
                val end = src.indexOf("]]>", lt)
                if (end < 0) break
                appendText(src.substring(lt + 9, end))
                i = end + 3
                continue
            }
            if (src.startsWith("<!", lt) || src.startsWith("<?", lt)) {
                var j = lt + 2
                var depth = 0
                while (j < n) {
                    val c = src[j]
                    if (c == '[') depth++ else if (c == ']') depth--
                    else if (c == '>' && depth <= 0) { j++; break }
                    j++
                }
                i = j
                continue
            }

            val gt = src.indexOf('>', lt)
            if (gt < 0) { appendText(src.substring(lt)); break }
            var tag = src.substring(lt + 1, gt)
            i = gt + 1

            // A '>' inside an attribute value ends the tag early. Only worth
            // handling when the quote count says so.
            if (tag.count { it == '"' } % 2 == 1 || tag.count { it == '\'' } % 2 == 1) {
                val next = src.indexOf('>', gt + 1)
                if (next > 0) {
                    tag = src.substring(lt + 1, next)
                    i = next + 1
                }
            }

            flush()

            if (tag.startsWith("/")) {
                val name = tag.substring(1).trim().lowercase()
                for (k in stack.indices.reversed()) {
                    if (stack[k].name == name) { closeTo(k); break }
                }
                continue
            }

            val selfClosed = tag.endsWith("/")
            val body = if (selfClosed) tag.dropLast(1) else tag
            val nameEnd = body.indexOfFirst { it.isWhitespace() }
            val name = (if (nameEnd < 0) body else body.substring(0, nameEnd)).trim().lowercase()
            if (name.isEmpty() || !name[0].isLetter()) continue
            val attrs = if (nameEnd < 0) emptyMap() else parseAttrs(body.substring(nameEnd))

            // Implicit closes, before the new node is pushed.
            if (name in CLOSES_P) implicitClose(setOf("p"))
            if (name in SELF_CLOSING_SIBLINGS) implicitClose(setOf(name))

            val node = Node(name, attrs, stack.lastOrNull())
            push(node)
            if (selfClosed || name in VOID) stack.removeAt(stack.size - 1)

            // Raw text: consume verbatim to the matching close tag.
            if (!selfClosed && name in RAW_TEXT) {
                val close = src.indexOf("</$name", i, ignoreCase = true)
                val end = if (close < 0) n else close
                val raw = src.substring(i, end)
                if (raw.isNotEmpty()) {
                    node.appendText(raw)
                }
                i = end
            }
        }

        return roots
    }

    private val ATTR = Regex("""([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

    private fun parseAttrs(s: String): Map<String, String> {
        val out = HashMap<String, String>(4)
        for (m in ATTR.findAll(s)) {
            val v = m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
            out[m.groupValues[1].lowercase()] = decodeEntities(v)
        }
        return out
    }

    /**
     * Decodes the entities that actually appear in prose.
     *
     * A full HTML entity table is ~2,000 entries for a handful that matter; a
     * numeric reference covers the rest. An unknown named entity is left alone
     * rather than dropped, so nothing silently disappears from the text.
     */
    fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { sb.append(c); i++; continue }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 10) { sb.append(c); i++; continue }
            val ent = s.substring(i + 1, semi)
            val decoded = when {
                ent.startsWith("#x") || ent.startsWith("#X") ->
                    ent.drop(2).toIntOrNull(16)?.let { if (it in 1..0x10FFFF) String(Character.toChars(it)) else null }
                ent.startsWith("#") ->
                    ent.drop(1).toIntOrNull()?.let { if (it in 1..0x10FFFF) String(Character.toChars(it)) else null }
                ent == "amp" -> "&"
                ent == "lt" -> "<"
                ent == "gt" -> ">"
                ent == "quot" -> "\""
                ent == "apos" -> "'"
                ent == "nbsp" -> "\u00a0"
                ent == "mdash" -> "\u2014"
                ent == "ndash" -> "\u2013"
                ent == "hellip" -> "\u2026"
                ent == "rsquo" -> "\u2019"
                ent == "lsquo" -> "\u2018"
                ent == "rdquo" -> "\u201d"
                ent == "ldquo" -> "\u201c"
                else -> null
            }
            if (decoded != null) { sb.append(decoded); i = semi + 1 } else { sb.append(c); i++ }
        }
        return sb.toString()
    }
}
