package dev.daisy.core

/**
 * Turns a feed body into items.
 *
 * XML is handled by a hand-rolled scanner rather than a DOM parser. Three
 * reasons, in order of weight:
 *
 *  - A DOM costs a document-sized allocation for a body we read once and drop.
 *    Feed bodies run to megabytes when a site embeds full content.
 *  - Android's XML parsers (XmlPullParser) are the Android stack; this file
 *    stays runnable in build.sh with nothing but kotlinc.
 *  - Feeds in the wild are frequently not well-formed. A DOM parser rejects the
 *    whole document over one stray ampersand; this one skips the broken element
 *    and keeps going, which is what a reader should do.
 *
 * What it does NOT do: namespaces are compared by local name only (`atom:link`
 * matches `link`), CDATA sections are read as text, and entities are decoded for
 * the five predefined ones plus numeric references. Custom DTD entities are not
 * expanded and it does not fetch external DTDs -- that is an XXE hole waiting to
 * happen, and no sane feed needs it.
 */
object FeedParser {

    sealed interface Result {
        data class Ok(val items: List<IncomingItem>, val title: String?) : Result

        /** Body was recognisably a feed but nothing usable came out of it. */
        data class Error(val reason: String) : Result
    }

    /** Element-local name, namespace prefix stripped. */
    private fun local(name: String): String = name.substringAfterLast(':')

    fun parse(body: String, baseUrl: String): Result {
        val roots = scan(body)
        if (roots.isEmpty()) return Result.Error("no elements")

        val root = roots.first()
        return when (local(root.name).lowercase()) {
            "rss" -> parseRss(root, baseUrl)
            "feed" -> parseAtom(root, baseUrl)
            // RDF is RSS 1.0: items are siblings of <channel>, not children.
            "rdf" -> parseRss(root, baseUrl)
            else -> Result.Error("not a feed: <${root.name}>")
        }
    }

    // --- RSS 2.0 and RSS 1.0 -------------------------------------------------

    private fun parseRss(root: El, baseUrl: String): Result {
        val channel = root.children.firstOrNull { local(it.name).equals("channel", true) }
        val title = channel?.children?.firstOrNull { local(it.name).equals("title", true) }
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }

        // RSS 1.0 puts <item> at the root; RSS 2.0 nests them in <channel>.
        val holders: List<El> = listOf(root) + listOfNotNull(channel)
        val itemEls = holders.flatMap { h ->
            h.children.filter { local(it.name).equals("item", true) }
        }
        if (itemEls.isEmpty()) return Result.Ok(emptyList(), title)

