package moe.ouom.neriplayer.core.player.queue.heartbeat

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 本地降级候选源测试（任务书 §5.1 建议补充项）。
 *
 * 关注两件事：
 * - provider 抛异常时必须降级成空池 / null，而不是把异常抛给播放链路；
 * - 候选池按层级构建（收藏=1、历史=2），且该缓存经 [HeartbeatCandidateCache] 生效。
 */
class LocalHeartbeatCandidateSourceTest {

    private val current = song(1, "当前歌曲")

    // ---- provider 异常降级 ----

    @Test
    fun `provider throwing on favorites degrades to empty result`() = runTest {
        val provider = object : HeartbeatCandidateProvider {
            override suspend fun favorites(): List<SongItem> = throw IllegalStateException("db down")
            override suspend fun recentHistory(limit: Int): List<SongItem> = listOf(song(2, "历史"))
        }
        val source = LocalHeartbeatCandidateSource(provider = provider)

        // 收藏读不出来，但历史仍可用：不得崩溃
        val result = source.selectNext(current, emptyList())

        assertEquals(2L, result?.id)
    }

    @Test
    fun `provider throwing on both sources returns null instead of crashing`() = runTest {
        val provider = object : HeartbeatCandidateProvider {
            override suspend fun favorites(): List<SongItem> = throw IllegalStateException("db down")
            override suspend fun recentHistory(limit: Int): List<SongItem> = throw IllegalStateException("db down")
        }
        val source = LocalHeartbeatCandidateSource(provider = provider)

        assertNull(source.selectNext(current, emptyList()))
    }

    @Test
    fun `empty provider yields null`() = runTest {
        val source = LocalHeartbeatCandidateSource(provider = EmptyHeartbeatCandidateProvider)

        assertNull(source.selectNext(current, emptyList()))
    }

    // ---- 候选池构建 ----

    @Test
    fun `favorites win over history because favorite tier is higher`() = runTest {
        val provider = StaticProvider(
            favorites = listOf(song(10, "收藏曲")),
            history = listOf(song(11, "历史曲")),
        )
        val source = LocalHeartbeatCandidateSource(provider = provider)

        val result = source.selectNext(current, emptyList())

        assertEquals("收藏层级必须压过历史层级", 10L, result?.id)
    }

    @Test
    fun `history candidates are used when there is no favorite`() = runTest {
        val provider = StaticProvider(favorites = emptyList(), history = listOf(song(11, "历史曲")))
        val source = LocalHeartbeatCandidateSource(provider = provider)

        assertEquals(11L, source.selectNext(current, emptyList())?.id)
    }

    @Test
    fun `earlier history ranks higher than later history`() = runTest {
        val provider = StaticProvider(
            favorites = emptyList(),
            history = listOf(song(21, "最近一条"), song(22, "稍早一条")),
        )
        val source = LocalHeartbeatCandidateSource(provider = provider)

        assertEquals("越近的历史权重越高", 21L, source.selectNext(current, emptyList())?.id)
    }

    @Test
    fun `current song and queued songs are excluded`() = runTest {
        val provider = StaticProvider(
            favorites = listOf(current, song(12, "已在队列")),
            history = emptyList(),
        )
        val source = LocalHeartbeatCandidateSource(provider = provider)

        val result = source.selectNext(current, listOf(song(12, "已在队列")))

        assertNull("只剩当前歌曲与队列内歌曲时应返回 null", result)
    }

    @Test
    fun `history entries that were just played are ranked below fresh favorites`() = runTest {
        val provider = StaticProvider(
            favorites = listOf(song(13, "收藏但刚播过")),
            history = listOf(song(14, "历史曲")),
        )
        val source = LocalHeartbeatCandidateSource(provider = provider)

        // 收藏候选带 recentlyPlayed=true（命中历史）会被打分器降权，
        // 但层级基础分差距（4000 vs 3000）远大于 0.35 的降权系数，
        // 因此收藏仍然压过历史——这正是任务书 §3.3 的层级优先语义。
        assertEquals(13L, source.selectNext(current, emptyList())?.id)
    }

