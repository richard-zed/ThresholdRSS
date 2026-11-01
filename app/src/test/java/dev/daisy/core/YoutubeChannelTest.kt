package dev.daisy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeChannelTest {

    // --- forms that already carry an id ----------------------------------------

    @Test
    fun a channel url yields its feed without any resolution() {
        val id = YoutubeChannel.idFromChannelPath("https://www.youtube.com/channel/UCabc123XYZ")
        assertEquals("UCabc123XYZ", id)
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?channel_id=UCabc123XYZ",
            YoutubeChannel.feedUrl(id!!),
        )
    }

    @Test
    fun a channel url is not sent to resolution() {
        assertFalse(YoutubeChannel.needsResolution("https://www.youtube.com/channel/UCabc123XYZ"))
    }

    @Test
    fun a trailing path after the id is ignored() {
        assertEquals(
            "UCabc123XYZ",
            YoutubeChannel.idFromChannelPath("https://www.youtube.com/channel/UCabc123XYZ/videos"),
        )
    }

    @Test
    fun a channel segment with no id is not an id() {
        // "channel" with nothing after it, and a short non-UC value.
        assertNull(YoutubeChannel.idFromChannelPath("https://www.youtube.com/channel/"))
        assertNull(YoutubeChannel.idFromChannelPath("https://www.youtube.com/channel/short"))
        // UC prefix but nothing else is not an id either.
        assertNull(YoutubeChannel.idFromChannelPath("https://www.youtube.com/channel/UC"))
    }

    // --- forms that need a page fetch -----------------------------------------

    @Test
    fun handle c and user forms all need resolution() {
        assertTrue(YoutubeChannel.needsResolution("https://www.youtube.com/@someone"))
        assertTrue(YoutubeChannel.needsResolution("https://www.youtube.com/c/SomeName"))
        assertTrue(YoutubeChannel.needsResolution("https://www.youtube.com/user/olduser"))
    }

    @Test
    fun a video url is not a channel() {
        // The important negative: a watch link must not trigger a page fetch,
        // or every pasted video costs a wasted request.
        assertFalse(YoutubeChannel.needsResolution("https://www.youtube.com/watch?v=abc123"))
        assertFalse(YoutubeChannel.needsResolution("https://www.youtube.com/"))
    }

    @Test
    fun youtu be is treated as youtube() {
        // hostOf folds the short domain, so classification agrees.
        assertTrue(YoutubeChannel.needsResolution("https://youtu.be/@someone"))
    }

    // --- extraction from a fetched page ---------------------------------------

    @Test
    fun the canonical link is read first() {
        val page = """
            <html><head>
            <link rel="canonical" href="https://www.youtube.com/channel/UCcanonicalId">
            <meta itemprop="channelId" content="UCmetaId">
            </head><body></body></html>
        """.trimIndent()
        assertEquals("UCcanonicalId", YoutubeChannel.idFromPage(page))
    }

    @Test
    fun the embedded json blob is the fallback() {
        // No canonical tag, but the data blob is there. Quoting is the trap:
        // the key sits inside a JSON string with escaped quotes around it.
        val page = """<html><body><script>var ytInitialData = {"metadata":{"channelMetadataRenderer":{"externalId":"UCblobId","title":"x"}}};</script></body></html>"""
        assertEquals("UCblobId", YoutubeChannel.idFromPage(page))
    }

    @Test
    fun the itemprop meta is the last resort() {
        val page = """<html><head><meta itemprop="channelId" content="UCmetaId"></head></html>"""
        assertEquals("UCmetaId", YoutubeChannel.idFromPage(page))
    }

    @Test
    fun a page with no id reports null rather than guessing() {
        // Consent walls and 404s both land here. Returning a guess would
        // subscribe the user to the wrong channel.
        assertNull(YoutubeChannel.idFromPage("<html><body>Before you continue</body></html>"))
        assertNull(YoutubeChannel.idFromPage(""))
    }

    @Test
    fun a malformed id in the page is rejected() {
        val page = """<meta itemprop="channelId" content="not-a-channel-id">"""
        assertNull(YoutubeChannel.idFromPage(page))
    }

    @Test
    fun attribute order does not matter() {
        val page = """<link href="https://www.youtube.com/channel/UCswapped" rel="canonical">"""
        assertEquals("UCswapped", YoutubeChannel.idFromPage(page))
    }

    @Test
    fun single and double quotes are both accepted() {
        val dq = """<link rel="canonical" href="https://www.youtube.com/channel/UCdq">"""
        val sq = """<link rel='canonical' href='https://www.youtube.com/channel/UCsq'>"""
        assertEquals("UCdq", YoutubeChannel.idFromPage(dq))
        assertEquals("UCsq", YoutubeChannel.idFromPage(sq))
    }
}

class YoutubeCandidateTest {

    @Test
    fun a handle produces the page and no guessed paths() {
        val c = UrlCandidates.forInput("https://www.youtube.com/@someone")
        val kinds = c.map { it.kind }

        // The page itself must be on the list -- it is the discovery fetch.
        assertTrue(c.any { it.url == "https://www.youtube.com/@someone" })
        // And nothing may guess /feed, /rss, /feed.xml at youtube.com.
        assertFalse(
            "youtube must not guess conventional paths, got ${c.map { it.url }}",
            kinds.contains(Candidate.Kind.CONVENTIONAL_PATH),
        )
    }

    @Test
    fun a direct channel id produces the feed url() {
        val c = UrlCandidates.forInput("https://www.youtube.com/channel/UCdirect")
        assertEquals(
            "https://www.youtube.com/feeds/videos.xml?channel_id=UCdirect",
            c.first { it.kind == Candidate.Kind.PATTERN_RULE }.url,
        )
    }

    @Test
    fun an ordinary site still guesses conventional paths() {
        // The exclusion must be youtube-only; this is the case that would
        // silently break every other site if the condition were inverted.
        val c = UrlCandidates.forInput("https://example.com/blog")
        assertTrue(c.any { it.kind == Candidate.Kind.CONVENTIONAL_PATH })
    }
}
