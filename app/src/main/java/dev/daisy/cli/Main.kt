package dev.daisy.cli

import dev.daisy.core.DiscoverState
import dev.daisy.core.Discoverer
import dev.daisy.core.FeedParser
import dev.daisy.core.FetchResult
import dev.daisy.core.Feed
import dev.daisy.core.FileSpace
import dev.daisy.core.HttpFetcher
import dev.daisy.core.IncomingItem
import dev.daisy.core.Item
import dev.daisy.core.Opml
import dev.daisy.core.Store
import dev.daisy.core.Sync
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * A command line front end for the core layer.
 *
 * This exists because everything before it was verified only against fixtures.
 * Being able to point the real pipeline at real sites is how discovery and
 * parsing get checked against the internet rather than against my assumptions
 * about it -- the youtube page scrape in particular.
 *
 * Commands:
 *   discover <url>   run discovery, print what was tried and what won
 *   fetch <url>      fetch and parse a feed url directly
 *   add <url>        discover, then store the feed
 *   list             every stored item, newest first
 *   feeds            the stored feed list
 *   export <file>    write OPML
 *   import <file>    read OPML and print what it found
 *
 * The store is a directory under --dir (default ./daisy-data), so runs are
 * isolated and a bad experiment is `rm -rf`.
 */
object Main {

    private class DiskSpace(private val root: File) : FileSpace {
        override fun read(path: String): String? {
            val f = File(root, path)
            return if (f.isFile) f.readText() else null
        }

        override fun write(path: String, content: String) {
            val f = File(root, path)
            f.parentFile?.mkdirs()
            f.writeText(content)
        }

        override fun writeAtomically(path: String, content: String) {
            val f = File(root, path)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(content)
            // A rename on the same filesystem is atomic; a process killed
            // mid-write then leaves either the old file or the new one.
            if (!tmp.renameTo(f)) {
                f.writeText(content)
                tmp.delete()
            }
        }

        override fun delete(path: String) {
            File(root, path).delete()
        }

        override fun list(dir: String): List<String> =
            File(root, dir).listFiles()?.map { it.name }?.sorted() ?: emptyList()
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        var dir = "daisy-data"
        val rest = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            if (args[i] == "--dir" && i + 1 < args.size) {
                dir = args[i + 1]
                i += 2
            } else {
                rest.add(args[i]); i++
            }
        }

        if (rest.isEmpty()) {
            usage()
            return@runBlocking
        }

        val store = Store(DiskSpace(File(dir)))
        val fetcher = HttpFetcher()
        val cmd = rest[0]
        val arg = rest.getOrNull(1)

