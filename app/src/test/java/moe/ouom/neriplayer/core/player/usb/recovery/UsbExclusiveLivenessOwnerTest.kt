package moe.ouom.neriplayer.core.player.usb.recovery

import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.usb.path.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveNativeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsbExclusiveLivenessOwnerTest {
    @Test
    fun `background transition owns audit and cancels it on foreground return`() = runTest {
        val port = RecordingPort()
        val owner = UsbExclusiveLivenessOwner(backgroundScope, port)

        owner.updateForegroundState(false, "screen_off")
        assertFalse(owner.appInForeground)
        assertEquals(listOf("screen_off"), port.foregroundReassertions)
        assertEquals(listOf("screen_off"), port.anchorUpdates)
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf("background_audit:screen_off:1000"), port.recoveries.map { it.first })

        owner.updateForegroundState(true, "screen_on")
        assertTrue(owner.appInForeground)
        assertEquals(listOf("screen_off", "screen_on"), port.anchorUpdates)
        advanceTimeBy(15_000L)
        runCurrent()
        assertEquals(1, port.recoveries.size)
    }

    @Test
    fun `disabled USB mode updates the anchor without scheduling liveness work`() = runTest {
        val port = RecordingPort()
        port.currentSnapshot = port.currentSnapshot.copy(playbackEnabled = false)
        val owner = UsbExclusiveLivenessOwner(backgroundScope, port)

        owner.updateForegroundState(false, "screen_off")
        owner.updateForegroundState(false, "duplicate")
        advanceTimeBy(15_000L)
        runCurrent()

        assertEquals(listOf("screen_off"), port.anchorUpdates)
        assertTrue(port.foregroundReassertions.isEmpty())
        assertTrue(port.recoveries.isEmpty())
        assertEquals(0, port.transferWindowChanges)
        assertEquals(0, port.bufferChanges)
    }

    @Test
    fun `stale generation and explicit cancel prevent background recovery`() = runTest {
        val port = RecordingPort()
        val owner = UsbExclusiveLivenessOwner(backgroundScope, port)
        owner.updateForegroundState(false, "background")
        port.currentSnapshot = port.currentSnapshot.copy(routeGeneration = 2L)
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(port.recoveries.isEmpty())

        port.currentSnapshot = port.currentSnapshot.copy(routeGeneration = 1L)
        owner.scheduleBackgroundAudit("retry")
        owner.cancelJobs()
        advanceTimeBy(15_000L)
        runCurrent()
        assertTrue(port.recoveries.isEmpty())
    }

    @Test
    fun `background audit forces recovery when native frames stop advancing`() = runTest {
        val port = RecordingPort()
        val owner = UsbExclusiveLivenessOwner(backgroundScope, port)

        owner.updateForegroundState(false, "screen_off")
        advanceTimeBy(5_000L)
        runCurrent()

        assertTrue(port.recoveries.contains("background_audit:screen_off:1000" to false))
        assertTrue(port.recoveries.contains("background_audit_fake_progress:screen_off:5000" to true))
    }

    @Test
    fun `foreground stopped transport restores intent then requests forced recovery`() = runTest {
        val port = RecordingPort()
        port.currentSnapshot = port.currentSnapshot.copy(transportActive = false)
        port.currentNative = port.currentNative.copy(opened = false, streaming = false)
        val owner = UsbExclusiveLivenessOwner(backgroundScope, port)

        owner.recoverOnForeground("resume")
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("resume"), port.restoredIntents)
        assertEquals(2, port.focusApplications)
        assertTrue(port.recoveries.contains("foreground_stopped:resume" to true))
        assertEquals(0, port.stableMarks)
    }

    @Test
    fun `foreground progress probe marks the route stable`() = runTest {
        val port = RecordingPort()
        port.nativeSamples += port.currentNative.copy(completedAudioFrames = 10L)
        port.nativeSamples += port.currentNative.copy(completedAudioFrames = 100L)
        val owner = UsbExclusiveLivenessOwner(backgroundScope, port)

        owner.recoverOnForeground("resume")
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(1, port.stableMarks)
        assertFalse(port.recoveries.any { it.second })
    }

    @Test
    fun `foreground recovery waits for a valid sample and an open native gate`() = runTest {
        val port = RecordingPort()
        val owner = UsbExclusiveLivenessOwner(backgroundScope, port)
        port.currentNative = port.currentNative.copy(
            runtimeReportValid = false,
            runtimeReportInvalidReason = "runtime_report_invalid"
        )
        owner.recoverOnForeground("invalid")
        runCurrent()
        assertTrue(port.recoveries.isEmpty())

        port.currentNative = port.currentNative.copy(runtimeReportValid = true)
        port.currentOpenGateReason = "native_reopening"
        owner.recoverOnForeground("open_gate")
        runCurrent()
        assertTrue(port.recoveries.isEmpty())
    }

    @Test
    fun `buffer changes use transfer window while streaming and resize when stopped`() {
        val port = RecordingPort()
        val owner = UsbExclusiveLivenessOwner(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), port)

        port.currentNative = port.currentNative.copy(streaming = true, bufferDurationMs = 100)
        owner.applyActiveBuffer("streaming")
        assertEquals(1, port.transferWindowChanges)
        assertEquals(0, port.bufferChanges)

        port.currentNative = port.currentNative.copy(streaming = false)
        owner.applyActiveBuffer("stopped")
        assertEquals(1, port.bufferChanges)
    }

    private class RecordingPort : UsbExclusiveLivenessPort {
        var currentSnapshot = UsbExclusiveLivenessSnapshot(
            playbackEnabled = true,
            playerInitialized = true,
            routeGeneration = 1L,
            transportActive = true,
            playerPositionMs = 0L,
            playerState = Player.STATE_READY,
            playWhenReady = true,
            isPlaying = true
        )
        var currentNative = UsbExclusiveNativeState(
            opened = true,
            streaming = true,
            source = "player_pcm",
            handle = 1L,
            completedAudioFrames = 10L
        )
        var currentOpenGateReason: String? = null
        val nativeSamples = ArrayDeque<UsbExclusiveNativeState>()
        val recoveries = mutableListOf<Pair<String, Boolean>>()
        val foregroundReassertions = mutableListOf<String>()
        val anchorUpdates = mutableListOf<String>()
        val restoredIntents = mutableListOf<String>()
        var focusApplications = 0
        var stableMarks = 0
        var transferWindowChanges = 0
        var bufferChanges = 0

        override fun snapshot(): UsbExclusiveLivenessSnapshot = currentSnapshot
        override fun pathState(): UsbExclusiveAudioPathState = UsbExclusiveAudioPathState(
            effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB,
            sinkPlaying = true
        )
        override fun nativeState(): UsbExclusiveNativeState = currentNative
        override fun refreshNativeState() {
            if (nativeSamples.isNotEmpty()) currentNative = nativeSamples.removeFirst()
        }
        override fun nativeOpenGateReason(): String? = currentOpenGateReason
        override fun backgroundAuditContext(): String = "test"
        override fun reassertServiceForeground(reason: String) { foregroundReassertions += reason }
        override fun updateBackgroundAnchor(reason: String) { anchorUpdates += reason }
        override fun recoverRoute(reason: String, forceRecovery: Boolean): Boolean {
            recoveries += reason to forceRecovery
            return false
        }
        override fun applyAudioFocus() { focusApplications++ }
        override fun applyPlaybackPolicy() = Unit
        override fun restorePlaybackIntent(reason: String) { restoredIntents += reason }
        override fun markForegroundStable() { stableMarks++ }
        override fun targetBufferDurationMs(foreground: Boolean): Int = if (foreground) 200 else 300
        override fun configureTransferWindow(durationMs: Int, foreground: Boolean): Boolean {
            transferWindowChanges++
            return true
        }
        override fun configureBufferDuration(durationMs: Int, foreground: Boolean): Boolean {
            bufferChanges++
            return true
        }
    }
}
