package moe.ouom.neriplayer.core.player.queue.heartbeat

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选池 TTL 缓存测试（任务书 §5.1）。
 *
 * 覆盖：TTL 过期重建、`invalidate()` 生效、空池不缓存、
 * 时钟回拨按过期处理。
 */
class HeartbeatCandidateCacheTest {

    private var now = 1_000_000L

    private fun cache(ttlMs: Long = HeartbeatCandidateCache.DEFAULT_TTL_MS) =
        HeartbeatCandidateCache(ttlMs = ttlMs, nowMs = { now })

    @Test
    fun `builds once and reuses within ttl`() = runTest {
        val cache = cache()
        var buildCount = 0
        val rebuild = suspend {
            buildCount++
            poolOf(song(1))
        }

        cache.getOrBuild(rebuild)
        cache.getOrBuild(rebuild)
        cache.getOrBuild(rebuild)

        assertEquals(1, buildCount)
        assertTrue(cache.isCached())
    }

    @Test
    fun `rebuilds after ttl expires`() = runTest {
        val cache = cache(ttlMs = 5 * 60 * 1000L)
        var buildCount = 0
        val rebuild = suspend {
            buildCount++
            poolOf(song(buildCount.toLong()))
        }

        cache.getOrBuild(rebuild)
        now += 5 * 60 * 1000L - 1
        cache.getOrBuild(rebuild)
        assertEquals("TTL 内不应重建", 1, buildCount)

        now += 2
        cache.getOrBuild(rebuild)
        assertEquals("TTL 过后必须重建", 2, buildCount)
    }

    @Test
    fun `invalidate forces rebuild`() = runTest {
        val cache = cache()
        var buildCount = 0
        val rebuild = suspend {
            buildCount++
            poolOf(song(1))
        }

        cache.getOrBuild(rebuild)
        cache.invalidate()
        assertFalse(cache.isCached())

        cache.getOrBuild(rebuild)
        assertEquals(2, buildCount)
    }

    @Test
    fun `empty pool is not cached`() = runTest {
        val cache = cache()
        var buildCount = 0
        val rebuild = suspend {
            buildCount++
            HeartbeatCandidatePool.EMPTY
        }

        cache.getOrBuild(rebuild)
        cache.getOrBuild(rebuild)

        assertEquals("空池不得被钉死缓存", 2, buildCount)
        assertFalse(cache.isCached())
    }

    @Test
    fun `clock rollback is treated as expired`() = runTest {
        val cache = cache(ttlMs = 1000L)
        var buildCount = 0
        val rebuild = suspend {
            buildCount++
            poolOf(song(1))
        }

        cache.getOrBuild(rebuild)
        now -= 10_000L
        cache.getOrBuild(rebuild)

        assertEquals(2, buildCount)
    }

    @Test
    fun `default ttl matches the five minute requirement`() {
        assertEquals(5 * 60 * 1000L, HeartbeatCandidateCache.DEFAULT_TTL_MS)
    }

    @Test
    fun `invalidate during build discards the stale result`() = runTest {
        val cache = cache()
        val built = cache.getOrBuild {
            // 构建过程中用户退出了心动模式
            cache.invalidate()
            poolOf(song(1))
        }

        assertNotNull(built)
        assertFalse("构建期间被 invalidate 的结果不得写入缓存", cache.isCached())
    }

    // ---- 助手 ----

    private fun poolOf(vararg songs: SongItem) = HeartbeatCandidatePool(
        mapOf(
            HeartbeatCandidateTier.FAVORITE to songs.map {
                HeartbeatCandidate(it, HeartbeatCandidateTier.FAVORITE)
            },
        ),
    )

    private fun song(id: Long) = SongItem(
        id = id,
        name = "歌曲$id",
        artist = "歌手$id",
        album = "专辑$id",
        albumId = id,
        durationMs = 180_000L,
        coverUrl = null,
    )
}
