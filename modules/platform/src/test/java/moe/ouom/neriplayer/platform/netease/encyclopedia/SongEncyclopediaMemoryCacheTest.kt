package moe.ouom.neriplayer.platform.netease.encyclopedia

import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaBasicInfo
import moe.ouom.neriplayer.data.model.encyclopedia.SongEncyclopedia
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SongEncyclopediaMemoryCache] 单测：TTL 过期、LRU 淘汰、覆盖、清空与未命中。
 *
 * 时间来源由 [SongEncyclopediaMemoryCache] 构造参数注入，因此这里用可变 `now` 精确控制时钟。
 */
class SongEncyclopediaMemoryCacheTest {

    @Test
    fun `a missing key returns null`() {
        val cache = SongEncyclopediaMemoryCache(nowMs = { 0L })

        assertNull(cache.get("nobody-put-this"))
    }

    @Test
    fun `put then get returns the stored data`() {
        val cache = SongEncyclopediaMemoryCache(nowMs = { 0L })
        val data = encyclopedia(songKey = "5|专辑|")

        cache.put(data)

        assertEquals(data, cache.get("5|专辑|"))
    }

    @Test
    fun `entries of different song keys stay isolated`() {
        val cache = SongEncyclopediaMemoryCache(nowMs = { 0L })
        val first = encyclopedia(songKey = "1|专辑|", name = "第一首")
        val second = encyclopedia(songKey = "2|专辑|", name = "第二首")

        cache.put(first)
        cache.put(second)

        assertEquals("第一首", cache.get("1|专辑|")?.basicInfo?.name)
        assertEquals("第二首", cache.get("2|专辑|")?.basicInfo?.name)
    }

    // ---------- TTL ----------

    @Test
    fun `an entry survives exactly up to the ttl and expires after it`() {
        var now = 0L
        val cache = SongEncyclopediaMemoryCache(nowMs = { now }, ttlMs = 1_000L)
        cache.put(encyclopedia(songKey = "key"))

        now = 1_000L
        assertNotNull("the ttl boundary is inclusive", cache.get("key"))

        now = 1_001L
        assertNull(cache.get("key"))
    }

    @Test
    fun `an expired entry is dropped rather than merely hidden`() {
        var now = 0L
        val cache = SongEncyclopediaMemoryCache(nowMs = { now }, ttlMs = 1_000L)
        cache.put(encyclopedia(songKey = "key"))

        now = 2_000L
        assertNull(cache.get("key"))

        // 时钟回拨也不会让已过期的条目复活，说明 get 真的把它移除了
        now = 500L
        assertNull(cache.get("key"))
    }

    @Test
    fun `a fresh put restarts the ttl window`() {
        var now = 0L
        val cache = SongEncyclopediaMemoryCache(nowMs = { now }, ttlMs = 1_000L)
        cache.put(encyclopedia(songKey = "key"))

        now = 1_001L
        assertNull(cache.get("key"))

        cache.put(encyclopedia(songKey = "key", name = "重新写入"))
        assertNotNull(cache.get("key"))
        assertEquals("重新写入", cache.get("key")?.basicInfo?.name)
    }

    // ---------- put 覆盖 ----------

    @Test
    fun `put overwrites the previous entry of the same key`() {
        val cache = SongEncyclopediaMemoryCache(nowMs = { 0L })
        cache.put(encyclopedia(songKey = "key", name = "旧"))

        val replacement = encyclopedia(songKey = "key", name = "新")
        cache.put(replacement)

        assertEquals(replacement, cache.get("key"))
        assertEquals("新", cache.get("key")?.basicInfo?.name)
    }

    // ---------- LRU ----------

    @Test
    fun `the cache evicts the least recently used entry beyond its limit`() {
        val cache = SongEncyclopediaMemoryCache(nowMs = { 0L }, maxEntries = 2)
        cache.put(encyclopedia(songKey = "A"))
        cache.put(encyclopedia(songKey = "B"))

        // 访问 A 让它变成最近使用，B 成为最久未使用
        assertNotNull(cache.get("A"))

        cache.put(encyclopedia(songKey = "C"))

        assertNotNull(cache.get("A"))
        assertNotNull(cache.get("C"))
        assertNull("B was the least recently used entry", cache.get("B"))
    }

    @Test
    fun `without a hit the eviction follows insertion order`() {
        val cache = SongEncyclopediaMemoryCache(nowMs = { 0L }, maxEntries = 2)
        cache.put(encyclopedia(songKey = "A"))
        cache.put(encyclopedia(songKey = "B"))
        cache.put(encyclopedia(songKey = "C"))

        // 没有任何访问时，最久未使用的是最早写入的 A
        assertNull(cache.get("A"))
        assertNotNull(cache.get("B"))
        assertNotNull(cache.get("C"))
    }

    @Test
    fun `eviction keeps the cache at its limit when inserting many entries`() {
        val cache = SongEncyclopediaMemoryCache(nowMs = { 0L }, maxEntries = 3)
        (1..10).forEach { cache.put(encyclopedia(songKey = "k$it")) }

        // 只剩最后写入的三个
        assertNotNull(cache.get("k10"))
        assertNotNull(cache.get("k9"))
        assertNotNull(cache.get("k8"))
        assertNull(cache.get("k7"))
        assertNull(cache.get("k1"))
    }

    // ---------- clear ----------

    @Test
    fun `clear drops every entry`() {
        val cache = SongEncyclopediaMemoryCache(nowMs = { 0L })
        cache.put(encyclopedia(songKey = "A"))
        cache.put(encyclopedia(songKey = "B"))

        cache.clear()

        assertNull(cache.get("A"))
        assertNull(cache.get("B"))
    }

    @Test
    fun `the cache is usable again after clear`() {
        val cache = SongEncyclopediaMemoryCache(nowMs = { 0L })
        cache.put(encyclopedia(songKey = "A"))
        cache.clear()

        cache.put(encyclopedia(songKey = "A", name = "清空后重新写入"))

        assertEquals("清空后重新写入", cache.get("A")?.basicInfo?.name)
    }

    // ---------- helpers ----------

    private fun encyclopedia(
        songKey: String,
        name: String = "歌名"
    ) = SongEncyclopedia(
        songKey = songKey,
        source = MusicPlatform.CLOUD_MUSIC,
        basicInfo = EncyclopediaBasicInfo(name = name, artists = listOf("歌手"), album = "专辑"),
        fetchedAtMs = 0L
    )
}
