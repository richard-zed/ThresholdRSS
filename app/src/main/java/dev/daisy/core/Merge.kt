package dev.daisy.core

/**
 * Result of merging one fetch into an existing item list.
 *
 * [items] is the complete new list for that feed, already capped, already
 * sorted. [inserted] is the count of rows that appeared for the first time --
 * that is the number the UI needs ("12 new"), and get it from here rather than
 * diffing afterwards.
 */
data class MergeResult(
    val items: List<Item>,
    val inserted: Int,
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * The merge rule. This is the whole correctness story of the app.
 *
 * The three properties it must hold, in order of how often they get broken:
 *
 *  1. Fetching the same feed twice inserts nothing the second time.
 *  2. A feed that drops guids from its items does not produce duplicates, and
 *     does not re-insert items it already has under a synthesised key.
 *  3. The same article syndicated by two different feeds is two rows, because
 *     identity is scoped to the feed. Do not dedupe on link.
 *
 * Property 2 is the one that decides whether guid resolution is done *before*
 * or *after* the lookup. It must be before: an item that has a real guid on
 * Monday and no guid on Tuesday resolves to a different key, and will be
 * inserted again. That is unavoidable -- the feed genuinely changed its
 * identity -- but it is bounded, and the alternative (trusting the link) is
 * worse because sites legitimately repost the same link.
 */
object Merge {

    const val MAX_ITEMS_PER_FEED = 200

    /**
     * @param existing  every item currently stored for this feed, any order
     * @param incoming  items from the fetch, already identity-resolved
     * @param now       epoch millis, injected so tests are deterministic
     */
    fun merge(
        feedId: Long,
        existing: List<Item>,
        incoming: List<IncomingItem>,
        now: Long,
        cap: Int = MAX_ITEMS_PER_FEED,
    ): MergeResult {
        val byGuid = HashMap<String, Item>(existing.size * 2)
        for (it in existing) byGuid[it.guid] = it

        var nextId = (existing.maxOfOrNull { it.id } ?: 0L) + 1L
        var inserted = 0

        for (inc in incoming) {
            val prev = byGuid[inc.guid]
            if (prev == null) {
                byGuid[inc.guid] = Item(
                    id = nextId++,
                    feedId = feedId,
                    guid = inc.guid,
                    title = inc.title,
                    link = inc.link,
                    summary = inc.summary,
                    imageUrl = inc.imageUrl,
                    // Undated items are stamped with fetch time AT INSERT ONLY.
                    // A feed that gives no date has no better answer than "we
                    // first saw it now". See the update branch: existing rows
                    // are never re-stamped, or every poll would drag a dateless
                    // item back to the top of the list.
                    published = inc.published ?: now,
                    fetchedAt = now,
                )
                inserted++
            } else {
                // Known item. Refresh the mutable fields but KEEP the id and the
                // original fetchedAt -- fetchedAt answers "when did we first see
                // this", which is the question you actually ask when debugging.
                //
                // published is deliberately not refreshed downward: a feed that
                // mangles an old item's date should not shuffle it out of the
                // window.
                byGuid[inc.guid] = prev.copy(
                    title = inc.title,
                    link = inc.link ?: prev.link,
                    summary = inc.summary ?: prev.summary,
                    imageUrl = inc.imageUrl ?: prev.imageUrl,
                    published = inc.published ?: prev.published,
                )
            }
        }

        val all = ArrayList<Item>(byGuid.values)
        return MergeResult(items = capAndSort(all, cap), inserted = inserted)
    }

    /**
     * Trim to [cap] by PUBLISHED DATE, then sort newest-first.
     *
     * Trimming by published rather than insertion order matters: a feed that
     * revises its list will have newly-inserted items that are older than the
     * window, and trimming by insertion order would drop a live item from the
     * top of the feed instead.
     *
     * Nulls should no longer reach here -- merge stamps them at insert -- but
     * the comparator stays total anyway. A null treated as 0 projects items to
     * 1970 and buries them; treated as now, every broken item floats to the top
     * forever. Staying explicit costs one branch and removes the question.
     */
    fun capAndSort(items: List<Item>, cap: Int = MAX_ITEMS_PER_FEED): List<Item> {
        val sorted = items.sortedWith(ITEM_ORDER)
        return if (sorted.size <= cap) sorted else sorted.subList(0, cap)
    }

    /**
     * Newest first, nulls last, ties broken by guid.
     *
     * The guid tiebreak is not decoration: bulk-published feeds produce runs of
     * identical timestamps, and without a stable tiebreak the list reorders
     * itself between launches.
     */
    val ITEM_ORDER: Comparator<Item> = Comparator { a, b ->
        val pa = a.published
        val pb = b.published
        when {
            pa != null && pb != null -> {
                val c = pb.compareTo(pa)
                if (c != 0) c else a.guid.compareTo(b.guid)
            }
            pa != null -> -1          // dated before undated
            pb != null -> 1
            else -> a.guid.compareTo(b.guid)
        }
    }
}
