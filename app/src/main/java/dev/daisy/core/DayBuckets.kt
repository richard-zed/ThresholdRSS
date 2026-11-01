package dev.daisy.core

/** One day's worth of a feed, for the separator labels. */
data class DayBucket(
    /** Local midnight that starts this day, epoch millis. */
    val dayStart: Long,
    val items: List<Item>,
)

/**
 * Groups an already-sorted item list into days for the date separators.
 *
 * Input MUST be sorted newest-first (Merge.capAndSort output). This does not
 * sort -- doing so would silently paper over a caller that passed the wrong
 * order, and the two would then disagree about null handling.
 *
 * Undated items are collected into a single trailing bucket under [UNDATED],
 * because a null published date is extremely common (Reddit especially) and
 * they have to land somewhere visible rather than being dropped.
 */
object DayBuckets {

    /** Sentinel dayStart for the undated bucket. Deliberately not a real time. */
    const val UNDATED = Long.MIN_VALUE

    fun group(items: List<Item>, zone: TimeZoneInfo): List<DayBucket> {
        if (items.isEmpty()) return emptyList()

        val out = ArrayList<DayBucket>()
        var currentStart = Long.MIN_VALUE
        var current = ArrayList<Item>()

        fun flush() {
            if (current.isNotEmpty()) {
                out.add(DayBucket(currentStart, current))
                current = ArrayList()
            }
        }

        for (it in items) {
            val p = it.published
            if (p == null) {
                // Undated always trails. Sorted input guarantees they are last,
                // but be explicit so a caller passing sorted-but-wrong order
                // still gets something coherent.
                if (currentStart != UNDATED) { flush(); currentStart = UNDATED }
                current.add(it)
                continue
            }
            if (currentStart == UNDATED) flush()
            val start = zone.startOfDay(p)
            if (start != currentStart) {
                flush()
                currentStart = start
            }
            current.add(it)
        }
        flush()
        return out
    }
}

/**
 * Calendar work separated from java.util.Calendar so it can be swapped and
 * tested. One implementation needed today: device local time.
 */
fun interface TimeZoneInfo {
    /** Epoch millis of local midnight for the day containing [epochMillis]. */
    fun startOfDay(epochMillis: Long): Long
}
