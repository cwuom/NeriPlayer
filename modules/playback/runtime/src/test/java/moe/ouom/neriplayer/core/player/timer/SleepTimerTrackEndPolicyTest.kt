package moe.ouom.neriplayer.core.player.timer

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTrackEndPolicyTest {

    @Test
    fun `finish playlist stops only after the last track`() {
        val manager = SleepTimerManager(scope = TestScope(), onTimerExpired = {})
        manager.startFinishPlaylist()

        assertFalse(manager.shouldStopOnTrackEnd(isLastInPlaylist = false))
        assertTrue(manager.shouldStopOnTrackEnd(isLastInPlaylist = true))
    }

    @Test
    fun `running countdowns and cancelled timers never stop on track end`() = runTest {
        val manager = SleepTimerManager(
            scope = backgroundScope,
            onTimerExpired = {},
            nowMsProvider = { testScheduler.currentTime }
        )

        manager.startCountdown(minutes = 5)
        assertFalse(manager.shouldStopOnTrackEnd(isLastInPlaylist = true))

        manager.cancel()
        assertFalse(manager.shouldStopOnTrackEnd(isLastInPlaylist = true))
    }
}