        return Result.Ok(itemEls.mapNotNull { toItem(it, baseUrl, atom = false) }, title)
    }

    // --- Atom ----------------------------------------------------------------

    private fun parseAtom(root: El, baseUrl: String): Result {
        val title = root.children.firstOrNull { local(it.name).equals("title", true) }
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }

        val entries = root.children.filter { local(it.name).equals("entry", true) }
        if (entries.isEmpty()) return Result.Ok(emptyList(), title)

        return Result.Ok(entries.mapNotNull { toItem(it, baseUrl, atom = true) }, title)
    }

    // --- one item ------------------------------------------------------------

    private fun toItem(el: El, baseUrl: String, atom: Boolean): IncomingItem? {
        val child = { n: String -> el.children.firstOrNull { local(it.name).equals(n, true) } }

        val title = child("title")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val published = dateFor(el, atom)

        val link = if (atom) {
            // Atom links carry rel; <link rel="alternate"> is the article, and
            // bare <link> is alternate. rel="self" points at the feed itself.
            val links = el.children.filter { local(it.name).equals("link", true) }
            val alt = links.firstOrNull {
                val rel = it.attrs["rel"]?.lowercase()
                rel == null || rel == "alternate"
            } ?: links.firstOrNull()
            alt?.attrs?.get("href")?.let { UrlResolve.resolve(baseUrl, it) }
        } else {
            child("link")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { UrlResolve.resolve(baseUrl, it) }
        }

        // Atom: <id> is the identity. RSS: <guid>, falling back to the link.
        val guid = when {
            atom -> child("id")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            else -> child("guid")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        }
        // Never null: with no guid and no link it hashes title+date, because
        // dropping the item is worse than a synthesised key. The parser decides
        // identity once, here, so the store and the merge layer agree.
        val identity = Identity.resolve(guid, link, title, published)

        val summary = if (atom) {
            // <summary> is the short one; <content> is the full body. Prefer
            // content when only it exists, since the reader shows a few lines.
            child("summary")?.text() ?: child("content")?.text()
        } else {
            child("description")?.text() ?: child("encoded")?.text()
        }

        return IncomingItem(
            guid = identity,
            title = title,
            link = link,
            summary = summary?.takeIf { it.isNotBlank() },
            imageUrl = imageFor(el, atom),
            published = published,
        )
    }

    /**
     * The item's lead image, or null.
     *
     * One pass, in document order, so the first usable image wins and the
     * preference is whatever the feed itself put first. A typed element is only
     * accepted when the type says image -- enclosures are usually audio or
     * video, and showing a podcast's mp3 as a picture is worse than no picture.
     * An UNTYPED url-bearing element is accepted: media:thumbnail and
     * media:content routinely omit the type, and omitting it is the only signal
     * many video feeds give.
     */
    private fun imageFor(el: El, atom: Boolean): String? {
        for (c in el.children) {
            val n = local(c.name).lowercase()
            if (atom && n == "link") continue
            if (n != "thumbnail" && n != "enclosure" && n != "content" && n != "image") continue

            val url = c.attrs["url"] ?: c.attrs["href"] ?: continue
            if (url.isBlank()) continue

            val type = c.attrs["type"]?.lowercase()
            if (type != null && type.isNotEmpty() && !type.startsWith("image/")) continue
            return url
        }
        return null
    }

    private fun dateFor(el: El, atom: Boolean): Long? {
        val raw = if (atom) {
            el.children.firstOrNull { local(it.name).equals("published", true) }?.text()
                ?: el.children.firstOrNull { local(it.name).equals("updated", true) }?.text()
        } else {
            el.children.firstOrNull { local(it.name).equals("pubDate", true) }?.text()
        }
        return raw?.let { Dates.parse(it) }
    }

    // --- scanner -------------------------------------------------------------

    /** One element, its attributes, and its children. Text lives in [text]. */
    class El(val name: String, val attrs: Map<String, String>) {
        val children = ArrayList<El>()
        var text: StringBuilder? = null

        /**
         * Direct text, with markup inside child elements dropped.
         *
         * RSS descriptions are HTML-escaped; after entity decoding that HTML is
         * literal text, which is what the reader wants -- it renders the markup.
         * But nested <item> elements would otherwise concatenate into the
         * parent, so only own-text is collected here.
         */
        fun text(): String = text?.toString().orEmpty()
    }

    /**
     * Parses into a forest and returns the top-level elements.
     *
     * Tolerant by construction: an unclosed tag is closed at end of input, an
     * unexpected close is ignored, and a `&` that starts no entity stays a `&`.
     * Nothing here can throw on bad input, which is the point.
     */
    fun scan(src: String): List<El> {
        val roots = ArrayList<El>()
        val stack = ArrayList<El>()
        val text = StringBuilder()
        var i = 0
        val n = src.length

        fun flush() {
            if (text.isEmpty()) return
            val t = text.toString()
            text.setLength(0)
            if (stack.isNotEmpty()) {
                val cur = stack.last()
                (cur.text ?: StringBuilder().also { cur.text = it }).append(t)
            }
        }

        fun addEl(e: El) {
            if (stack.isEmpty()) roots.add(e) else stack.last().children.add(e)
        }

        while (i < n) {
            val lt = src.indexOf('<', i)
            if (lt < 0) {
                text.append(decode(src.substring(i)))
                break
            }
            if (lt > i) text.append(decode(src.substring(i, lt)))

            // CDATA is literal text, not markup.
            if (src.startsWith("<![CDATA[", lt)) {
                val end = src.indexOf("]]>", lt)
                if (end < 0) { text.append(decode(src.substring(lt))); break }
                text.append(src.substring(lt + 9, end))
                i = end + 3
                continue
            }
            // Comments, doctype, processing instructions: skipped.
            if (src.startsWith("<!--", lt)) {
                val end = src.indexOf("-->", lt)
                if (end < 0) break
                i = end + 3
                continue
            }
            if (src.startsWith("<?", lt)) {
                val end = src.indexOf("?>", lt)
                if (end < 0) break
                i = end + 2
                continue
            }
            if (src.startsWith("<!", lt)) {
                // <!DOCTYPE ...> possibly with an internal subset.
                var j = lt + 2
                var depth = 0
                while (j < n) {
                    val ch = src[j]
                    if (ch == '[') depth++
                    else if (ch == ']') depth--
                    else if (ch == '>' && depth <= 0) { j++; break }
                    j++
                }
                i = j
                continue
            }

            val gt = src.indexOf('>', lt)
            if (gt < 0) { text.append(decode(src.substring(lt))); break }
            val tag = src.substring(lt + 1, gt)
            i = gt + 1

            if (tag.startsWith("/")) {
                flush()
                val name = tag.substring(1).trim()
                // Unwind to the matching open tag; ignore a stray close.
                for (k in stack.indices.reversed()) {
                    if (stack[k].name == name) {
                        while (stack.size > k) stack.removeAt(stack.size - 1)
                        break
                    }
                }
                continue
            }

            val selfClosing = tag.endsWith("/")
            val body = if (selfClosing) tag.dropLast(1) else tag
            val nameEnd = body.indexOfFirst { it.isWhitespace() }
            val name = if (nameEnd < 0) body.trim() else body.substring(0, nameEnd).trim()
            if (name.isEmpty()) continue
            val attrs = if (nameEnd < 0) emptyMap() else parseAttrs(body.substring(nameEnd))

            flush()
            val el = El(name, attrs)
            addEl(el)
            if (!selfClosing) stack.add(el)
        }
        flush()
        return roots
    }

    private val ATTR = Regex("""([\w:.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    private fun parseAttrs(s: String): Map<String, String> {
        val out = HashMap<String, String>(4)
        for (m in ATTR.findAll(s)) {
            val v = m.groupValues[2].ifEmpty { m.groupValues[3] }
            out[m.groupValues[1]] = decode(v)
        }
        return out
    }

    /**
     * Decodes the five predefined entities plus numeric references.
     *
     * An unknown entity is left as written rather than dropped: `&mdash;` in a
     * feed that forgot to declare it should look wrong, not silently vanish.
     */
    fun decode(s: String): String {
        if (s.indexOf('&') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { out.append(c); i++; continue }
            val semi = s.indexOf(';', i)
            if (semi < 0 || semi - i > 12) { out.append(c); i++; continue }
            val ent = s.substring(i + 1, semi)
            val rep: String? = when (ent) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> when {
                    ent.startsWith("#x") || ent.startsWith("#X") ->
                        ent.drop(2).toIntOrNull(16)?.let { cp -> chars(cp) }
                    ent.startsWith("#") ->
                        ent.drop(1).toIntOrNull()?.let { cp -> chars(cp) }
                    else -> null
                }
            }
            if (rep == null) { out.append(c); i++; continue }
            out.append(rep)
            i = semi + 1
        }
        return out.toString()
    }

    private fun chars(cp: Int): String? =
        if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else null
}
