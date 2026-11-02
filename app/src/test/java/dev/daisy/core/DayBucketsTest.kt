package dev.daisy.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DayBucketsTest {

    /** 2024-01-01T00:00:00Z, and a day of millis to step through. */
    private val DAY0 = 1_704_067_200_000L
    private val DAY = 86_400_000L

    /** Identity zone: midnight is the epoch multiple itself. */
    private val utc = TimeZoneInfo { it - (it % DAY) }

    private var seq = 0L
    private fun item(published: Long?, feedId: Long = 1L): Item {
        seq += 1
        return Item(
            id = seq,
            feedId = feedId,
            guid = "g$seq",
            title = "t$seq",
            published = published,
            fetchedAt = DAY0,
        )
    }

    @Test
    fun `one bucket per day`() {
        val items = listOf(
            item(DAY0 + DAY * 2),
            item(DAY0 + DAY * 2 + 1),
            item(DAY0 + DAY),
        )
        val out = DayBuckets.group(items, utc)
        assertEquals(listOf(DAY0 + DAY * 2, DAY0 + DAY), out.map { it.dayStart })
        assertEquals(listOf(2, 1), out.map { it.items.size })
    }

    /**
     * The regression. An undated item sitting between two dated items of the
     * same day used to split the day into two buckets sharing a dayStart,
     * which the list keys separators on -- a duplicate-key crash.
     *
     * Two feeds is what made it reachable: an undated item from one landing
     * mid-day between another feed's dated items.
     */
    @Test
    fun `an undated item does not split its day`() {
        val day = DAY0 + DAY
        val items = listOf(
            item(day, feedId = 1),
            item(null, feedId = 2),
            item(day + 60_000, feedId = 1),
        )
        val out = DayBuckets.group(items, utc)

        assertEquals(1, out.size)
        assertEquals(day, out[0].dayStart)
        assertEquals(3, out[0].items.size)
    }

    @Test
    fun `day starts are unique across a mixed list`() {
        val items = buildList {
            repeat(3) { d ->
                add(item(DAY0 + DAY * d))
                add(item(null))
                add(item(DAY0 + DAY * d + 1))
            }
        }
        val starts = DayBuckets.group(items, utc).map { it.dayStart }
        assertEquals(starts.size, starts.toSet().size)
    }

    @Test
    fun `leading undated items still form one trailing bucket`() {
        val items = listOf(item(null), item(null), item(DAY0))
        val out = DayBuckets.group(items, utc)
        assertEquals(2, out.size)
        assertEquals(2, out[0].items.size)
        assertEquals(DAY0, out[1].dayStart)
        // Both undated buckets would collide on this sentinel if a second
        // undated run could ever be opened. Only one can, per the loop.
        assertEquals(startsUnique(out), true)
    }

    private fun startsUnique(b: List<DayBucket>) =
        b.map { it.dayStart }.let { it.size == it.toSet().size }
}
