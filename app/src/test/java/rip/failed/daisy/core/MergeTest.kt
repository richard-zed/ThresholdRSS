package rip.failed.daisy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTest {

    private val feedA = 1L
    private val now = 1_700_000_000_000L

    private fun incoming(
        guid: String?,
        title: String = "t",
        link: String? = "https://example.com/$guid",
        published: Long? = now,
    ) = IncomingItem(
        guid = Identity.resolve(guid, link, title, published),
        title = title,
        link = link,
        published = published,
    )

    // --- case 1: idempotence -------------------------------------------------

    @Test
    fun `fetching the same feed twice inserts nothing the second time`() {
        val batch = listOf(incoming("a"), incoming("b"), incoming("c"))

        val first = Merge.merge(feedA, emptyList(), batch, now)
        assertEquals(3, first.inserted)

        val second = Merge.merge(feedA, first.items, batch, now + 1000)
        assertEquals(0, second.inserted)
        assertEquals(3, second.items.size)
        // ids are stable, so a DiffUtil will not see a fake change
        assertEquals(first.items.map { it.id }.toSet(), second.items.map { it.id }.toSet())
    }

    // --- case 2: guid churn --------------------------------------------------

    @Test
    fun `an item that gains a guid is treated as new, not as a duplicate`() {
        // Monday: no guid, so identity falls back to the link.
        val monday = listOf(incoming(null, title = "post", link = "https://x/1"))
        val first = Merge.merge(feedA, emptyList(), monday, now)
        assertEquals(1, first.inserted)

        // Tuesday: the feed grew a guid. Different key, so it inserts again.
        // This is the documented, accepted cost -- the feed changed identity.
        val tuesday = listOf(incoming("real-guid", title = "post", link = "https://x/1"))
        val second = Merge.merge(feedA, first.items, tuesday, now + 86_400_000)
        assertEquals(1, second.inserted)
        assertEquals(2, second.items.size)
    }

    @Test
    fun `an item that loses its guid gets a new row, not a silent merge`() {
        // The reverse direction. Stored key is the real guid; the fetch offers a
        // synthesised one, so it is not found and DOES insert. Assert reality
        // rather than the hoped-for behaviour.
        val withGuid = listOf(incoming("abc"))
        val first = Merge.merge(feedA, emptyList(), withGuid, now)

        val withoutGuid = listOf(incoming(null, link = "https://example.com/abc"))
        val second = Merge.merge(feedA, first.items, withoutGuid, now + 1000)
        assertEquals(1, second.inserted)
        assertEquals(2, second.items.size)
    }

    @Test
    fun `dropping guids from half the items never duplicates the guids that remain`() {
        val batch = (1..10).map { incoming("g$it", link = "https://x/$it") }
        val first = Merge.merge(feedA, emptyList(), batch, now)

        // Half keep their guid, half lose it. The kept half must not budge.
        val churned = (1..10).map { i ->
            if (i % 2 == 0) incoming("g$i", link = "https://x/$i")
            else incoming(null, link = "https://x/$i")
        }
        val second = Merge.merge(feedA, first.items, churned, now + 1000)

        val guids = second.items.map { it.guid }
        assertEquals("no duplicate guids", guids.size, guids.toSet().size)
        for (i in 2..10 step 2) {
            assertTrue("g$i survived", guids.contains("g$i"))
        }
    }

    // --- case 3: syndication -------------------------------------------------

    @Test
    fun `the same article in two feeds is two rows`() {
        val one = IncomingItem(
            guid = "same-guid", title = "Post", link = "https://example.com/post",
            published = now,
        )
        val a = Merge.merge(feedA, emptyList(), listOf(one), now)
        val b = Merge.merge(2L, emptyList(), listOf(one), now)

        assertEquals(1, a.items.size)
        assertEquals(1, b.items.size)
        // identity is scoped to the feed: same guid, different feed, both exist
        assertEquals("same-guid", a.items[0].guid)
        assertEquals("same-guid", b.items[0].guid)
        assertNotEquals(feedA, b.items[0].feedId)
    }

    // --- ordering traps ------------------------------------------------------

    @Test
    fun `identical timestamps keep a stable order`() {
        val batch = listOf(incoming("b"), incoming("a"), incoming("c"))
        val first = Merge.merge(feedA, emptyList(), batch, now)
        val again = Merge.merge(feedA, first.items, batch.reversed(), now + 1)
        assertEquals(
            first.items.map { it.guid },
            again.items.map { it.guid },
        )
    }

    @Test
    fun `the cap trims the oldest items and keeps the window`() {
        val batch = (1..10).map { incoming("g$it", published = now - it * 1000L) }
        val merged = Merge.merge(feedA, emptyList(), batch, now, cap = 5)

        assertEquals(5, merged.items.size)
        // g1 is the newest (smallest subtraction), so it must survive
        assertTrue(merged.items.any { it.guid == "g1" })
        assertTrue(merged.items.none { it.guid == "g10" })
    }

    @Test
    fun `an old item arriving late is trimmed, not the live one`() {
        val batch = (1..5).map { incoming("g$it", published = now - it * 1000L) }
        val first = Merge.merge(feedA, emptyList(), batch, now, cap = 5)

        // A revised feed drops in something from last year.
        val late = listOf(incoming("ancient", published = now - 365L * 86_400_000L))
        val second = Merge.merge(feedA, first.items, late, now, cap = 5)

        assertEquals(5, second.items.size)
        assertTrue("newest item survived", second.items.any { it.guid == "g1" })
        assertTrue("ancient was trimmed", second.items.none { it.guid == "ancient" })
    }

    // --- undated stamping ----------------------------------------------------

    @Test
    fun `an undated item is stamped with fetch time at insert`() {
        val merged = Merge.merge(feedA, emptyList(), listOf(incoming("u", published = null)), now)
        assertEquals(1, merged.inserted)
        assertEquals(now, merged.items[0].published)
    }

    @Test
    fun `a stamped item is not re-stamped on a later fetch`() {
        val first = Merge.merge(feedA, emptyList(), listOf(incoming("u", published = null)), now)

        // A poll an hour later, still no date from the feed.
        val hour = 3_600_000L
        val second = Merge.merge(feedA, first.items, listOf(incoming("u", published = null)), now + hour)

        assertEquals(0, second.inserted)
        // The stamp must not move -- otherwise a dateless item climbs to the top
        // of the list on every poll, forever.
        assertEquals(now, second.items[0].published)
    }

    @Test
    fun `a real date arriving later overrides the stamp`() {
        val first = Merge.merge(feedA, emptyList(), listOf(incoming("u", published = null)), now)
        assertEquals(now, first.items[0].published)

        // The feed backfills a real timestamp from last week.
        val real = now - 7 * 86_400_000L
        val second = Merge.merge(feedA, first.items, listOf(incoming("u", published = real)), now + 1000)

        assertEquals(real, second.items[0].published)
    }

    @Test
    fun `a stamped item sorts by its stamp, not to the bottom`() {
        val items = listOf(
            incoming("old", published = now - 10_000_000),
            incoming("undated", published = null),
        )
        val merged = Merge.merge(feedA, emptyList(), items, now)
        // The stamp is `now`, so it sorts to the TOP, above the older dated item.
        assertEquals(listOf("undated", "old"), merged.items.map { it.guid })
    }

    // --- identity ------------------------------------------------------------

    @Test
    fun `synthesised keys cannot collide with real guids`() {
        val realGuid = "https://example.com/foo"
        val fromLink = Identity.resolve(null, "https://example.com/foo", "t", now)
        assertNotEquals(realGuid, fromLink)
        assertTrue(fromLink.startsWith("hash:"))
    }

    @Test
    fun `stableHash is fixed width and deterministic`() {
        val a = Identity.stableHash("https://example.com/x")
        val b = Identity.stableHash("https://example.com/x")
        assertEquals(a, b)
        assertEquals(16, a.length)
        assertNotEquals(a, Identity.stableHash("https://example.com/y"))
    }
}
