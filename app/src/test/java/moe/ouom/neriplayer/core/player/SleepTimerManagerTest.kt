package moe.ouom.neriplayer.core.player

import kotlinx.coroutines.test.TestScope
import moe.ouom.neriplayer.core.player.timer.SleepTimerManager
import moe.ouom.neriplayer.core.player.timer.SleepTimerMode
import moe.ouom.neriplayer.core.player.timer.SleepTimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerManagerTest {

    @Test
    fun `finish current stops on any track end`() {
        val manager = SleepTimerManager(
            scope = TestScope(),
            onTimerExpired = {}
        )

        manager.startFinishCurrent()

        assertTrue(manager.timerState.value.isActive)
        assertEquals(SleepTimerMode.FINISH_CURRENT, manager.timerState.value.mode)
        assertTrue(manager.shouldStopOnTrackEnd(isLastInPlaylist = false))
    }

    @Test
    fun `cancel clears finish current state`() {
        val manager = SleepTimerManager(
            scope = TestScope(),
            onTimerExpired = {}
        )

        manager.startFinishCurrent()
        manager.cancel()

        assertFalse(manager.timerState.value.isActive)
        assertFalse(manager.shouldStopOnTrackEnd(isLastInPlaylist = true))
    }

    @Test
    fun `state change callback fires for finish current and cancel`() {
        val states = mutableListOf<SleepTimerState>()
        val manager = SleepTimerManager(
            scope = TestScope(),
            onTimerExpired = {},
            onTimerStateChanged = states::add
        )

        manager.startFinishCurrent()
        manager.cancel()

        assertEquals(
            listOf(
                SleepTimerState(isActive = true, mode = SleepTimerMode.FINISH_CURRENT),
                SleepTimerState()
            ),
            states
        )
    }
}