        when (cmd) {
            "discover" -> {
                if (arg == null) { usage(); return@runBlocking }
                val d = Discoverer(fetcher)
                val state = d.discover(
                    input = arg,
                    onState = { s ->
                        if (s is DiscoverState.Looking) println("  ... ${s.current ?: ""}")
                    },
                    now = { System.currentTimeMillis() },
                )
                when (state) {
                    is DiscoverState.Found -> {
                        println("FOUND  ${state.feedUrl}")
                        println("title  ${state.title ?: "(none)"}")
                    }
                    is DiscoverState.NotFound -> {
                        println("NOT FOUND")
                        for (a in state.tried) {
                            println("  ${a.outcome}  ${a.url}  ${a.detail ?: ""}")
                        }
                    }
                    else -> println(state)
                }
            }

            "fetch" -> {
                if (arg == null) { usage(); return@runBlocking }
                when (val r = fetcher.get(arg)) {
                    is FetchResult.Ok -> {
                        when (val p = FeedParser.parse(r.body, r.finalUrl)) {
                            is FeedParser.Result.Ok -> {
                                println("${p.items.size} items, title=${p.title ?: "(none)"}")
                                for (it in p.items.take(10)) {
                                    println("  ${it.published ?: "-"}  ${it.title}")
                                }
                            }
                            is FeedParser.Result.Error -> println("parse: ${p.reason}")
                        }
                    }
                    is FetchResult.Failed -> println("fetch: ${r.detail()}")
                }
            }

            "add" -> {
                if (arg == null) { usage(); return@runBlocking }
                val d = Discoverer(fetcher)
                val state = d.discover(
                    input = arg,
                    onState = { },
                    now = { System.currentTimeMillis() },
                )
                if (state !is DiscoverState.Found) {
                    println("not found: $state")
                    return@runBlocking
                }
                val r = fetcher.get(state.feedUrl)
                if (r !is FetchResult.Ok) { println("fetch failed"); return@runBlocking }
                val parsed = FeedParser.parse(r.body, r.finalUrl)
                if (parsed is FeedParser.Result.Error) {
                    println("parse failed: ${parsed.reason}")
                    return@runBlocking
                }
                val ok = parsed as FeedParser.Result.Ok

                val feeds = store.loadFeeds()
                if (feeds.any { it.url == state.feedUrl }) {
                    println("already subscribed: ${state.feedUrl}")
                    return@runBlocking
                }
                val id = store.nextFeedId()
                val feed = Feed(
                    id = id,
                    url = state.feedUrl,
                    title = state.title ?: ok.title ?: state.feedUrl,
                    siteUrl = null,
                    lastFetched = System.currentTimeMillis(),
                )
                store.saveFeeds(feeds + feed)
                val now = System.currentTimeMillis()
                store.saveItems(
                    id,
                    ok.items.mapIndexed { idx, inc ->
                        Item(
                            id = idx.toLong(),
                            feedId = id,
                            guid = inc.guid,
                            title = inc.title,
                            link = inc.link,
                            summary = inc.summary,
                            imageUrl = inc.imageUrl,
                            published = inc.published,
                            fetchedAt = now,
                        )
                    },
                )
                println("added ${feed.title} ($id) with ${ok.items.size} items")
            }

            "sync" -> {
                // arg is an optional feed id: `sync` refreshes everything,
                // `sync 3` refreshes one feed. Useful when one feed is broken
                // and you do not want to wait on the rest.
                val only = arg?.toLongOrNull()
                val feeds = store.loadFeeds()
                if (feeds.isEmpty()) {
                    println("(no feeds -- add one first)")
                    return@runBlocking
                }
                val targets = if (only == null) feeds else feeds.filter { it.id == only }
                if (targets.isEmpty()) {
                    println("no feed with id $only")
                    return@runBlocking
                }

                val now = System.currentTimeMillis()
                val outcomes = ArrayList<Sync.Outcome>(targets.size)

                for (feed in targets) {
                    println("  ... ${feed.title}")
                    when (val r = fetcher.get(feed.url)) {
                        is FetchResult.Failed ->
                            outcomes.add(Sync.Outcome.Failed(feed.id, r.detail()))

                        is FetchResult.Ok -> {
                            val parsed = FeedParser.parse(r.body, r.finalUrl)
                            if (parsed is FeedParser.Result.Error) {
                                outcomes.add(Sync.Outcome.Failed(feed.id, "parse: ${parsed.reason}"))
                            } else {
                                val ok = parsed as FeedParser.Result.Ok
                                val incoming = ok.items.map { inc ->
                                    IncomingItem(
                                        guid = inc.guid,
                                        title = inc.title,
                                        link = inc.link,
                                        summary = inc.summary,
                                        imageUrl = inc.imageUrl,
                                        published = inc.published,
                                    )
                                }
                                val out = Sync.apply(
                                    feedId = feed.id,
                                    title = ok.title,
                                    existing = store.loadItems(feed.id),
                                    incoming = incoming,
                                    now = now,
                                )
                                store.saveItems(feed.id, out.items)
                                outcomes.add(out)
                            }
                        }
                    }
                }

                // lastFetched advances for every ATTEMPTED feed, including
                // failed ones: it records when we last tried, not when we last
                // succeeded. See Sync.stamp.
                val attempted = outcomes.mapNotNull {
                    when (it) {
                        is Sync.Outcome.Updated -> it.feedId
                        is Sync.Outcome.Failed -> it.feedId
                    }
                }.toSet()
                store.saveFeeds(
                    feeds.map { if (it.id in attempted) it.copy(lastFetched = Sync.stamp(now)) else it },
                )

                val inserted = Sync.totalInserted(outcomes)
                println("$inserted new across ${targets.size} feed${if (targets.size == 1) "" else "s"}")
                for (f in Sync.failures(outcomes)) {
                    val name = feeds.firstOrNull { it.id == f.feedId }?.title ?: "feed ${f.feedId}"
                    println("  FAILED  $name  ${f.reason}")
                }
            }

            "feeds" -> {
                val feeds = store.loadFeeds()
                if (feeds.isEmpty()) println("(no feeds)")
                for (f in feeds) println("${f.id}  ${f.title}  ${f.url}")
            }

            "list" -> {
                val all = store.loadAllItems()
                    .sortedWith(
                        compareByDescending<Item> { it.published ?: Long.MIN_VALUE }
                            .thenBy { it.guid },
                    )
                if (all.isEmpty()) println("(nothing stored)")
                for (it in all.take(40)) println("  ${it.published ?: "-"}  ${it.title}")
                println("${all.size} total")
            }

            "export" -> {
                val out = arg ?: "daisy.opml"
                val feeds = store.loadFeeds()
                File(out).writeText(Opml.export(feeds))
                println("wrote ${feeds.size} feeds to $out")
            }

            "import" -> {
                if (arg == null) { usage(); return@runBlocking }
                val entries = Opml.parse(File(arg).readText())
                println("${entries.size} feeds in $arg")
                for (e in entries.take(20)) println("  ${e.url}  ${e.title ?: ""}")
            }

            else -> usage()
        }
    }

    private fun usage() {
        println(
            """
            usage: daisy [--dir DIR] <command> [arg]

              discover <url>   find the feed for any url
              fetch <url>      fetch and parse a feed url
              add <url>        discover and store a feed
              sync [id]        refresh stored feeds (all, or one by id)
              feeds            the stored feed list
              list             stored items, newest first
              export [file]    write OPML (default daisy.opml)
              import <file>    read OPML and print its feeds

            The store lives in ./daisy-data unless --dir says otherwise.
            """.trimIndent(),
        )
    }
}
