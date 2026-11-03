package rip.failed.daisy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverTest {

    private fun urls(input: String, siteHandles: Boolean = true) =
        UrlCandidates.forInput(input, siteHandles).map { it.url }

    // --- ordering ------------------------------------------------------------

    @Test
    fun `the url as typed is always tried first`() {
        assertEquals(
            "https://example.com/feed",
            urls("https://example.com/feed").first(),
        )
    }

    @Test
    fun `zero-cost suffix rules beat guessed paths`() {
        val list = urls("https://mastodon.social/@someone")
        // .rss costs no extra request, so it must precede anything speculative.
        // The typed url itself leads (index 0) -- it is the as-given attempt.
        val suffixIdx = list.indexOf("https://mastodon.social/@someone.rss")
        val pathIdx = list.indexOf("https://mastodon.social/feed")
        assertTrue("suffix rule should be present", suffixIdx > 0)
        assertTrue("suffix rule should precede guessed paths", suffixIdx < pathIdx)
    }

    @Test
    fun `the discovery page and the as-given url are one candidate, not two`() {
        // Both roles are served by a single GET: fetch the url, and if the body
        // is HTML we already hold it for the link scan. Two entries with the
        // same url would be two requests for one answer.
        val list = urls("https://example.com")
        assertEquals(1, list.count { it == "https://example.com" })
    }

    @Test
    fun `the as-given url is first so a direct feed link costs one request`() {
        val list = UrlCandidates.forInput("https://example.com/feed.xml")
        assertEquals(Candidate.Kind.AS_GIVEN, list.first().kind)
        assertEquals("https://example.com/feed.xml", list.first().url)
    }

    @Test
    fun `the discovery page comes before guessed paths`() {
        val list = urls("https://example.com")
        val pageIdx = list.indexOf("https://example.com")
        val pathIdx = list.indexOf("https://example.com/feed")
        assertTrue(pageIdx in 0 until pathIdx)
    }

    @Test
    fun `conventional paths are guessed in order`() {
        val paths = UrlCandidates.forInput("https://example.com")
            .filter { it.kind == Candidate.Kind.CONVENTIONAL_PATH }
            .map { it.url }
        assertEquals("https://example.com/feed", paths.first())
        assertTrue(paths.contains("https://example.com/rss.xml"))
    }

    @Test
    fun `a url is never listed twice`() {
        // youtube.com/feeds/... is not a conventional path, but /feed on a bare
        // origin duplicates nothing; the guard is what this pins.
        val list = UrlCandidates.forInput("https://example.com/feed").map { it.url }
        assertEquals(list.size, list.toSet().size)
    }

    // --- classification ------------------------------------------------------

    @Test
    fun `a youtube channel url maps straight to the canonical feed`() {
        val list = urls("https://www.youtube.com/channel/UCabc123")
        assertTrue(
            list.contains("https://www.youtube.com/feeds/videos.xml?channel_id=UCabc123")
        )
    }

    @Test
    fun `a youtube handle is not guessed at, because the id is unknown`() {
        val list = urls("https://www.youtube.com/@someone")
        // No feed url can be built without the channel id, so nothing is invented.
        assertTrue(list.none { it.contains("videos.xml") })
    }

    @Test
    fun `a youtube short link classifies as youtube`() {
        assertTrue(UrlCandidates.isYoutube("https://youtu.be/xyz"))
    }

    @Test
    fun `a mastodon profile gets one rss suffix`() {
        assertEquals(
            listOf("https://mastodon.social/@someone.rss"),
            UrlCandidates.forInput("https://mastodon.social/@someone")
                .filter { it.kind == Candidate.Kind.SUFFIX_RULE }
                .map { it.url },
        )
    }

    @Test
    fun `a subreddit gets both rss spellings`() {
        val suffix = UrlCandidates.forInput("https://reddit.com/r/android")
            .filter { it.kind == Candidate.Kind.SUFFIX_RULE }
            .map { it.url }
        assertEquals(
            listOf("https://reddit.com/r/android/.rss", "https://reddit.com/r/android.rss"),
            suffix,
        )
    }

    @Test
    fun `a url that already ends in rss is not suffixed again`() {
        val suffix = UrlCandidates.forInput("https://mastodon.social/@someone.rss")
            .filter { it.kind == Candidate.Kind.SUFFIX_RULE }
        assertTrue(suffix.isEmpty())
    }

    // --- rejection -----------------------------------------------------------

    // A bare host is the NORMAL input -- it is what a user pastes. This used to
    // return an empty plan, so discovery reported NotFound(tried=[]) having made
    // zero requests, while the same host with https:// worked. The scheme is
    // supplied by the planner now.
    @Test
    fun `a bare host is discovered, not rejected`() {
        val plan = UrlCandidates.forInput("example.com")
        assertTrue(plan.isNotEmpty())
        assertEquals("https://example.com", plan.first().url)
    }

    @Test
    fun `a bare host keeps its path`() {
        assertEquals(
            "https://example.com/blog",
            UrlCandidates.forInput("example.com/blog").first().url,
        )
    }

    @Test
    fun `a single word with no dot is still not a url`() {
        assertTrue(UrlCandidates.forInput("notaurl").isEmpty())
    }

    @Test
    fun `a sentence is not a url`() {
        assertTrue(UrlCandidates.forInput("this is not a link").isEmpty())
    }

    @Test
    fun `whitespace around a url is tolerated`() {
        assertEquals(
            "https://example.com/feed",
            UrlCandidates.forInput("  https://example.com/feed  ").first().url,
        )
    }

    @Test
    fun `origin drops the path`() {
        assertEquals("https://example.com", UrlCandidates.origin("https://example.com/a/b?c=1"))
    }

    @Test
    fun `channelIdInPath insists on the UC prefix`() {
        assertNull(UrlCandidates.channelIdInPath("https://www.youtube.com/channel/abc"))
        assertEquals(
            "UCxyz",
            UrlCandidates.channelIdInPath("https://www.youtube.com/channel/UCxyz/videos"),
        )
    }

    // --- indicator state -----------------------------------------------------

    @Test
    fun `the four indicator states are distinct`() {
        val states: List<DiscoverState> = listOf(
            DiscoverState.Idle,
            DiscoverState.Looking(),
            DiscoverState.Found("https://a/feed", "A"),
            DiscoverState.NotFound(listOf(Attempt("https://a", Attempt.Outcome.HTTP_ERROR, "404"))),
        )
        assertEquals(states.size, states.toSet().size)
    }

    // --- fediverse handles ---------------------------------------------------

    @Test
    fun `a fediverse handle resolves to its profile url`() {
        // The form people actually paste. Before the handle step existed this
        // returned an empty candidate list: `@someone@mastodon.social` was
        // split on '/' and its "host" failed the dotted-TLD check.
        assertEquals(
            "https://mastodon.social/@someone",
            UrlCandidates.profileUrlForHandle("@someone@mastodon.social"),
        )
    }

    @Test
    fun `the leading at sign is optional`() {
        assertEquals(
            UrlCandidates.profileUrlForHandle("@someone@mastodon.social"),
            UrlCandidates.profileUrlForHandle("someone@mastodon.social"),
        )
    }

    @Test
    fun `a handle produces feed candidates rather than an empty list`() {
        val list = urls("@someone@mastodon.social")
        assertEquals("https://mastodon.social/@someone", list.first())
        assertTrue("expected a .rss guess", list.contains("https://mastodon.social/@someone.rss"))
        assertTrue(
            "expected the /users path guess",
            list.contains("https://mastodon.social/users/someone.rss"),
        )
    }

    @Test
    fun `a bare handle names no instance and is left alone`() {
        assertNull(UrlCandidates.profileUrlForHandle("@someone"))
        assertTrue(urls("@someone").isEmpty())
    }

    @Test
    fun `a url is never mistaken for a handle`() {
        assertNull(UrlCandidates.profileUrlForHandle("https://mastodon.social/@someone"))
        // And it keeps leading its own walk.
        assertEquals(
            "https://mastodon.social/@someone",
            urls("https://mastodon.social/@someone").first(),
        )
    }

    @Test
    fun `a malformed handle is rejected rather than guessed at`() {
        assertNull(UrlCandidates.profileUrlForHandle("@a@b@c"))
        assertNull(UrlCandidates.profileUrlForHandle("@user@foo"))
        assertNull(UrlCandidates.profileUrlForHandle("@@host.com"))
    }

    @Test
    fun `the users path guess is not generated for a deeper profile path`() {
        val list = urls("https://mastodon.social/@someone/@other")
        assertFalse(
            "a two-segment @path is not a profile",
            list.any { it.contains("/users/") },
        )
    }
}
