package dev.daisy.core

/**
 * Walks the candidate queue and reports what it finds.
 *
 * Split in two on purpose:
 *
 *  - [DiscoverPlan] is pure. It owns the queue, the ordering rules and the
 *    "what next" decision, with no network, no clock and no coroutines. That is
 *    where the subtle bugs live (promotion, dedupe, budget), so it is testable
 *    directly.
 *  - [Discoverer] is the suspend driver. It only does the three things a pure
 *    function cannot: fetch, check the clock, and emit state.
 *
 * Why not one suspend function with the queue inline: the interesting failures
 * are ordering failures, and an ordering failure inside a suspend function is
 * only observable through a fake Fetcher and a virtual clock. Keeping the queue
 * separate means most of it needs neither.
 */
class DiscoverPlan(
    candidates: List<Candidate>,
    private val budgetMs: Long,
) {
    /**
     * Remaining work, in the order it will be tried.
     *
     * Not a [java.util.ArrayDeque] because promotion inserts at the FRONT and
     * the list is tiny (a dozen entries); readability beats the constant.
     */
    private val queue = ArrayDeque<Candidate>().apply { addAll(candidates) }

    /** Everything tried so far, for the failure report. */
    private val tried = ArrayList<Attempt>()

    /** URLs already attempted or queued. Promotion must not re-add either. */
    private val seen = HashSet<String>().apply { candidates.forEach { add(it.url) } }

    var elapsedMs: Long = 0
        private set

    val attempts: List<Attempt> get() = tried
    val remaining: Int get() = queue.size
    val exhausted: Boolean get() = queue.isEmpty()

    /**
     * The next candidate, or null if the walk is over.
     *
     * Null means one of two things and the caller does not need to know which:
     * the queue emptied, or the budget ran out. Both produce a [DiscoverState.NotFound]
     * built from [attempts], and the difference is visible there.
     */
    fun next(): Candidate? = if (queue.isEmpty()) null else queue.removeFirst()

    /** True when there is enough budget left to be worth starting another request. */
    fun canAfford(): Boolean = elapsedMs < budgetMs

    /** Cost of the request that just finished, for budget accounting. */
    fun charge(ms: Long, attempt: Attempt) {
        elapsedMs += ms
        tried.add(attempt)
    }

    /**
     * Puts feed URLs the site advertised ahead of everything speculative.
     *
     * This is the one ordering change the walk makes mid-flight. A link the
     * page itself declared is far likelier to be the feed than `/feed.xml`
     * guessed by convention, and it costs the same one request.
     *
     * Dedupe is against BOTH the queue and the tried list, so a page that
     * advertises the URL we just fetched does not send us round again.
     */
    fun promote(urls: List<String>) {
        // Reversed: we push to the front one at a time, so pushing in reverse
        // leaves them in their declared order.
        for (url in urls.asReversed()) {
            if (seen.add(url)) {
                queue.addFirst(Candidate(url, Candidate.Kind.ADVERTISED))
            }
        }
    }

    /** Skips an already-answered url without charging a request. */
    fun skip(url: String, reason: String) {
        tried.add(Attempt(url, Attempt.Outcome.SKIPPED, reason))
    }
}

/**
 * Runs a discovery: fetch, sniff, and either report a feed or report why not.
 *
 * Sequential, not parallel. Candidates are ordered by cost and confidence, and
 * the common case answers on the first or second one -- firing all eleven at
 * once would cost a site eleven requests to answer a question usually settled
 * by one, and would hit sites harder than a person browsing would.
 */
