package moe.ouom.neriplayer.core.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import moe.ouom.neriplayer.core.player.timer.SleepTimerManager
import moe.ouom.neriplayer.core.player.timer.formatSleepTimerNotificationRemaining
import moe.ouom.neriplayer.core.player.timer.formatSleepTimerRemaining
import moe.ouom.neriplayer.core.player.timer.sleepTimerNotificationKey
import moe.ouom.neriplayer.data.model.playback.SleepTimerMode
import moe.ouom.neriplayer.data.model.playback.SleepTimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerManagerTest {

    @Test
    fun `notification text keeps minute granularity until the last minute`() {
        val state = SleepTimerState(isActive = true, mode = SleepTimerMode.COUNTDOWN)

        assertEquals("1:02:05", formatSleepTimerRemaining(state.copy(remainingMillis = 3_725_000L)))
        assertEquals("1:03:00", formatSleepTimerNotificationRemaining(state.copy(remainingMillis = 3_725_000L)))
        assertEquals("5:00", formatSleepTimerNotificationRemaining(state.copy(remainingMillis = 241_000L)))
        assertEquals("1:00", formatSleepTimerNotificationRemaining(state.copy(remainingMillis = 60_000L)))
        assertEquals("0:09", formatSleepTimerNotificationRemaining(state.copy(remainingMillis = 9_400L)))
        assertEquals("", formatSleepTimerNotificationRemaining(state.copy(mode = SleepTimerMode.FINISH_CURRENT)))
        assertEquals("", formatSleepTimerNotificationRemaining(state.copy(isActive = false, remainingMillis = 9_000L)))
    }

    @Test
    fun `notification key only changes when the visible countdown or timer state changes`() {
        val countdown = SleepTimerState(isActive = true, mode = SleepTimerMode.COUNTDOWN, totalMillis = 300_000L)
        val keys = (300_000L downTo 1_000L step 1_000L).map { remaining ->
            sleepTimerNotificationKey(countdown.copy(remainingMillis = remaining))
        }

        // 5:00/4:00/3:00/2:00 each cover a minute, the final minute updates every second
        assertEquals(4 + 60, keys.zipWithNext().count { (previous, next) -> previous != next } + 1)
        val finishCurrent = countdown.copy(mode = SleepTimerMode.FINISH_CURRENT)
        assertEquals(
            sleepTimerNotificationKey(finishCurrent.copy(remainingMillis = 9_000L)),
            sleepTimerNotificationKey(finishCurrent.copy(remainingMillis = 1_000L))
        )
        assertFalse(
            sleepTimerNotificationKey(countdown.copy(remainingMillis = 90_000L)) ==
                sleepTimerNotificationKey(countdown.copy(isActive = false, remainingMillis = 90_000L))
        )
    }

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
    fun `countdown can finish current song after expiry`() = runTest {
        var timerExpired = false
        var nowMs = 0L
        val manager = SleepTimerManager(
            scope = this,
            onTimerExpired = { timerExpired = true },
            nowMsProvider = { nowMs }
        )

        manager.startCountdown(minutes = 1, finishCurrentOnExpiry = true)

        assertEquals(
            SleepTimerMode.COUNTDOWN_FINISH_CURRENT,
            manager.timerState.value.mode
        )
        nowMs = 60_000L
        advanceTimeBy(60_000.milliseconds)
        runCurrent()

        assertFalse(timerExpired)
        assertEquals(SleepTimerMode.FINISH_CURRENT, manager.timerState.value.mode)
        assertTrue(manager.shouldStopOnTrackEnd(isLastInPlaylist = false))
    }

    @Test
    fun `countdown still stops immediately after expiry`() = runTest {
        var timerExpired = false
        var nowMs = 0L
        val manager = SleepTimerManager(
            scope = this,
            onTimerExpired = { timerExpired = true },
            nowMsProvider = { nowMs }
        )

        manager.startCountdown(minutes = 1)
        nowMs = 60_000L
        advanceTimeBy(60_000.milliseconds)
        runCurrent()

        assertTrue(timerExpired)
        assertFalse(manager.timerState.value.isActive)
    }

    @Test
    fun `countdown uses elapsed time after a delayed wakeup`() = runTest {
        var nowMs = 0L
        val manager = SleepTimerManager(
            scope = this,
            onTimerExpired = {},
            nowMsProvider = { nowMs }
        )

        manager.startCountdown(minutes = 1, finishCurrentOnExpiry = true)
        advanceTimeBy(1_000.milliseconds)
        nowMs = 60_000L
        advanceTimeBy(1_000.milliseconds)
        runCurrent()

        assertEquals(SleepTimerMode.FINISH_CURRENT, manager.timerState.value.mode)
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
