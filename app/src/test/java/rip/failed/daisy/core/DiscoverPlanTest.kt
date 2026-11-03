package rip.failed.daisy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverPlanTest {

    private fun plan(vararg urls: String, budget: Long = 20_000) =
        DiscoverPlan(urls.map { Candidate(it, Candidate.Kind.AS_GIVEN) }, budget)

    private fun Attempt.isFeed() = outcome == Attempt.Outcome.FEED

    // --- the queue in and out -------------------------------------------------

    @Test
    fun `candidates come back in the order they went in`() {
        val p = plan("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), listOf(p.next()?.url, p.next()?.url, p.next()?.url))
    }

    @Test
    fun `an exhausted plan returns null rather than throwing`() {
        val p = plan("a")
        assertEquals("a", p.next()?.url)
        assertNull(p.next())
        assertTrue(p.exhausted)
    }

    // --- promotion ------------------------------------------------------------

    @Test
    fun `an advertised feed jumps ahead of speculative candidates`() {
        val p = plan("page", "/feed", "/rss")
        assertEquals("page", p.next()?.url)
        p.promote(listOf("https://site/real.xml"))

        // The advertised url is next, ahead of the conventional guesses.
        assertEquals("https://site/real.xml", p.next()?.url)
        assertEquals("/feed", p.next()?.url)
    }

    @Test
    fun `two promoted urls keep their declared order`() {
        val p = plan("page")
        assertEquals("page", p.next()?.url)
        p.promote(listOf("first.xml", "second.xml"))

        // Pushing to the front one at a time reverses; the plan must undo that.
        assertEquals("first.xml", p.next()?.url)
        assertEquals("second.xml", p.next()?.url)
    }

    @Test
    fun `an advertised url already queued is not added twice`() {
        val p = plan("page", "/feed")
        p.promote(listOf("/feed"))

        assertEquals("page", p.next()?.url)
        assertEquals(1, p.remaining)
        assertEquals("/feed", p.next()?.url)
        assertNull(p.next())
    }

    @Test
    fun `an advertised url already tried is not retried`() {
        val p = plan("page")
        assertEquals("page", p.next()?.url)
        p.charge(100, Attempt("page", Attempt.Outcome.NOT_A_FEED, "HTTP 200"))

        p.promote(listOf("page"))
        assertTrue("already tried, should not be requeued", p.exhausted)
    }

    // --- budget ---------------------------------------------------------------

    @Test
    fun `a plan can afford work while under budget`() {
        val p = plan("a", "b", budget = 1000)
        assertTrue(p.canAfford())
    }

    @Test
    fun `a plan cannot afford work once the budget is spent`() {
        val p = plan("a", "b", budget = 1000)
        p.charge(1000, Attempt("a", Attempt.Outcome.NOT_A_FEED, null))
        assertTrue(!p.canAfford())
    }

    @Test
    fun `budget is measured against total elapsed, not the last request`() {
        val p = plan("a", "b", "c", budget = 1000)
        p.charge(600, Attempt("a", Attempt.Outcome.NOT_A_FEED, null))
        p.charge(600, Attempt("b", Attempt.Outcome.NOT_A_FEED, null))
        // Neither request alone exhausted the budget; together they did.
        assertTrue(!p.canAfford())
        assertEquals(1200, p.elapsedMs)
    }

    // --- attempts -------------------------------------------------------------

    @Test
    fun `attempts accumulate in the order they were charged`() {
        val p = plan("a", "b")
        p.next(); p.charge(10, Attempt("a", Attempt.Outcome.HTTP_ERROR, "HTTP 404"))
        p.next(); p.charge(10, Attempt("b", Attempt.Outcome.NOT_A_FEED, "HTTP 200"))

        assertEquals(listOf("a", "b"), p.attempts.map { it.url })
    }

    @Test
    fun `a skipped candidate is recorded so the report does not imply it was fetched`() {
        val p = plan("a", "b", budget = 0)
        p.skip("a", "out of time")
        p.skip("b", "out of time")
        assertTrue(p.attempts.all { it.outcome == Attempt.Outcome.SKIPPED })
    }
}
