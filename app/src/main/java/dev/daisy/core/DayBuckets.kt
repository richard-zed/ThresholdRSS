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
 * Every item is expected to carry a published date: merge stamps undated items
 * with fetch time at insert, so the UNDATED sentinel this file used to have is
 * gone. The null branch below is defensive only -- if one arrives it trails the
 * list rather than being silently dropped, because losing items is worse than a
 * slightly odd group.
 */
object DayBuckets {

    fun group(items: List<Item>, zone: TimeZoneInfo): List<DayBucket> {
        if (items.isEmpty()) return emptyList()

        val out = ArrayList<DayBucket>()
        var currentStart: Long? = null
        var current = ArrayList<Item>()

        fun flush() {
            if (current.isNotEmpty()) {
                out.add(DayBucket(currentStart ?: Long.MIN_VALUE, current))
                current = ArrayList()
            }
        }

        // Undated items must not SPLIT a day. The old code flushed the open
        // day on a null and set the marker back to null; the next dated item
        // then saw start != null, flushed an empty bucket and REPOPENED the
        // same day -- two buckets with one dayStart. The list keys separators
        // on dayStart, so that is a duplicate-key crash in the measure pass,
        // which is what refreshing two feeds started hitting.
        //
        // A null therefore does not end a day. It joins whatever is open, and
        // only starts a trailing bucket when nothing is open (a leading run of
        // undated items, which the caller sorts last).
        for (it in items) {
            val p = it.published
            if (p == null) {
                // Should not happen post-stamp. Defensive: keep it, trailing.
                current.add(it)
                continue
            }
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
