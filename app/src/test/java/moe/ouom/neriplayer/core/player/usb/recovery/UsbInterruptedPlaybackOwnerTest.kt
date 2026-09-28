package moe.ouom.neriplayer.core.player.usb.recovery

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsbInterruptedPlaybackOwnerTest {
    @Test
    fun `intent records queue identity and is ignored for an invalid index`() = runTest {
        val port = RecordingPort()
        val owner = UsbInterruptedPlaybackOwner(backgroundScope, port, nowElapsedMs = { 123L })

        owner.remember("missing", queueIndex = 8, positionMs = 10L)
        assertNull(owner.intent)
        owner.remember("native_failure", queueIndex = 1, positionMs = -40L)

        assertEquals(1, owner.intent?.queueIndex)
        assertEquals(0L, owner.intent?.positionMs)
        assertEquals(77L, owner.intent?.requestToken)
        assertEquals(123L, owner.intent?.recordedAtMs)
        assertEquals(listOf(true), port.resumeRequests)
        owner.clear("cancel")
        assertNull(owner.intent)
    }

    @Test
    fun `system fallback resumes only when USB mode is disabled`() = runTest {
        val port = RecordingPort()
        val owner = UsbInterruptedPlaybackOwner(backgroundScope, port, nowElapsedMs = { 0L })
        owner.remember("failure", 1, 700L)

        assertFalse(owner.resumeOnSystemRouteIfNeeded("still_usb"))
        port.currentSnapshot = port.currentSnapshot.copy(usbEnabled = false)
        assertTrue(owner.resumeOnSystemRouteIfNeeded("disabled"))
        assertNull(owner.intent)
        assertEquals(listOf(1 to 700L), port.systemResumes)
    }

    @Test
    fun `system fallback discards an interrupted intent after the queue shrinks`() = runTest {
        val port = RecordingPort()
        val owner = UsbInterruptedPlaybackOwner(backgroundScope, port, nowElapsedMs = { 0L })
        owner.remember("failure", 1, 700L)
        port.currentSnapshot = port.currentSnapshot.copy(usbEnabled = false, queueSize = 1)

        assertFalse(owner.resumeOnSystemRouteIfNeeded("queue_changed"))
        assertNull(owner.intent)
        assertTrue(port.systemResumes.isEmpty())
    }

    @Test
    fun `reattached DAC resumes saved position after permission is ready`() = runTest {
        val port = RecordingPort()
        val owner = UsbInterruptedPlaybackOwner(backgroundScope, port, nowElapsedMs = { 0L })
        owner.remember("device_lost", 1, 9_000L)

        owner.scheduleResumeAfterDeviceAttach("attached")
        runCurrent()
        advanceTimeBy(750L)
        runCurrent()

        assertEquals(listOf(1 to 9_000L), port.usbResumes)
        assertNull(owner.intent)
        assertTrue(port.permissionRequests.isEmpty())
    }

    @Test
    fun `reattach checks native gate after requesting permission`() = runTest {
        val port = RecordingPort()
        port.availability = port.availability.copy(canRequestPermission = true)
        port.openGateOnPermissionRequest = true
        val owner = UsbInterruptedPlaybackOwner(backgroundScope, port, nowElapsedMs = { 0L })
        owner.remember("device_lost", 1, 900L)

        owner.scheduleResumeAfterDeviceAttach("attached")
        advanceTimeBy(750L)
        runCurrent()
        assertEquals(listOf("usb_device_reattach:attached"), port.permissionRequests)
        assertTrue(port.usbResumes.isEmpty())

        port.openGateOnPermissionRequest = false
        port.nativeOpenGateActive = false
        port.availability = port.availability.copy(canRequestPermission = false)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf(1 to 900L), port.usbResumes)
    }

    @Test
    fun `volume confirmation owns continuation and cancellation`() = runTest {
        val port = RecordingPort()
        port.confirmationRequired = true
        val owner = UsbInterruptedPlaybackOwner(backgroundScope, port, nowElapsedMs = { 0L })
        owner.remember("device_lost", 1, 500L)

        owner.scheduleResumeAfterDeviceAttach("attached")
        advanceTimeBy(750L)
        runCurrent()
        assertTrue(port.usbResumes.isEmpty())
        port.currentSnapshot = port.currentSnapshot.copy(mixedPlaybackEnabled = true)
        port.confirmPlayback?.invoke()
        assertTrue(port.usbResumes.isEmpty())
        assertTrue(owner.intent != null)
        port.currentSnapshot = port.currentSnapshot.copy(mixedPlaybackEnabled = false)
        port.confirmPlayback?.invoke()
        assertEquals(listOf(1 to 500L), port.usbResumes)
        assertNull(owner.intent)

        owner.remember("device_lost_again", 1, 600L)
        owner.scheduleResumeAfterDeviceAttach("attached_again")
        advanceTimeBy(750L)
        runCurrent()
        port.cancelPlayback?.invoke()
        assertNull(owner.intent)
        assertEquals(false, port.resumeRequests.last())
    }

    @Test
    fun `stale request token and cancelled job cannot restart playback`() = runTest {
        val port = RecordingPort()
        val owner = UsbInterruptedPlaybackOwner(backgroundScope, port, nowElapsedMs = { 0L })
        owner.remember("old", 1, 100L)
        owner.scheduleResumeAfterDeviceAttach("old")
        port.currentSnapshot = port.currentSnapshot.copy(requestToken = 78L)
        owner.remember("new", 1, 200L)
        advanceTimeBy(750L)
        runCurrent()
        assertTrue(port.usbResumes.isEmpty())

        owner.scheduleResumeAfterDeviceAttach("new")
        owner.cancelReattach()
        advanceTimeBy(750L)
        runCurrent()
        assertTrue(port.usbResumes.isEmpty())
    }

    @Test
    fun `queue and playback flags determine interruption candidate`() = runTest {
        val port = RecordingPort()
        val owner = UsbInterruptedPlaybackOwner(backgroundScope, port, nowElapsedMs = { 0L })
        assertEquals(1, owner.queueIndexForInterruption())
        assertTrue(owner.shouldKeepIntentAfterNativeFailure())
        val idle = port.currentSnapshot.copy(
            resumeRequested = false,
            playJobActive = false,
            playWhenReady = false,
            isPlaying = false,
            reportedPlayWhenReady = false,
            reportedPlaying = false
        )
        port.currentSnapshot = idle
        assertFalse(owner.shouldKeepIntentAfterNativeFailure())
        listOf(
            idle.copy(resumeRequested = true),
            idle.copy(playJobActive = true),
            idle.copy(playWhenReady = true),
            idle.copy(isPlaying = true),
            idle.copy(reportedPlayWhenReady = true),
            idle.copy(reportedPlaying = true)
        ).forEach { signal ->
            port.currentSnapshot = signal
            assertTrue(owner.shouldKeepIntentAfterNativeFailure())
        }
        port.currentSnapshot = idle.copy(playerInitialized = false, resumeRequested = true)
        assertFalse(owner.shouldKeepIntentAfterNativeFailure())
        port.currentSnapshot = idle.copy(queueSize = 0, resumeRequested = true)
        assertFalse(owner.shouldKeepIntentAfterNativeFailure())
        assertNull(owner.queueIndexForInterruption())
    }

    @Test
    fun `native failure saves only a live and addressable playback intent`() = runTest {
        val port = RecordingPort()
        val owner = UsbInterruptedPlaybackOwner(backgroundScope, port, nowElapsedMs = { 5L })
        assertTrue(owner.rememberAfterNativeFailure("transport_failed", 88L))
        assertEquals(88L, owner.intent?.positionMs)

        port.currentSnapshot = port.currentSnapshot.copy(
            resumeRequested = false, playJobActive = false, playWhenReady = false,
            isPlaying = false, reportedPlayWhenReady = false, reportedPlaying = false
        )
        assertFalse(owner.rememberAfterNativeFailure("idle", 99L))
        assertNull(owner.intent)
        assertEquals(false, port.resumeRequests.last())

        port.currentSnapshot = port.currentSnapshot.copy(
            resumeRequested = true, queueSize = 1, currentIndex = 8,
            currentIndexMatchesCurrentSong = false, currentSongQueueIndex = -1
        )
        assertFalse(owner.rememberAfterNativeFailure("missing_queue", 100L))
        assertNull(owner.intent)
    }

    private class RecordingPort : UsbInterruptedPlaybackPort {
        var currentSnapshot = UsbInterruptedPlaybackSnapshot(
            usbEnabled = true,
            mixedPlaybackEnabled = false,
            resumeRequested = true,
            playerInitialized = true,
            queueSize = 2,
            currentIndex = 1,
            currentIndexMatchesCurrentSong = true,
            currentSongQueueIndex = 1,
            requestToken = 77L,
            playJobActive = false,
            playWhenReady = true,
            isPlaying = true,
            reportedPlayWhenReady = true,
            reportedPlaying = true
        )
        var availability = UsbReattachAvailability(
            canRequestPermission = false,
            selectedOutputAvailable = true,
            selectedHostPermissionGranted = true
        )
        var nativeOpenGateActive = false
        var openGateOnPermissionRequest = false
        var confirmationRequired = false
        var confirmPlayback: (() -> Unit)? = null
        var cancelPlayback: (() -> Unit)? = null
        val resumeRequests = mutableListOf<Boolean>()
        val permissionRequests = mutableListOf<String>()
        val systemResumes = mutableListOf<Pair<Int, Long>>()
        val usbResumes = mutableListOf<Pair<Int, Long>>()

        override fun snapshot(): UsbInterruptedPlaybackSnapshot = currentSnapshot
        override fun reattachAvailability(): UsbReattachAvailability = availability
        override fun requestPermission(reason: String) {
            permissionRequests += reason
            if (openGateOnPermissionRequest) nativeOpenGateActive = true
        }
        override fun nativeOpenGateActive(): Boolean = nativeOpenGateActive
        override fun requestLoudPlaybackConfirmation(onConfirm: () -> Unit, onCancel: () -> Unit): Boolean {
            confirmPlayback = onConfirm
            cancelPlayback = onCancel
            return confirmationRequired
        }
        override fun updateResumeRequested(requested: Boolean) { resumeRequests += requested }
        override fun resumeOnSystemRoute(intent: UsbInterruptedPlaybackIntent) {
            systemResumes += intent.queueIndex to intent.positionMs
        }
        override fun resumeOnUsbRoute(intent: UsbInterruptedPlaybackIntent) {
            usbResumes += intent.queueIndex to intent.positionMs
        }
    }
}
