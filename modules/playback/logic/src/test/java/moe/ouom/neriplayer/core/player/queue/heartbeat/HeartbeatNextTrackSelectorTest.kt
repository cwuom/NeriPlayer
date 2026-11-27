package moe.ouom.neriplayer.core.player.queue.heartbeat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 心动模式选歌协调器测试：官方结果原样返回，失败按歌计数并熔断。 */
class HeartbeatNextTrackSelectorTest {

    private val current = song(1, "当前歌曲")
    private val official = song(2, "官方推荐")

    @Test
    fun `official result is returned as is`() = runTest {
        val coordinator = HeartbeatNextTrackCoordinator(officialSource = RecordingSelector(listOf(official)))

        assertEquals(official, coordinator.selectNext(current, emptyList()))
    }

    @Test
    fun `returns null when official returns null`() = runTest {
        val coordinator = HeartbeatNextTrackCoordinator(officialSource = RecordingSelector(listOf(null)))

        assertNull(coordinator.selectNext(current, emptyList()))
    }

    // ---- 健壮性 ----

    @Test
    fun `works with official source not wired`() = runTest {
        assertNull(HeartbeatNextTrackCoordinator(officialSource = null).selectNext(current, emptyList()))
    }

    @Test
    fun `official source exception degrades instead of crashing`() = runTest {
        val coordinator = HeartbeatNextTrackCoordinator(officialSource = ThrowingSelector())

        assertNull(coordinator.selectNext(current, emptyList()))
    }

    @Test
    fun `official source trips open after three consecutive failures`() = runTest {
        val officialSource = RecordingSelector(listOf(null, null, null, official))
        val coordinator = HeartbeatNextTrackCoordinator(
            officialSource = officialSource,
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
        )

        repeat(2) { coordinator.selectNext(current, emptyList()) }
        coordinator.selectNext(current, emptyList()) // 成功，计数清零
        repeat(2) { coordinator.selectNext(current, emptyList()) }

        assertFalse("成功过一次后不应熔断", coordinator.isOfficialCircuitOpen())
    }

    @Test
    fun `failing for one song does not permanently block the official source`() = runTest {
        // 2026-10-19 真机回归：实测日志里同一首歌连续失败即触发熔断。
        // 失败按歌计数，重新开启后官方源必须仍可用。
        val other = song(99, "另一首歌")
        val officialSource = RecordingSelector(listOf(null, null, null, official))
        val coordinator = HeartbeatNextTrackCoordinator(
            officialSource = officialSource,
        )

        repeat(3) { coordinator.selectNext(current, emptyList()) }
        assertTrue("当前歌曲连续三次失败应计数熔断", coordinator.isOfficialCircuitOpen())

        // reset 后官方源必须恢复可用，另一首歌不被前一首的失败计数拖累
        coordinator.reset()
        assertEquals(official, coordinator.selectNext(other, emptyList()))
        assertEquals(4, officialSource.callCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a result arriving after reset cannot trip the new session`() = runTest {
        // 重置只会清状态，打断不了已经在途的选歌请求。若那个请求回来后照旧写回，
        // 刚重置好的新会话会被上一轮的失败重新标成熔断。
        val gate = CompletableDeferred<Unit>()
        val officialSource = GatedSelector(gate, suspendAtCall = 3, results = listOf(null))
        val coordinator = HeartbeatNextTrackCoordinator(
            officialSource = officialSource,
        )

        repeat(2) { coordinator.selectNext(current, emptyList()) }
        assertFalse(coordinator.isOfficialCircuitOpen())

        val inFlight = launch { coordinator.selectNext(current, emptyList()) }
        runCurrent()
        coordinator.reset()
        gate.complete(Unit)
        inFlight.join()

        assertFalse("重置前发出的请求不得把新会话标成熔断", coordinator.isOfficialCircuitOpen())
        assertEquals(3, officialSource.callCount)
    }

    // ---- 测试替身 ----

    private class GatedSelector(
        private val gate: CompletableDeferred<Unit>,
        private val suspendAtCall: Int,
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
            if (callCount == suspendAtCall) gate.await()
            return results[index]
        }
    }

    private class RecordingSelector(        private val results: List<SongItem?>,
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
