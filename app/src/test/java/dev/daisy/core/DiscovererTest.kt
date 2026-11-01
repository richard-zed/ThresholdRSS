package dev.daisy.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscovererTest {

    /** Serves canned bodies per url and advances a virtual clock on every call. */
    private class FakeFetcher(
        private val responses: Map<String, FetchResult>,
        private val latencyMs: Long = 100,
    ) : Fetcher {
        val requested = ArrayList<String>()
        private var clock = 0L

        fun nowMs(): Long = clock
        fun tick(ms: Long) { clock += ms }

        override suspend fun get(url: String): FetchResult {
            requested.add(url)
            clock += latencyMs
            return responses[url] ?: FetchResult.Failed(404, "not found")
        }
    }

    private fun rss(title: String) =
        """<?xml version="1.0"?><rss version="2.0"><channel><title>$title</title></channel></rss>"""

    private fun html(vararg feedHrefs: String): String {
        val links = feedHrefs.joinToString("") {
            """<link rel="alternate" type="application/rss+xml" href="$it">"""
        }
        return "<html><head>$links</head><body>hi</body></html>"
    }

    private fun run(
        fetcher: FakeFetcher,
        input: String,
        remembered: String? = null,
        budget: Long = 20_000,
        cancelled: () -> Boolean = { false },
    ): Pair<DiscoverState, List<DiscoverState>> {
        val states = ArrayList<DiscoverState>()
        val result = runBlocking {
            Discoverer(fetcher, budget).discover(
                input = input,
                remembered = remembered,
                onState = { states.add(it) },
                isCancelled = cancelled,
                now = { fetcher.nowMs() },
            )
        }
        return result to states
    }

    // --- happy path -----------------------------------------------------------

    @Test
    fun `a direct feed url resolves on the first request`() {
        val f = FakeFetcher(mapOf("https://site/x.xml" to FetchResult.Ok(
            "https://site/x.xml", "application/rss+xml", rss("My Feed"), 200,
        )))
        val (state, _) = run(f, "https://site/x.xml")

        assertEquals(1, f.requested.size)
        assertTrue(state is DiscoverState.Found)
        val found = state as DiscoverState.Found
        assertEquals("https://site/x.xml", found.feedUrl)
        assertEquals("My Feed", found.title)
    }

    @Test
    fun `the final url after redirects is reported, not the requested one`() {
        val f = FakeFetcher(mapOf("https://site/feed" to FetchResult.Ok(
            "https://site/feed.xml", null, rss("T"), 200,
        )))
        val (state, _) = run(f, "https://site/feed")
        assertEquals("https://site/feed.xml", (state as DiscoverState.Found).feedUrl)
    }

    // --- the advertised-link promotion ---------------------------------------

    @Test
    fun `a link advertised by the page is tried before guessed paths`() {
        val f = FakeFetcher(mapOf(
            "https://site/blog" to FetchResult.Ok("https://site/blog", "text/html",
                html("https://site/real.xml"), 200),
            "https://site/real.xml" to FetchResult.Ok("https://site/real.xml", null, rss("R"), 200),
        ))
        val (state, _) = run(f, "https://site/blog")

        assertEquals("https://site/real.xml", (state as DiscoverState.Found).feedUrl)
        // The advertised url is request 2. A conventional path would only be
        // reached if promotion had not happened.
        assertEquals(listOf("https://site/blog", "https://site/real.xml"), f.requested)
    }

    // --- failure paths --------------------------------------------------------

    @Test
    fun `every candidate failing yields NotFound with each attempt recorded`() {
        val f = FakeFetcher(emptyMap())
        val (state, _) = run(f, "https://site/blog")

        assertTrue(state is DiscoverState.NotFound)
        val tried = (state as DiscoverState.NotFound).tried
        assertEquals(f.requested.size, tried.size)
        assertTrue(tried.none { it.outcome == Attempt.Outcome.SKIPPED })
    }

    @Test
    fun `an empty input asks nothing and reports nothing tried`() {
        val f = FakeFetcher(emptyMap())
        val (state, _) = run(f, "   ")
        assertTrue(state is DiscoverState.NotFound)
        assertTrue((state as DiscoverState.NotFound).tried.isEmpty())
        assertTrue(f.requested.isEmpty())
    }

    // --- budget ---------------------------------------------------------------

    @Test
    fun `a spent budget stops the walk and marks the rest skipped`() {
        val f = FakeFetcher(emptyMap(), latencyMs = 600)
        val (state, _) = run(f, "https://site/blog", budget = 1000)

        assertTrue(state is DiscoverState.NotFound)
        val tried = (state as DiscoverState.NotFound).tried
        assertTrue("should have stopped early", f.requested.size < tried.size)
        assertTrue("unattempted candidates should be marked skipped",
            tried.any { it.outcome == Attempt.Outcome.SKIPPED })
        assertTrue(tried.any { it.outcome == Attempt.Outcome.SKIPPED && it.detail == "out of time" })
    }

    // --- remembering ----------------------------------------------------------

    @Test
    fun `a remembered url is tried before the generated candidates`() {
        val f = FakeFetcher(mapOf(
            "https://site/known.xml" to FetchResult.Ok("https://site/known.xml", null, rss("K"), 200),
        ))
        val (state, _) = run(f, "https://site", remembered = "https://site/known.xml")

        assertEquals("https://site/known.xml", f.requested.first())
        assertEquals(1, f.requested.size)
        assertTrue(state is DiscoverState.Found)
    }

    @Test
    fun `a remembered url that no longer works falls through to the normal walk`() {
        val f = FakeFetcher(mapOf(
            "https://site" to FetchResult.Ok("https://site", null, rss("Fresh"), 200),
        ))
        val (state, _) = run(f, "https://site", remembered = "https://site/dead.xml")

        assertEquals("https://site/dead.xml", f.requested.first())
        assertTrue(state is DiscoverState.Found)
        assertEquals("Fresh", (state as DiscoverState.Found).title)
    }

    // --- cancellation ---------------------------------------------------------

    @Test
    fun `a cancelled walk reports Idle rather than a result`() {
        val f = FakeFetcher(mapOf(
            "https://site" to FetchResult.Ok("https://site", null, rss("X"), 200),
        ))
        val (state, _) = run(f, "https://site", cancelled = { true })
        assertEquals(DiscoverState.Idle, state)
        assertTrue(f.requested.isEmpty())
    }

    @Test
    fun `a walk cancelled mid-flight does not emit a stale Found`() {
        val f = FakeFetcher(mapOf(
            "https://site" to FetchResult.Ok("https://site", null, rss("X"), 200),
        ))
        var calls = 0
        val (state, _) = run(f, "https://site", cancelled = { ++calls > 1 })
        assertEquals(DiscoverState.Idle, state)
    }

    // --- state emissions ------------------------------------------------------

    @Test
    fun `Looking names the url being fetched`() {
        val f = FakeFetcher(mapOf(
            "https://site/x.xml" to FetchResult.Ok("https://site/x.xml", null, rss("T"), 200),
        ))
        val (_, states) = run(f, "https://site/x.xml")

        val looking = states.filterIsInstance<DiscoverState.Looking>()
        assertEquals(1, looking.size)
        assertEquals("https://site/x.xml", looking.first().current)
    }

    @Test
    fun `the final state is emitted exactly once`() {
        val f = FakeFetcher(mapOf(
            "https://site/x.xml" to FetchResult.Ok("https://site/x.xml", null, rss("T"), 200),
        ))
        val (_, states) = run(f, "https://site/x.xml")
        assertEquals(1, states.count { it is DiscoverState.Found })
        assertEquals(states.last(), states.lastOrNull { it is DiscoverState.Found })
    }
}