    // ---- 缓存接线 ----

    @Test
    fun `provider is queried again after invalidate`() = runTest {
        // 注意：缓存把 cachedAt <= 0 视为过期（时钟回拨保护），基线时间必须非 0
        var now = 1_000_000L
        val cache = HeartbeatCandidateCache(ttlMs = 5 * 60 * 1000L, nowMs = { now })
        val provider = CountingProvider(favorites = listOf(song(10, "收藏曲")))
        val source = LocalHeartbeatCandidateSource(provider = provider, cache = cache)

        source.selectNext(current, emptyList())
        assertEquals(1, provider.favoritesCalls)

        source.selectNext(current, emptyList())
        assertEquals("TTL 内不得重复查库", 1, provider.favoritesCalls)

        source.invalidate()
        source.selectNext(current, emptyList())
        assertEquals(2, provider.favoritesCalls)
    }

    @Test
    fun `provider is queried again after ttl expires`() = runTest {
        var now = 1_000_000L
        val cache = HeartbeatCandidateCache(ttlMs = 5 * 60 * 1000L, nowMs = { now })
        val provider = CountingProvider(favorites = listOf(song(10, "收藏曲")))
        val source = LocalHeartbeatCandidateSource(provider = provider, cache = cache)

        source.selectNext(current, emptyList())
        assertEquals(1, provider.favoritesCalls)

        now += 5 * 60 * 1000L - 1
        source.selectNext(current, emptyList())
        assertEquals("TTL 内不得重复查库", 1, provider.favoritesCalls)

        now += 2
        source.selectNext(current, emptyList())
        assertEquals("TTL 过期后必须重新查库", 2, provider.favoritesCalls)
    }

    @Test
    fun `history limit follows the provider default`() = runTest {
        val provider = RecordingLimitProvider()
        val source = LocalHeartbeatCandidateSource(provider = provider)

        source.selectNext(current, emptyList())

        assertEquals(
            HeartbeatCandidateProvider.DEFAULT_HISTORY_LIMIT,
            provider.observedLimit,
        )
    }

    @Test
    fun `repeated selection is deterministic`() = runTest {
        val provider = StaticProvider(
            favorites = listOf(song(10, "收藏曲"), song(15, "另一首收藏")),
            history = listOf(song(11, "历史曲")),
        )
        val source = LocalHeartbeatCandidateSource(provider = provider)

        val first = source.selectNext(current, emptyList())
        val second = source.selectNext(current, emptyList())

        assertNotNull(first)
        assertEquals(first, second)
    }

    // ---- 测试替身 ----

    private class StaticProvider(
        private val favorites: List<SongItem>,
        private val history: List<SongItem>,
    ) : HeartbeatCandidateProvider {
        override suspend fun favorites(): List<SongItem> = favorites
        override suspend fun recentHistory(limit: Int): List<SongItem> = history
    }

    private class CountingProvider(
        private val favorites: List<SongItem>,
    ) : HeartbeatCandidateProvider {
        var favoritesCalls: Int = 0
            private set

        override suspend fun favorites(): List<SongItem> {
            favoritesCalls++
            return favorites
        }

        override suspend fun recentHistory(limit: Int): List<SongItem> = emptyList()
    }

    private class RecordingLimitProvider : HeartbeatCandidateProvider {
        var observedLimit: Int? = null
            private set

        override suspend fun favorites(): List<SongItem> = emptyList()

        override suspend fun recentHistory(limit: Int): List<SongItem> {
            observedLimit = limit
            return emptyList()
        }
    }

    private fun song(id: Long, name: String) = SongItem(
        id = id,
        name = name,
        artist = "歌手$id",
        album = "专辑$id",
        albumId = id,
        durationMs = 180_000L,
        coverUrl = null,
        sourceStableKey = "netease:$id",
    )
}
