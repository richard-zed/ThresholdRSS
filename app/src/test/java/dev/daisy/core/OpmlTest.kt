package dev.daisy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpmlTest {

    private fun feed(url: String, title: String, site: String? = null) =
        Feed(id = 1, url = url, title = title, siteUrl = site)

    @Test
    fun `export is well formed and names every feed`() {
        val doc = Opml.export(listOf(feed("https://a.com/rss", "A"), feed("https://b.com/rss", "B")))
        assertTrue(doc.startsWith("<?xml"))
        assertTrue(doc.contains("<opml version=\"2.0\">"))
        assertTrue(doc.contains("""xmlUrl="https://a.com/rss""""))
        assertTrue(doc.contains("""xmlUrl="https://b.com/rss""""))
        assertTrue(doc.trimEnd().endsWith("</opml>"))
    }

    @Test
    fun `an ampersand in a title is escaped`() {
        val doc = Opml.export(listOf(feed("https://a.com/rss", "AT&T")))
        assertTrue("unescaped & makes the file unreadable elsewhere", doc.contains("""text="AT&amp;T""""))
        assertTrue(!doc.contains("AT&T\""))
    }

    @Test
    fun `angle brackets and quotes are escaped`() {
        val doc = Opml.export(listOf(feed("https://a.com/rss", """R&D <notes> "x"""")))
        assertTrue(doc.contains("&lt;notes&gt;"))
        assertTrue(doc.contains("&quot;x&quot;"))
        assertTrue(doc.contains("&amp;"))
    }

    @Test
    fun `an untitled feed falls back to its url`() {
        val doc = Opml.export(listOf(feed("https://a.com/rss", "")))
        assertTrue(doc.contains("""text="https://a.com/rss""""))
    }

    @Test
    fun `htmlUrl is written only when there is a site`() {
        val withSite = Opml.export(listOf(feed("https://a.com/rss", "A", "https://a.com")))
        assertTrue(withSite.contains("""htmlUrl="https://a.com""""))

        val without = Opml.export(listOf(feed("https://a.com/rss", "A")))
        assertTrue(!without.contains("htmlUrl="))
    }

    @Test
    fun `export then import round trips`() {
        val feeds = listOf(
            feed("https://a.com/rss", "A & B", "https://a.com"),
            feed("https://b.com/atom", "B <c>"),
        )
        val back = Opml.parse(Opml.export(feeds))
        assertEquals(listOf("https://a.com/rss", "https://b.com/atom"), back.map { it.url })
        // Escaping is undone by the scanner's entity decode.
        assertEquals(listOf("A & B", "B <c>"), back.map { it.title })
        assertEquals("https://a.com", back[0].siteUrl)
    }

    @Test
    fun `import accepts attributes in any order and case`() {
        val doc = """<opml><body>
            <outline xmlUrl="https://a.com/rss" text="A"/>
            <outline TEXT="B" XMLURL="https://b.com/rss"/>
        </body></opml>"""
        assertEquals(listOf("https://a.com/rss", "https://b.com/rss"), Opml.parse(doc).map { it.url })
    }

    @Test
    fun `nested folders are flattened`() {
        val doc = """<opml><body>
            <outline text="Tech">
              <outline text="A" xmlUrl="https://a.com/rss"/>
              <outline text="Deep">
                <outline text="B" xmlUrl="https://b.com/rss"/>
              </outline>
            </outline>
        </body></opml>"""
        assertEquals(listOf("https://a.com/rss", "https://b.com/rss"), Opml.parse(doc).map { it.url })
    }

    @Test
    fun `an outline with no xmlUrl is a folder, not a feed`() {
        val doc = """<opml><body><outline text="Just a folder"/></body></opml>"""
        assertTrue(Opml.parse(doc).isEmpty())
    }

    @Test
    fun `title falls back to text`() {
        val doc = """<opml><body><outline text="From text" xmlUrl="https://a.com/rss"/></body></opml>"""
        assertEquals("From text", Opml.parse(doc).single().title)
    }

    @Test
    fun `a duplicate url is returned twice, dedupe is the caller's call`() {
        val doc = """<opml><body>
            <outline xmlUrl="https://a.com/rss"/><outline xmlUrl="https://a.com/rss"/>
        </body></opml>"""
        assertEquals(2, Opml.parse(doc).size)
    }

    @Test
    fun `an empty document yields nothing rather than throwing`() {
        assertTrue(Opml.parse("").isEmpty())
        assertTrue(Opml.parse("<opml><body></body></opml>").isEmpty())
    }
}
