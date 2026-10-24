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
    fun `bilibili and youtube songs are filtered out of every tier`() = runTest {
        val provider = StaticProvider(
            favorites = listOf(
                song(30, "B站曲").copy(channelId = "bilibili"),
                song(31, "YouTube曲").copy(channelId = "youtubeMusic"),
                song(32, "历史标记的B站曲").copy(album = "Bilibili|123"),
            ),
            history = listOf(song(33, "正常曲")),
        )
        val source = LocalHeartbeatCandidateSource(provider = provider)

        // 非网易云音源一旦被选中就会让推进卡死，所以必须在这里就被筛掉
        val result = source.selectNext(current, emptyList())

        assertEquals(33L, result?.id)
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

    // ---- 层级 3/4（同歌手/同专辑、随机兜底）----

    @Test
    fun `same artist sample is picked when favorites and history are exhausted`() = runTest {
        // 当前歌是歌手1；收藏/历史都已在队列里被排除，只剩同歌手的库内抽样可用
        val provider = SampleProvider(
            favorites = listOf(current),
            history = emptyList(),
            samples = listOf(
                song(30, "同歌手曲").copy(artist = "歌手1"),
                song(31, "别人曲").copy(artist = "歌手99"),
            ),
        )
        val source = LocalHeartbeatCandidateSource(provider = provider)

        assertEquals("层级 3 必须优先于层级 4", 30L, source.selectNext(current, emptyList())?.id)
    }

    @Test
    fun `random tier is used when nothing shares artist or album`() = runTest {
        val provider = SampleProvider(
            favorites = listOf(current),
            history = emptyList(),
            samples = listOf(song(31, "无关曲").copy(artist = "歌手99", album = "别的专辑", albumId = 999)),
        )
        val source = LocalHeartbeatCandidateSource(provider = provider)

        assertEquals(31L, source.selectNext(current, emptyList())?.id)
    }

    @Test
    fun `samples already present in higher tiers are not duplicated`() = runTest {
        val favorite = song(40, "收藏曲")
        val provider = SampleProvider(
            favorites = listOf(favorite),
            history = emptyList(),
            samples = listOf(favorite, song(41, "同歌手抽样").copy(artist = "歌手1")),
        )
        val source = LocalHeartbeatCandidateSource(provider = provider)

        // 收藏层级更高，必须赢；抽样里重复出现的同一首歌不得造成两次候选
        assertEquals(40L, source.selectNext(current, emptyList())?.id)
    }

    @Test
    fun `library samples are not requested when provider default implementation is used`() = runTest {
        // 默认实现返回空列表 → 层级 3/4 自然为空，不得崩溃
        val provider = StaticProvider(favorites = emptyList(), history = emptyList())
        val source = LocalHeartbeatCandidateSource(provider = provider)

        assertNull(source.selectNext(current, emptyList()))
    }

    // ---- 失败曲目排除（任务书 §八）----

    @Test
    fun `failed song is excluded from later selections`() = runTest {
        val failed = song(50, "会失败的")
        val healthy = song(51, "健康的")
        val provider = StaticProvider(
            favorites = listOf(failed, healthy),
            history = emptyList(),
        )
        val source = LocalHeartbeatCandidateSource(provider = provider)

        assertEquals(50L, source.selectNext(current, emptyList())?.id)

        source.markFailed(failed)

        assertEquals("失败曲目必须被排除", 51L, source.selectNext(current, emptyList())?.id)
    }

    @Test
    fun `resetFailures re-allows previously failed songs`() = runTest {
        val failed = song(50, "会失败的")
        val provider = StaticProvider(favorites = listOf(failed), history = emptyList())
        val source = LocalHeartbeatCandidateSource(provider = provider)

        source.markFailed(failed)
        assertNull(source.selectNext(current, emptyList()))

        source.resetFailures()
        assertEquals(50L, source.selectNext(current, emptyList())?.id)
    }

    // ---- 不喜欢标记 ----

    @Test
    fun `selectNext skips a disliked favorite when a clean candidate exists`() = runTest {
        // 被标记的收藏歌必须让位给未标记的历史歌，这是标记的全部意义
        val disliked = song(60, "收藏但不喜欢")
        val clean = song(61, "历史正常")
        val provider = DislikeProvider(
            favorites = listOf(disliked),
            history = listOf(clean),
            dislikedKeys = setOf(HeartbeatScorer.identityKey(disliked)),
        )
        val source = LocalHeartbeatCandidateSource(provider = provider)

        assertEquals(61L, source.selectNext(current, emptyList())?.id)
    }

    @Test
    fun `selectNext returns null when every candidate is disliked`() = runTest {
        val only = song(62, "全都不喜欢")
        val provider = DislikeProvider(
            favorites = listOf(only),
            history = emptyList(),
            dislikedKeys = setOf(HeartbeatScorer.identityKey(only)),
        )
        val source = LocalHeartbeatCandidateSource(provider = provider)

        assertNull(source.selectNext(current, emptyList()))
    }

    @Test
    fun `disliked keys are read on every selection not cached`() = runTest {
        // 候选池有 5 分钟 TTL，但标记必须点完立刻生效，所以名单不能进缓存
        val target = song(63, "刚被标记")
        val provider = DislikeProvider(
            favorites = listOf(target),
            history = emptyList(),
            dislikedKeys = emptySet(),
        )
        val source = LocalHeartbeatCandidateSource(provider = provider)

        assertEquals(63L, source.selectNext(current, emptyList())?.id)

        provider.dislikedKeys = setOf(HeartbeatScorer.identityKey(target))
        assertNull(source.selectNext(current, emptyList()))
        assertEquals(2, provider.dislikedCalls)
    }

    @Test
    fun `disliked lookup failure is treated as no marks`() = runTest {
        // 名单读不出来不该让心动模式整体失效
        val target = song(64, "读名单失败")
        val provider = object : HeartbeatCandidateProvider {
            override suspend fun favorites(): List<SongItem> = listOf(target)
            override suspend fun recentHistory(limit: Int): List<SongItem> = emptyList()
            override suspend fun dislikedIdentities(): Set<String> =
                throw IllegalStateException("datastore corrupted")
        }
        val source = LocalHeartbeatCandidateSource(provider = provider)

        assertEquals(64L, source.selectNext(current, emptyList())?.id)
    }

    // ---- 测试替身 ----

    private class StaticProvider(
        private val favorites: List<SongItem>,
        private val history: List<SongItem>,
    ) : HeartbeatCandidateProvider {
        override suspend fun favorites(): List<SongItem> = favorites
        override suspend fun recentHistory(limit: Int): List<SongItem> = history
    }

    private class SampleProvider(
        private val favorites: List<SongItem>,
        private val history: List<SongItem>,
        private val samples: List<SongItem>,
    ) : HeartbeatCandidateProvider {
        override suspend fun favorites(): List<SongItem> = favorites
        override suspend fun recentHistory(limit: Int): List<SongItem> = history
        override suspend fun librarySamples(limit: Int): List<SongItem> = samples
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

    private class DislikeProvider(
        private val favorites: List<SongItem>,
        private val history: List<SongItem>,
        var dislikedKeys: Set<String>,
    ) : HeartbeatCandidateProvider {
        var dislikedCalls: Int = 0
            private set

        override suspend fun favorites(): List<SongItem> = favorites
        override suspend fun recentHistory(limit: Int): List<SongItem> = history

        override suspend fun dislikedIdentities(): Set<String> {
            dislikedCalls++
            return dislikedKeys
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
