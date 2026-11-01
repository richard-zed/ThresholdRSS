package dev.daisy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTest {

    private fun item(guid: String, published: Long? = 1000L, id: Long = 0) = Item(
        id = id,
        feedId = 1,
        guid = guid,
        title = "t-$guid",
        published = published,
        fetchedAt = 0,
    )

    private fun incoming(guid: String, published: Long? = 1000L) =
        IncomingItem(guid = guid, title = "t-$guid", published = published)

    @Test
    fun `a first sync inserts everything`() {
        val out = Sync.apply(
            feedId = 1, title = "Feed",
            existing = emptyList(),
            incoming = listOf(incoming("a"), incoming("b")),
            now = 5000L,
        )
        assertEquals(2, out.inserted)
        assertEquals(2, out.items.size)
    }

    @Test
    fun `a second sync of the same feed inserts nothing`() {
        val first = Sync.apply(
            feedId = 1, title = "Feed", existing = emptyList(),
            incoming = listOf(incoming("a"), incoming("b")), now = 5000L,
        )
        val second = Sync.apply(
            feedId = 1, title = "Feed", existing = first.items,
            incoming = listOf(incoming("a"), incoming("b")), now = 6000L,
        )
        assertEquals(0, second.inserted)
        assertEquals(2, second.items.size)
        // fetchedAt and id survive the second pass; only mutable fields refresh.
        assertEquals(first.items.map { it.id }.sorted(), second.items.map { it.id }.sorted())
    }

    @Test
    fun `only the genuinely new item counts as inserted`() {
        val first = Sync.apply(
            feedId = 1, title = "Feed", existing = emptyList(),
            incoming = listOf(incoming("a")), now = 5000L,
        )
        val second = Sync.apply(
            feedId = 1, title = "Feed", existing = first.items,
            incoming = listOf(incoming("a"), incoming("new")), now = 6000L,
        )
        assertEquals(1, second.inserted)
        assertEquals(2, second.items.size)
    }

    @Test
    fun `a failure contributes nothing to the new-item total`() {
        val outcomes = listOf<Sync.Outcome>(
            Sync.Outcome.Updated(1, "a", listOf(item("x")), inserted = 3),
            Sync.Outcome.Failed(2, "timeout"),
            Sync.Outcome.Updated(3, "c", emptyList(), inserted = 1),
        )
        assertEquals(4, Sync.totalInserted(outcomes))
        assertEquals(listOf(2L), Sync.failures(outcomes).map { it.feedId })
    }

    @Test
    fun `an empty feed list totals zero without throwing`() {
        assertEquals(0, Sync.totalInserted(emptyList()))
        assertTrue(Sync.failures(emptyList()).isEmpty())
    }

    @Test
    fun `the cap is applied on sync as on any other merge`() {
        val existing = (0 until 200).map { item("old$it", published = 1000L, id = it.toLong()) }
        val out = Sync.apply(
            feedId = 1, title = null, existing = existing,
            incoming = listOf(incoming("fresh", published = 9000L)),
            now = 10_000L,
        )
        assertEquals(1, out.inserted)
        assertEquals(Merge.MAX_ITEMS_PER_FEED, out.items.size)
        // The newest survives the trim, the oldest is dropped.
        assertEquals("fresh", out.items.first().guid)
    }

    @Test
    fun `a never-fetched feed is due`() {
        assertTrue(Sync.shouldRetry(lastFetched = null, now = 1000L, minIntervalMs = 60_000L))
    }

    @Test
    fun `a feed inside the interval is not due`() {
        assertFalse(Sync.shouldRetry(lastFetched = 1000L, now = 1500L, minIntervalMs = 60_000L))
    }

    @Test
    fun `a feed past the interval is due`() {
        assertTrue(Sync.shouldRetry(lastFetched = 1000L, now = 1000L + 60_000L, minIntervalMs = 60_000L))
    }

    @Test
    fun `a manual sync ignores the interval`() {
        assertTrue(Sync.shouldRetry(lastFetched = 1000L, now = 1001L, minIntervalMs = 0L))
    }

    @Test
    fun `a clock that jumped backwards does not park the feed`() {
        // lastFetched in the future means the clock moved, not that the feed is
        // fresh. Treating it as fresh would park it until the clock caught up.
        assertTrue(Sync.shouldRetry(lastFetched = 10_000L, now = 1000L, minIntervalMs = 60_000L))
    }
}
