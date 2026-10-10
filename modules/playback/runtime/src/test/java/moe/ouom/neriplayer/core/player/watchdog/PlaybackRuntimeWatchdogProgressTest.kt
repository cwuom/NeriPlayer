package moe.ouom.neriplayer.core.player.watchdog

import android.os.SystemClock
import kotlinx.coroutines.Job
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.policy.progress.PLAYBACK_RUNTIME_STALL_POSITION_TOLERANCE_MS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic

class PlaybackRuntimeWatchdogProgressTest {

    private val manager = PlayerManager
    private val previousJob = manager.playbackRuntimeWatchdogJob
    private val previousToken = manager.playbackRuntimeWatchdogToken
    private val previousAttempts = manager.playbackRuntimeStallRecoveryAttempts
    private val previousPositionMs = manager.playbackRuntimeLastProgressPositionMs
    private val previousProgressAtMs = manager.playbackRuntimeLastProgressAtElapsedRealtimeMs
    private lateinit var clock: MockedStatic<SystemClock>

    @Before
    fun setUp() {
        clock = mockStatic(SystemClock::class.java)
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(NOW_MS)
    }

    @After
    fun tearDown() {
        clock.close()
        manager.playbackRuntimeWatchdogJob = previousJob
        manager.playbackRuntimeWatchdogToken = previousToken
        manager.playbackRuntimeStallRecoveryAttempts = previousAttempts
        manager.playbackRuntimeLastProgressPositionMs = previousPositionMs
        manager.playbackRuntimeLastProgressAtElapsedRealtimeMs = previousProgressAtMs
    }

    @Test
    fun `reset cancels the running watchdog and forgets recorded progress`() {
        val watchdog = Job()
        manager.playbackRuntimeWatchdogJob = watchdog
        manager.playbackRuntimeWatchdogToken = 41L
        manager.playbackRuntimeStallRecoveryAttempts = 2
        manager.playbackRuntimeLastProgressPositionMs = 5_000L
        manager.playbackRuntimeLastProgressAtElapsedRealtimeMs = 9_000L

        manager.resetPlaybackRuntimeWatchdog("seek")

        assertTrue(watchdog.isCancelled)
        assertNull(manager.playbackRuntimeWatchdogJob)
        assertEquals(42L, manager.playbackRuntimeWatchdogToken)
        assertEquals(0, manager.playbackRuntimeStallRecoveryAttempts)
        assertEquals(0L, manager.playbackRuntimeLastProgressPositionMs)
        assertEquals(0L, manager.playbackRuntimeLastProgressAtElapsedRealtimeMs)
    }

    @Test
    fun `reset without a running watchdog still invalidates the previous token`() {
        manager.playbackRuntimeWatchdogJob = null
        manager.playbackRuntimeWatchdogToken = 7L

        manager.resetPlaybackRuntimeWatchdog("stop")

        assertEquals(8L, manager.playbackRuntimeWatchdogToken)

        val finished = Job().apply { complete() }
        manager.playbackRuntimeWatchdogJob = finished

        manager.resetPlaybackRuntimeWatchdog("stop")

        assertEquals(9L, manager.playbackRuntimeWatchdogToken)
        assertFalse(finished.isCancelled)
        assertNull(manager.playbackRuntimeWatchdogJob)
    }

    @Test
    fun `first progress sample is recorded even without movement`() {
        manager.playbackRuntimeLastProgressPositionMs = 0L
        manager.playbackRuntimeLastProgressAtElapsedRealtimeMs = 0L
        manager.playbackRuntimeStallRecoveryAttempts = 3

        manager.recordPlaybackRuntimeProgress(-25L)

        assertEquals(0L, manager.playbackRuntimeLastProgressPositionMs)
        assertEquals(NOW_MS, manager.playbackRuntimeLastProgressAtElapsedRealtimeMs)
        assertEquals(0, manager.playbackRuntimeStallRecoveryAttempts)
    }

    @Test
    fun `later samples refresh progress only after moving beyond the tolerance`() {
        manager.playbackRuntimeLastProgressPositionMs = 1_000L
        manager.playbackRuntimeLastProgressAtElapsedRealtimeMs = 4_000L
        manager.playbackRuntimeStallRecoveryAttempts = 2

        manager.recordPlaybackRuntimeProgress(1_000L + PLAYBACK_RUNTIME_STALL_POSITION_TOLERANCE_MS)

        assertEquals(1_000L, manager.playbackRuntimeLastProgressPositionMs)
        assertEquals(4_000L, manager.playbackRuntimeLastProgressAtElapsedRealtimeMs)
        assertEquals(2, manager.playbackRuntimeStallRecoveryAttempts)

        val movedPositionMs = 1_001L + PLAYBACK_RUNTIME_STALL_POSITION_TOLERANCE_MS
        manager.recordPlaybackRuntimeProgress(movedPositionMs)

        assertEquals(movedPositionMs, manager.playbackRuntimeLastProgressPositionMs)
        assertEquals(NOW_MS, manager.playbackRuntimeLastProgressAtElapsedRealtimeMs)
        assertEquals(0, manager.playbackRuntimeStallRecoveryAttempts)
    }

    private companion object {
        const val NOW_MS = 86_400_000L
    }
}