class Discoverer(
    private val fetcher: Fetcher,
    private val budgetMs: Long = DEFAULT_BUDGET_MS,
) {

    /**
     * @param onState called for every transition, including the per-candidate
     *   `Looking` updates. The caller decides how much of that to show; the
     *   state carries the URL so one spinner can name what it is fetching.
     * @param isCancelled polled between requests and after each fetch. A newer
     *   discovery must be able to stop an older one from overwriting its result.
     */
    suspend fun discover(
        input: String,
        remembered: String? = null,
        onState: (DiscoverState) -> Unit,
        isCancelled: () -> Boolean = { false },
        now: () -> Long,
    ): DiscoverState {
        val start = now()

        fun elapsed() = now() - start

        // A remembered winner is tried first, ahead of the generated list. It
        // is the whole payoff of remembering: a discovery that needed seven
        // requests last time becomes one.
        val generated = UrlCandidates.forInput(input)
        val candidates = buildList {
            if (remembered != null) add(Candidate(remembered, Candidate.Kind.REMEMBERED))
            if (!input.trim().isEmpty()) {
                // The as-given attempt is cheap and covers feeds no rule predicts,
                // but on a repeat visit the remembered url already proved itself.
                addAll(generated)
            }
        }

        if (candidates.isEmpty()) {
            return finish(DiscoverState.NotFound(emptyList()), onState)
        }

        val plan = DiscoverPlan(candidates, budgetMs)

        while (true) {
            if (isCancelled()) return DiscoverState.Idle

            if (!plan.canAfford()) {
                // Out of budget, not out of ideas. Every unattempted candidate is
                // reported as skipped so the failure list does not imply we tried
                // things we never did.
                while (!plan.exhausted) {
                    plan.next()?.let { plan.skip(it.url, "out of time") }
                }
                break
            }
            val candidate = plan.next() ?: break

            onState(DiscoverState.Looking(candidate.url))

            val before = now()
            val result = fetcher.get(candidate.url)
            plan.charge(now() - before, result.toAttempt(candidate.url))

            if (isCancelled()) return DiscoverState.Idle

            when (result) {
                is FetchResult.Failed -> continue

                is FetchResult.Ok -> {
                    // Two different questions that both look like "did this work":
                    // `outcome()` says the request succeeded, `looksLikeFeed` says
                    // the body IS a feed. Only the second one ends the walk.
                    if (FeedSniffer.looksLikeFeed(result.body)) {
                        return finish(
                            DiscoverState.Found(result.finalUrl, feedTitle(result.body)),
                            onState,
                        )
                    }

                    // Not a feed. If it is HTML it may still name the feed.
                    // Only the body is scanned: FetchResult.Ok does not carry
                    // response headers, so the Link: header path is unreachable
                    // until it does. Better absent than a stub that quietly
                    // contributes nothing.
                    val advertised = LinkDiscovery.rssLinksFrom(result.body, result.finalUrl)
                    if (advertised.isNotEmpty()) plan.promote(advertised)
                }
            }
        }

        return finish(DiscoverState.NotFound(plan.attempts), onState)
    }

    private fun finish(state: DiscoverState, onState: (DiscoverState) -> Unit): DiscoverState {
        onState(state)
        return state
    }

    /** `<title>` from the feed's own head, for the feed list. Null if absent. */
    private fun feedTitle(body: String): String? {
        val head = body.take(4096)
        val m = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(head) ?: return null
        val raw = m.groupValues[1]
        return raw.trim()
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .takeIf { it.isNotEmpty() }
    }

    companion object {
        /**
         * Total wall clock for a whole discovery, not per request.
         *
         * Eleven candidates at a 12s HTTP timeout each could run for minutes.
         * Twenty seconds is long enough for the realistic case (one or two
         * requests) and short enough that a chain of dead candidates ends in a
         * visible answer rather than an apparently-frozen spinner.
         */
        const val DEFAULT_BUDGET_MS: Long = 20_000L
    }
}

/** Maps a fetch outcome onto the attempt record, including the body's verdict. */
private fun FetchResult.toAttempt(url: String): Attempt = when (this) {
    is FetchResult.Failed -> Attempt(url, outcome(), detail())
    is FetchResult.Ok ->
        if (FeedSniffer.looksLikeFeed(body)) {
            Attempt(url, Attempt.Outcome.FEED, "HTTP $status")
        } else {
            Attempt(url, Attempt.Outcome.NOT_A_FEED, "HTTP $status")
        }
}
