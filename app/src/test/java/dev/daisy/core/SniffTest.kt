package dev.daisy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SniffTest {

    // --- feed sniffer --------------------------------------------------------

    @Test
    fun `rss 2 point 0 is a feed`() {
        assertTrue(FeedSniffer.looksLikeFeed("""<?xml version="1.0"?><rss version="2.0"><channel></channel></rss>"""))
    }

    @Test
    fun `atom is a feed`() {
        assertTrue(FeedSniffer.looksLikeFeed("""<feed xmlns="http://www.w3.org/2005/Atom"><title>x</title></feed>"""))
    }

    @Test
    fun `rdf rss 1 point 0 is a feed`() {
        assertTrue(FeedSniffer.looksLikeFeed("""<rdf:RDF xmlns:rss="..."><channel></channel></rdf:RDF>"""))
    }

    @Test
    fun `an xml prolog does not hide the root element`() {
        assertTrue(
            FeedSniffer.looksLikeFeed(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <rss version="2.0"><channel></channel></rss>
                """.trimIndent()
            )
        )
    }

    @Test
    fun `leading comments are skipped`() {
        assertTrue(
            FeedSniffer.looksLikeFeed("""<!-- generated --><feed><title>x</title></feed>""")
        )
    }

    @Test
    fun `a prolog then whitespace then the root still counts`() {
        assertTrue(
            FeedSniffer.looksLikeFeed("<?xml version=\"1.0\"?>\n\n  <feed></feed>")
        )
    }

    @Test
    fun `html is not a feed`() {
        assertFalse(FeedSniffer.looksLikeFeed("<!DOCTYPE html><html><head></head></html>"))
        assertFalse(FeedSniffer.looksLikeFeed("<html lang=\"en\"><body></body></html>"))
    }

    @Test
    fun `json is not a feed`() {
        assertFalse(FeedSniffer.looksLikeFeed("""{"items":[]}"""))
    }

    @Test
    fun `an empty body is not a feed`() {
        assertFalse(FeedSniffer.looksLikeFeed(""))
        assertFalse(FeedSniffer.looksLikeFeed("   \n\t  "))
    }

    @Test
    fun `an unclosed comment is rejected rather than scanned forever`() {
        assertFalse(FeedSniffer.looksLikeFeed("<!-- no end"))
    }

    // --- link discovery ------------------------------------------------------

    @Test
    fun `a standard rss link tag is found`() {
        val html = """<html><head><link rel="alternate" type="application/rss+xml" href="https://a.com/feed"></head></html>"""
        assertEquals(listOf("https://a.com/feed"), LinkDiscovery.rssLinksFrom(html, "https://a.com"))
    }

    @Test
    fun `attribute order does not matter`() {
        val html = """<link href="/feed.xml" type="application/rss+xml" rel="alternate">"""
        assertEquals(listOf("https://a.com/feed.xml"), LinkDiscovery.rssLinksFrom(html, "https://a.com/some/page"))
    }

    @Test
    fun `single quotes are accepted`() {
        val html = """<link rel='alternate' type='application/rss+xml' href='/rss'>"""
        assertEquals(listOf("https://a.com/rss"), LinkDiscovery.rssLinksFrom(html, "https://a.com/"))
    }

    @Test
    fun `a relative href resolves against the page directory`() {
        val html = """<link rel="alternate" type="application/rss+xml" href="feed.xml">"""
        assertEquals(
            listOf("https://a.com/blog/feed.xml"),
            LinkDiscovery.rssLinksFrom(html, "https://a.com/blog/post.html"),
        )
    }

    @Test
    fun `protocol relative hrefs inherit the scheme`() {
        val html = """<link rel="alternate" type="application/atom+xml" href="//cdn.a.com/feed">"""
        assertEquals(listOf("https://cdn.a.com/feed"), LinkDiscovery.rssLinksFrom(html, "https://a.com"))
    }

    @Test
    fun `a rel with several values still matches alternate`() {
        val html = """<link rel="alternate nofollow" type="application/atom+xml" href="https://a.com/atom">"""
        assertEquals(listOf("https://a.com/atom"), LinkDiscovery.rssLinksFrom(html, "https://a.com"))
    }

    @Test
    fun `plain stylesheet links are ignored`() {
        val html = """<link rel="stylesheet" href="/style.css"><link rel="icon" href="/f.ico">"""
        assertTrue(LinkDiscovery.rssLinksFrom(html, "https://a.com").isEmpty())
    }

    @Test
    fun `a link with a non-feed type is ignored`() {
        val html = """<link rel="alternate" type="text/plain" href="/notes.txt">"""
        assertTrue(LinkDiscovery.rssLinksFrom(html, "https://a.com").isEmpty())
    }

    @Test
    fun `multiple feeds are returned in document order`() {
        val html = """
            <link rel="alternate" type="application/rss+xml" href="/rss">
            <link rel="alternate" type="application/atom+xml" href="/atom">
        """.trimIndent()
        assertEquals(
            listOf("https://a.com/rss", "https://a.com/atom"),
            LinkDiscovery.rssLinksFrom(html, "https://a.com"),
        )
    }

    // --- Link: header --------------------------------------------------------

    @Test
    fun `the http link header is parsed`() {
        val header = """<https://a.com/feed>; rel="alternate"; type="application/rss+xml""""
        assertEquals(listOf("https://a.com/feed"), LinkDiscovery.rssLinksFromHeader(header, "https://a.com"))
    }

    @Test
    fun `multiple link header entries are parsed`() {
        val header = """<https://a.com/rss>; rel="alternate"; type="application/rss+xml", <https://a.com/style.css>; rel="preload"; as="style""""
        assertEquals(listOf("https://a.com/rss"), LinkDiscovery.rssLinksFromHeader(header, "https://a.com"))
    }

    // --- url resolve ---------------------------------------------------------

    @Test
    fun `absolute urls pass through unresolved`() {
        assertEquals("https://b.com/x", UrlResolve.resolve("https://a.com", "https://b.com/x"))
    }

    @Test
    fun `a root relative path replaces the whole path`() {
        assertEquals("https://a.com/rss", UrlResolve.resolve("https://a.com/blog/post", "/rss"))
    }

    @Test
    fun `a bare relative path attaches to the directory`() {
        assertEquals("https://a.com/blog/feed", UrlResolve.resolve("https://a.com/blog/post", "feed"))
    }

    @Test
    fun `an empty reference is the base without its fragment`() {
        assertEquals("https://a.com/x", UrlResolve.resolve("https://a.com/x#section", ""))
    }
}
