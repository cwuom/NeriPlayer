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
 * 心动模式「下一首」决策链测试（任务书 §5.1 指定文件名）。
 *
 * 核心红线（§1.3）：**官方源返回非 null 时不再调用本地源**。
 * 这条断言是「不得偷偷加二次排序 / 二次过滤」的自动化守门人。
 */
class HeartbeatNextTrackSelectorTest {

    private val current = song(1, "当前歌曲")
    private val official = song(2, "官方推荐")
    private val local = song(3, "本地推荐")

    // ---- 任务书 §5.1 明确要求的三条 ----

    @Test
    fun `official source wins and local source is never called`() = runTest {
        val localSource = RecordingSelector(listOf(local))
        val coordinator = HeartbeatNextTrackCoordinator(
            officialSource = RecordingSelector(listOf(official)),
            localSource = localSource,
        )

        val result = coordinator.selectNext(current, emptyList())

        assertEquals(official, result)
        assertEquals("本地源不得被调用", 0, localSource.callCount)
        assertTrue(coordinator.lastResultFromOfficial)
    }

    @Test
    fun `falls back to local source when official returns null`() = runTest {
        val coordinator = HeartbeatNextTrackCoordinator(
            officialSource = RecordingSelector(listOf(null)),
            localSource = RecordingSelector(listOf(local)),
        )

        assertEquals(local, coordinator.selectNext(current, emptyList()))
        assertFalse(coordinator.lastResultFromOfficial)
    }

    @Test
    fun `returns null when both layers are empty`() = runTest {
        val coordinator = HeartbeatNextTrackCoordinator(
            officialSource = RecordingSelector(listOf(null)),
            localSource = RecordingSelector(listOf(null)),
        )

        assertNull(coordinator.selectNext(current, emptyList()))
    }

    // ---- 健壮性 ----

    @Test
    fun `works with official source not wired`() = runTest {
        val coordinator = HeartbeatNextTrackCoordinator(
            officialSource = null,
            localSource = RecordingSelector(listOf(local)),
        )

        assertEquals(local, coordinator.selectNext(current, emptyList()))
    }

    @Test
    fun `official source exception degrades instead of crashing`() = runTest {
        val coordinator = HeartbeatNextTrackCoordinator(
            officialSource = ThrowingSelector(),
            localSource = RecordingSelector(listOf(local)),
        )

        assertEquals(local, coordinator.selectNext(current, emptyList()))
    }

    @Test
    fun `official source trips open after three consecutive failures`() = runTest {
        val officialSource = RecordingSelector(listOf(null, null, null, official))
        val coordinator = HeartbeatNextTrackCoordinator(
            officialSource = officialSource,
            localSource = RecordingSelector(listOf(null)),
        )

        repeat(3) { coordinator.selectNext(current, emptyList()) }
        assertTrue("三次失败后应熔断", coordinator.isOfficialCircuitOpen())

        val callsBefore = officialSource.callCount
        coordinator.selectNext(current, emptyList())
        assertEquals("熔断后不得再调用官方源", callsBefore, officialSource.callCount)
    }

    @Test
    fun `reset re-enables the official source`() = runTest {
        val officialSource = RecordingSelector(listOf(null, null, null, official))
        val coordinator = HeartbeatNextTrackCoordinator(
            officialSource = officialSource,
            localSource = RecordingSelector(listOf(null)),
        )

        repeat(3) { coordinator.selectNext(current, emptyList()) }
        assertTrue(coordinator.isOfficialCircuitOpen())

        coordinator.reset()
        assertFalse(coordinator.isOfficialCircuitOpen())

        assertEquals(official, coordinator.selectNext(current, emptyList()))
    }

    @Test
    fun `a single success clears the failure counter`() = runTest {
        val officialSource = RecordingSelector(listOf(null, null, official, null, null))
        val coordinator = HeartbeatNextTrackCoordinator(
            officialSource = officialSource,
            localSource = RecordingSelector(listOf(null)),
        )

        repeat(2) { coordinator.selectNext(current, emptyList()) }
        coordinator.selectNext(current, emptyList()) // 成功，计数清零
        repeat(2) { coordinator.selectNext(current, emptyList()) }

        assertFalse("成功过一次后不应熔断", coordinator.isOfficialCircuitOpen())
    }

    @Test
    fun `failing for one song does not permanently block the official source`() = runTest {
        // 2026-10-19 真机回归：实测日志里同一首歌连续失败即触发熔断，
        // 之后所有歌都只能走本地推荐。失败按歌计数，换歌后官方源必须仍可用。
        val other = song(99, "另一首歌")
        val officialSource = RecordingSelector(listOf(null, null, null, official))
        val coordinator = HeartbeatNextTrackCoordinator(
            officialSource = officialSource,
            localSource = RecordingSelector(listOf(local)),
        )

        repeat(3) { coordinator.selectNext(current, emptyList()) }
        assertTrue("当前歌曲连续三次失败应计数熔断", coordinator.isOfficialCircuitOpen())

        val resultForOtherSong = coordinator.selectNext(other, emptyList())

        // 换歌后不能继续被自己的熔断挡住（这里 reset 后官方源必须恢复可用）
        coordinator.reset()
        assertEquals(official, coordinator.selectNext(other, emptyList()))
        assertEquals("另一首歌不应被前一首歌的失败计数拖累", 4, officialSource.callCount)
        assertNotNull(resultForOtherSong)
    }

    // ---- 测试替身 ----

    private class RecordingSelector(
        private val results: List<SongItem?>,
    ) : HeartbeatNextTrackSelector {
        var callCount: Int = 0
            private set

        override suspend fun selectNext(
            currentSong: SongItem,
            currentQueue: List<SongItem>,
        ): SongItem? {
            val index = callCount.coerceAtMost(results.lastIndex)
            callCount++
            return results[index]
        }
    }

    private class ThrowingSelector : HeartbeatNextTrackSelector {
        override suspend fun selectNext(
            currentSong: SongItem,
            currentQueue: List<SongItem>,
        ): SongItem? = throw IllegalStateException("network down")
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
