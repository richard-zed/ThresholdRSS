package rip.failed.daisy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuffixRuleTest {

    private fun suffixUrls(input: String) =
        UrlCandidates.forInput(input)
            .filter { it.kind == Candidate.Kind.SUFFIX_RULE }
            .map { it.url }

    // --- github --------------------------------------------------------------

    @Test
    fun `a github user page maps to its atom feed`() {
        assertEquals(listOf("https://github.com/someone.atom"), suffixUrls("https://github.com/someone"))
    }

    @Test
    fun `a github repo maps to releases and commits`() {
        // Two candidates, both real. releases is listed first because a repo
        // that publishes releases is the more useful feed of the two.
        assertEquals(
            listOf(
                "https://github.com/someone/repo/releases.atom",
                "https://github.com/someone/repo/commits.atom",
            ),
            suffixUrls("https://github.com/someone/repo"),
        )
    }

    @Test
    fun `a trailing slash does not change the github result`() {
        assertEquals(listOf("https://github.com/someone.atom"), suffixUrls("https://github.com/someone/"))
    }

    // --- rule precedence -----------------------------------------------------

    @Test
    fun `the at-shaped rule does not claim a url whose host has its own rule`() {
        // Regression: medium.com is also /@name, and the generic @-shape branch
        // used to sit ahead of it and hand back medium.com/@someone.rss. The
        // shape rule is a fallback for fediverse instances, which have no shared
        // domain, so a host-specific rule must always win.
        assertEquals(
            listOf("https://medium.com/@someone/feed"),
            suffixUrls("https://medium.com/@someone"),
        )
    }

    @Test
    fun `an at-shaped path on an unknown host still gets the rss suffix`() {
        // The other half of the same rule: nothing claims mastodon.social, so
        // the shape branch must still fire for it.
        assertEquals(
            listOf(
                "https://mastodon.social/@someone.rss",
                "https://mastodon.social/users/someone.rss",
            ),
            suffixUrls("https://mastodon.social/@someone"),
        )
    }

    @Test
    fun `no two host rules can both claim the same url`() {
        // Every host-specific prefix here must be mutually exclusive. If a new
        // rule is added whose host also matches an earlier one, the earlier one
        // silently wins and this catches it only if the hosts overlap -- so the
        // real protection is that each branch names a distinct host.
        val hosts = listOf(
            "https://github.com/someone",
            "https://medium.com/@someone",
            "https://someone.tumblr.com",
            "https://news.ycombinator.com",
        )
        val results = hosts.map { suffixUrls(it) }
        assertEquals(4, results.count { it.isNotEmpty() })
        assertEquals(4, results.flatten().distinct().size)
    }

    // --- medium --------------------------------------------------------------

    @Test
    fun `a medium profile maps to feed`() {
        assertEquals(
            listOf("https://medium.com/@someone/feed"),
            suffixUrls("https://medium.com/@someone"),
        )
    }

    @Test
    fun `a medium subdomain maps to feed`() {
        assertEquals(
            listOf("https://someone.medium.com/feed"),
            suffixUrls("https://someone.medium.com"),
        )
    }

    // --- tumblr --------------------------------------------------------------

    @Test
    fun `a tumblr blog maps to rss`() {
        assertEquals(
            listOf("https://someone.tumblr.com/rss"),
            suffixUrls("https://someone.tumblr.com"),
        )
    }

    // --- hacker news ---------------------------------------------------------

    @Test
    fun `the hacker news front page maps to rss`() {
        assertEquals(
            listOf("https://news.ycombinator.com/rss"),
            suffixUrls("https://news.ycombinator.com"),
        )
    }

    // --- lemmy / mastodon ----------------------------------------------------

    @Test
    fun `a mastodon profile maps to rss`() {
        assertEquals(
            listOf(
                "https://mastodon.social/@someone.rss",
                "https://mastodon.social/users/someone.rss",
            ),
            suffixUrls("https://mastodon.social/@someone"),
        )
    }

    @Test
    fun `an already suffixed url gains no duplicate rule`() {
        assertTrue(suffixUrls("https://mastodon.social/@someone.rss").isEmpty())
    }

    // --- bluesky -------------------------------------------------------------

    @Test
    fun `bluesky needs no rule because the profile page advertises its feed`() {
        // bsky.app/profile/<handle>/rss is real, but Bluesky embeds a
        // <link rel=alternate> on the profile page, so generic discovery finds
        // it. A dedicated rule would be a second request for the same answer.
        assertTrue(suffixUrls("https://bsky.app/profile/someone.bsky.social").isEmpty())
    }

    @Test
    fun `bluesky still reaches generic discovery`() {
        val list = UrlCandidates.forInput("https://bsky.app/profile/someone.bsky.social")
        assertEquals("https://bsky.app/profile/someone.bsky.social", list.first().url)
        assertTrue("conventional paths should be tried as a fallback", list.size > 1)
    }

    // --- non-matches ---------------------------------------------------------

    @Test
    fun `an unrelated url gains no suffix rule`() {
        assertTrue(suffixUrls("https://example.com/blog/post").isEmpty())
    }

    @Test
    fun `path segments counts the path, not the host`() {
        assertEquals(0, UrlCandidates.pathSegments("https://example.com"))
        assertEquals(1, UrlCandidates.pathSegments("https://example.com/a"))
        assertEquals(2, UrlCandidates.pathSegments("https://example.com/a/b"))
        assertEquals(2, UrlCandidates.pathSegments("https://example.com/a/b/"))
        assertEquals(2, UrlCandidates.pathSegments("https://example.com/a/b?x=1"))
        assertEquals(2, UrlCandidates.pathSegments("https://example.com/a/b#frag"))
    }

    @Test
    fun `a suffix rule never leaves the original origin`() {
        // The whole point of the tier: a suffix rule is one request to the same
        // host. Anything that rewrites the host is a pattern rule.
        val list = UrlCandidates.forInput("https://github.com/someone/repo")
            .filter { it.kind == Candidate.Kind.SUFFIX_RULE }
        assertTrue(list.isNotEmpty())
        list.forEach {
            assertTrue("${it.url} left github.com", it.url.startsWith("https://github.com/"))
        }
    }
}
