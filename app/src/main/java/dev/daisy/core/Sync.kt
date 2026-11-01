package dev.daisy.core

/**
 * One feed's refresh, with the network and the disk abstracted out.
 *
 * Split from the driver for the same reason [DiscoverPlan] is: the interesting
 * failures are ordering and counting ones, and they are only observable through
 * a fake fetcher. Everything here is pure -- it takes what is stored, what came
 * back, and the clock, and returns what should now be stored.
 *
 * The fetch itself stays in the driver. This decides what to do with the answer.
 */
object Sync {

    /** What a single feed's refresh produced. */
    sealed interface Outcome {
        /** Parsed, merged, and worth saving. [inserted] is the "12 new" number. */
        data class Updated(
            val feedId: Long,
            val title: String?,
            val items: List<Item>,
            val inserted: Int,
        ) : Outcome

        /**
         * The fetch or the parse failed. [items] are the rows already stored,
         * untouched -- a feed that 500s must not lose its history, which is
         * why this carries them rather than being a bare error.
         */
        data class Failed(
            val feedId: Long,
            val reason: String,
        ) : Outcome
    }

    /**
     * Merge one parsed feed into what is stored for it.
     *
     * @param existing  rows currently stored for this feed, any order
     * @param incoming  items from the parse, identity already resolved
     * @param now       epoch millis, injected so tests are deterministic
     */
    fun apply(
        feedId: Long,
        title: String?,
        existing: List<Item>,
        incoming: List<IncomingItem>,
        now: Long,
        cap: Int = Merge.MAX_ITEMS_PER_FEED,
    ): Outcome.Updated {
        val merged = Merge.merge(feedId, existing, incoming, now, cap)
        return Outcome.Updated(
            feedId = feedId,
            title = title,
            items = merged.items,
            inserted = merged.inserted,
        )
    }

    /**
     * Total new rows across a whole sync, for the one-line summary.
     *
     * Failures contribute zero: a feed that did not load did not produce new
     * items, and counting it as anything else makes the summary lie.
     */
    fun totalInserted(outcomes: List<Outcome>): Int =
        outcomes.sumOf { if (it is Outcome.Updated) it.inserted else 0 }

    /** Feeds that could not be refreshed, in the order they were tried. */
    fun failures(outcomes: List<Outcome>): List<Outcome.Failed> =
        outcomes.filterIsInstance<Outcome.Failed>()

    /**
     * `lastFetched` for the feed after a sync attempt, success or not.
     *
     * Advanced on failure too, deliberately, but see [shouldRetry]: this is a
     * record of "when we last tried", not "when we last succeeded". Recording
     * only successes makes a feed that has been dead for a month look
     * never-checked, and hides exactly the feeds worth dropping.
     */
    fun stamp(now: Long): Long = now

    /**
     * Whether a feed is due for a refresh.
     *
     * @param lastFetched epoch millis, or null if never fetched
     * @param minIntervalMs floor between attempts; a manual sync passes 0
     */
    fun shouldRetry(lastFetched: Long?, now: Long, minIntervalMs: Long): Boolean {
        if (lastFetched == null) return true
        if (minIntervalMs <= 0) return true
        // Clock skew guard: a system clock that jumped backwards would otherwise
        // park every feed until the wall clock caught up again.
        if (lastFetched > now) return true
        return now - lastFetched >= minIntervalMs
    }
}
