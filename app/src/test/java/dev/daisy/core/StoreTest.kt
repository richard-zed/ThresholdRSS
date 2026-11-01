package dev.daisy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreTest {

    /** In-memory FileSpace, so the store is testable with no filesystem. */
    private class Mem : FileSpace {
        val files = LinkedHashMap<String, String>()
        var atomicWrites = 0

        override fun read(path: String): String? = files[path]
        override fun write(path: String, content: String) { files[path] = content }
        override fun writeAtomically(path: String, content: String) {
            atomicWrites++
            files[path] = content
        }
        override fun delete(path: String) { files.remove(path) }
        override fun list(dir: String): List<String> =
            files.keys.filter { it.startsWith("$dir/") }.map { it.substringAfterLast('/') }
    }

    private fun feed(id: Long, url: String = "https://e.com/$id", title: String = "F$id") =
        Feed(id = id, url = url, title = title)

    private fun item(feedId: Long, guid: String, published: Long? = null) = Item(
        id = 0, feedId = feedId, guid = guid, title = "T$guid",
        published = published, fetchedAt = 1000L,
    )

    @Test
    fun `an empty store reads as empty`() {
        val s = Store(Mem())
        assertTrue(s.loadFeeds().isEmpty())
        assertTrue(s.loadItems(1).isEmpty())
        assertTrue(s.loadAllItems().isEmpty())
    }

    @Test
    fun `feeds round trip`() {
        val s = Store(Mem())
        val feeds = listOf(
            Feed(1, "https://a.com/rss", "A", siteUrl = "https://a.com", faviconUrl = "https://a.com/f.ico", lastFetched = 42L),
            Feed(2, "https://b.com/rss", "B"),
        )
        s.saveFeeds(feeds)
        assertEquals(feeds, s.loadFeeds())
    }

    @Test
    fun `items round trip including nulls`() {
        val s = Store(Mem())
        val items = listOf(
            item(3, "g1", 500L),
            Item(1, 3, "g2", "Title", link = null, summary = null, imageUrl = null, published = null, fetchedAt = 7L),
        )
        s.saveItems(3, items)
        assertEquals(items, s.loadItems(3))
    }

    @Test
    fun `each feed gets its own file`() {
        val mem = Mem()
        val s = Store(mem)
        s.saveFeeds(listOf(feed(1), feed(2)))
        s.saveItems(1, listOf(item(1, "a")))
        s.saveItems(2, listOf(item(2, "b")))

        assertTrue(mem.files.containsKey("meta.json"))
        assertTrue(mem.files.containsKey("feeds/1.json"))
        assertTrue(mem.files.containsKey("feeds/2.json"))
    }

    @Test
    fun `saving one feed does not touch another`() {
        val mem = Mem()
        val s = Store(mem)
        s.saveItems(1, listOf(item(1, "a")))
        val before = mem.files["feeds/1.json"]
        s.saveItems(2, listOf(item(2, "b")))
        assertEquals(before, mem.files["feeds/1.json"])
    }

    @Test
    fun `feeds are written atomically`() {
        val mem = Mem()
        Store(mem).saveFeeds(listOf(feed(1)))
        assertTrue("meta.json must not be a plain write", mem.atomicWrites > 0)
    }

    @Test
    fun `a corrupt meta file reads as empty rather than throwing`() {
        val mem = Mem()
        mem.files["meta.json"] = "{not json"
        assertTrue(Store(mem).loadFeeds().isEmpty())
    }

    @Test
    fun `a future version reads as empty rather than half loading`() {
        val mem = Mem()
        mem.files["meta.json"] = """{"version":99,"feeds":[{"id":1,"url":"https://a.com/rss","title":"A"}]}"""
        assertTrue(Store(mem).loadFeeds().isEmpty())
    }

    @Test
    fun `a corrupt items file reads as empty`() {
        val mem = Mem()
        mem.files["feeds/1.json"] = "garbage"
        assertTrue(Store(mem).loadItems(1).isEmpty())
    }

    @Test
    fun `next id is one past the highest`() {
        val s = Store(Mem())
        assertEquals(1L, s.nextFeedId())
        s.saveFeeds(listOf(feed(1), feed(7), feed(3)))
        assertEquals(8L, s.nextFeedId())
    }

    @Test
    fun `next id does not reuse a gap`() {
        // 2 was deleted; the next subscribe must still be 4, so a stale
        // feeds/2.json cannot be adopted by a different feed.
        val s = Store(Mem())
        s.saveFeeds(listOf(feed(1), feed(3)))
        assertEquals(4L, s.nextFeedId())
    }

    @Test
    fun `deleting a feed removes its items file`() {
        val mem = Mem()
        val s = Store(mem)
        s.saveItems(1, listOf(item(1, "a")))
        s.deleteFeed(1)
        assertNull(mem.files["feeds/1.json"])
    }

    @Test
    fun `loadAllItems concatenates every feed`() {
        val s = Store(Mem())
        s.saveFeeds(listOf(feed(1), feed(2)))
        s.saveItems(1, listOf(item(1, "a"), item(1, "b")))
        s.saveItems(2, listOf(item(2, "c")))
        assertEquals(listOf("a", "b", "c"), s.loadAllItems().map { it.guid })
    }

    @Test
    fun `loadItems does not sort, because merge owns ordering`() {
        val s = Store(Mem())
        s.saveItems(1, listOf(item(1, "z", 100L), item(1, "a", 200L)))
        assertEquals(listOf("z", "a"), s.loadItems(1).map { it.guid })
    }

    @Test
    fun `a title containing quotes and newlines survives`() {
        val s = Store(Mem())
        s.saveItems(1, listOf(Item(0, 1, "g", "He said \"hi\"\nand left", fetchedAt = 1L)))
        assertEquals("He said \"hi\"\nand left", s.loadItems(1).single().title)
    }

    @Test
    fun `a title containing non-ascii survives`() {
        val s = Store(Mem())
        s.saveItems(1, listOf(Item(0, 1, "g", "日本語 — émoji 🎉", fetchedAt = 1L)))
        assertEquals("日本語 — émoji 🎉", s.loadItems(1).single().title)
    }

    @Test
    fun `an item missing its guid is skipped`() {
        val mem = Mem()
        mem.files["feeds/1.json"] = """{"version":1,"feedId":1,"items":[{"title":"no guid"}]}"""
        assertTrue(Store(mem).loadItems(1).isEmpty())
    }
}
