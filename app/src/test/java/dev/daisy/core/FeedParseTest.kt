package dev.daisy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedParseTest {

    private fun items(body: String, base: String = "https://example.com/"): List<IncomingItem> {
        val r = FeedParser.parse(body, base)
        assertTrue("expected Ok, got $r", r is FeedParser.Result.Ok)
        return (r as FeedParser.Result.Ok).items
    }

    private fun rss(vararg items: String): String = """
        <?xml version="1.0"?>
        <rss version="2.0"><channel>
          <title>Example</title>
          ${items.joinToString("")}
        </channel></rss>
    """.trimIndent()

    // --- identity and titles -------------------------------------------------

    @Test
    fun `an rss item keeps its guid verbatim`() {
        val i = items(rss("<item><title>A</title><guid>tag:example,2024:a</guid></item>")).single()
        assertEquals("tag:example,2024:a", i.guid)
        assertEquals("A", i.title)
    }

    @Test
    fun `an atom entry uses id for identity`() {
        val body = """<feed xmlns="http://www.w3.org/2005/Atom">
            <title>X</title>
            <entry><title>E</title><id>urn:uuid:1234</id></entry>
        </feed>"""
        val i = items(body).single()
        assertEquals("urn:uuid:1234", i.guid)
    }

    @Test
    fun `an item with no guid falls back to a link hash`() {
        val i = items(rss("<item><title>A</title><link>https://example.com/a</link></item>")).single()
        assertEquals("hash:" + Identity.stableHash("https://example.com/a"), i.guid)
    }

    @Test
    fun `an item with no title is dropped`() {
        assertTrue(items(rss("<item><guid>x</guid></item>")).isEmpty())
    }

    @Test
    fun `a title-only item still gets an identity`() {
        // Identity hashs title+date rather than dropping it.
        val i = items(rss("<item><title>Bare</title></item>")).single()
        assertTrue("expected a body: prefix, got ${i.guid}", i.guid.startsWith("body:"))
    }

    // --- links ---------------------------------------------------------------

    @Test
    fun `a relative rss link resolves against the feed url`() {
        val i = items(
            rss("<item><title>A</title><link>/posts/1</link></item>"),
            base = "https://example.com/feed.xml",
        ).single()
        assertEquals("https://example.com/posts/1", i.link)
    }

    @Test
    fun `atom prefers rel alternate over self`() {
        val body = """<feed xmlns="http://www.w3.org/2005/Atom"><entry>
            <title>E</title><id>1</id>
            <link rel="self" href="https://example.com/feed"/>
            <link rel="alternate" type="text/html" href="https://example.com/post"/>
        </entry></feed>"""
        assertEquals("https://example.com/post", items(body).single().link)
    }

    @Test
    fun `a bare atom link is alternate`() {
        val body = """<feed xmlns="http://www.w3.org/2005/Atom"><entry>
            <title>E</title><id>1</id><link href="https://example.com/p"/>
        </entry></feed>"""
        assertEquals("https://example.com/p", items(body).single().link)
    }

    // --- bodies --------------------------------------------------------------

    @Test
    fun `an html-escaped description is decoded to markup`() {
        val i = items(rss(
            "<item><title>A</title><guid>1</guid>" +
                "<description>&lt;p&gt;Hello&lt;/p&gt;</description></item>",
        )).single()
        assertEquals("<p>Hello</p>", i.summary)
    }

    @Test
    fun `a cdata description survives untouched`() {
        val i = items(rss(
            "<item><title>A</title><guid>1</guid>" +
                "<description><![CDATA[<p>Raw</p>]]></description></item>",
        )).single()
        assertEquals("<p>Raw</p>", i.summary)
    }

    @Test
    fun `atom falls back to content when there is no summary`() {
        val body = """<feed xmlns="http://www.w3.org/2005/Atom"><entry>
            <title>E</title><id>1</id><content>Full body</content>
        </entry></feed>"""
        assertEquals("Full body", items(body).single().summary)
    }

    @Test
    fun `numeric entities decode`() {
        val i = items(rss("<item><title>A &#8212; B</title><guid>1</guid></item>")).single()
        assertEquals("A \u2014 B", i.title)
    }

    @Test
    fun `decoding does not run twice`() {
        // &amp;#8212; is an escaped em-dash REFERENCE, not an em dash. A second
        // decode pass would turn it into one and corrupt the text.
        val i = items(rss("<item><title>A &amp;#8212; B</title><guid>1</guid></item>")).single()
        assertEquals("A &#8212; B", i.title)
    }

    @Test
    fun `an unknown entity is left as written`() {
        val i = items(rss("<item><title>a &mdash; b</title><guid>1</guid></item>")).single()
        assertEquals("a &mdash; b", i.title)
    }

    // --- dates ---------------------------------------------------------------

    @Test
    fun `an rfc822 date parses`() {
        val i = items(rss(
            "<item><title>A</title><guid>1</guid><pubDate>Tue, 10 Jun 2014 04:00:00 GMT</pubDate></item>",
        )).single()
        assertEquals(1_402_372_800_000L, i.published)
    }

    @Test
    fun `an rfc3339 date parses`() {
        val body = """<feed xmlns="http://www.w3.org/2005/Atom"><entry>
            <title>E</title><id>1</id><published>2014-06-10T04:00:00Z</published>
        </entry></feed>"""
        assertEquals(1_402_372_800_000L, items(body).single().published)
    }

    @Test
    fun `atom falls back to updated when published is absent`() {
        val body = """<feed xmlns="http://www.w3.org/2005/Atom"><entry>
            <title>E</title><id>1</id><updated>2014-06-10T04:00:00Z</updated>
        </entry></feed>"""
        assertEquals(1_402_372_800_000L, items(body).single().published)
    }

    @Test
    fun `an unparseable date is null, not zero`() {
        val i = items(rss("<item><title>A</title><guid>1</guid><pubDate>last tuesday</pubDate></item>")).single()
        assertNull(i.published)
    }

    // --- document shapes -----------------------------------------------------

    @Test
    fun `rss 1_0 items are siblings of channel`() {
        val body = """<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                                xmlns="http://purl.org/rss/1.0/">
            <channel><title>Old</title></channel>
            <item><title>I</title><link>https://example.com/i</link></item>
        </rdf:RDF>"""
        assertEquals(1, items(body).size)
    }

    @Test
    fun `a feed with no items is an empty ok, not an error`() {
        val r = FeedParser.parse(rss(), "https://example.com/")
        assertTrue(r is FeedParser.Result.Ok)
        assertTrue((r as FeedParser.Result.Ok).items.isEmpty())
        assertEquals("Example", r.title)
    }

    @Test
    fun `html is rejected as not a feed`() {
        val r = FeedParser.parse("<html><body>nope</body></html>", "https://example.com/")
        assertTrue(r is FeedParser.Result.Error)
    }

    @Test
    fun `an unclosed item does not swallow the next one`() {
        // Deliberately malformed: real feeds do this and a DOM parser would throw.
        val body = "<rss><channel>" +
            "<item><title>A</title><guid>1</guid>" +
            "<item><title>B</title><guid>2</guid></item>" +
            "</channel></rss>"
        val got = items(body)
        assertEquals(listOf("A", "B"), got.map { it.title })
    }

    @Test
    fun `a stray close tag is ignored`() {
        val body = "<rss><channel><item><title>A</title><guid>1</guid></item></span></channel></rss>"
        assertEquals(1, items(body).size)
    }

    @Test
    fun `a bare ampersand does not break the scan`() {
        val body = "<rss><channel><item><title>AT&T</title><guid>1</guid></item></channel></rss>"
        assertEquals("AT&T", items(body).single().title)
    }

    @Test
    fun `a namespace prefix on elements is ignored`() {
        val body = """<rss xmlns:dc="http://purl.org/dc/elements/1.1/"><channel>
            <item><dc:title>Prefixed</dc:title><guid>1</guid></item>
        </channel></rss>"""
        assertEquals("Prefixed", items(body).single().title)
    }

    @Test
    fun `a comment before the root does not hide it`() {
        val body = "<!-- hi --><rss><channel><item><title>A</title><guid>1</guid></item></channel></rss>"
        assertEquals(1, items(body).size)
    }

    @Test
    fun `an enclosure supplies an image only when it is an image`() {
        val audio = rss(
            "<item><title>A</title><guid>1</guid>" +
                "<enclosure url=\"https://example.com/a.mp3\" type=\"audio/mpeg\"/></item>",
        )
        assertNull(items(audio).single().imageUrl)

        val image = rss(
            "<item><title>A</title><guid>1</guid>" +
                "<enclosure url=\"https://example.com/a.jpg\" type=\"image/jpeg\"/></item>",
        )
        assertEquals("https://example.com/a.jpg", items(image).single().imageUrl)
    }

    @Test
    fun `media thumbnail supplies an image`() {
        val body = rss(
            "<item><title>A</title><guid>1</guid>" +
                "<media:thumbnail xmlns:media=\"http://search.yahoo.com/mrss/\" " +
                "url=\"https://example.com/t.jpg\"/></item>",
        )
        assertEquals("https://example.com/t.jpg", items(body).single().imageUrl)
    }
}
