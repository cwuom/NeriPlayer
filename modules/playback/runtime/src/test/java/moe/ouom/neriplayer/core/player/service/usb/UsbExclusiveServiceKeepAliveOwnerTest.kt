package moe.ouom.neriplayer.core.player.service.usb

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsbExclusiveServiceKeepAliveOwnerTest {
    @Test
    fun `active background playback refreshes native session and stops after becoming idle`() = runTest {
        val port = FakePort()
        val owner = UsbExclusiveServiceKeepAliveOwner(backgroundScope, port)

        owner.update("started")
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, port.refreshCalls)
        assertEquals(1, port.presentationCalls)
        assertEquals(listOf("started", "usb_keepalive"), port.anchorStarts)

        port.active = false
        owner.update("paused")
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, port.refreshCalls)
        assertTrue("inactive:paused" in port.anchorStops)
        owner.close()
        assertTrue("service_destroy" in port.anchorStops)
    }

    @Test
    fun `repeated activation keeps one watchdog loop`() = runTest {
        val port = FakePort()
        val owner = UsbExclusiveServiceKeepAliveOwner(backgroundScope, port)

        owner.update("started")
        runCurrent()
        owner.update("still_active")
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(1, port.refreshCalls)
        owner.close()
    }

    @Test
    fun `unchanged native frames trigger a bounded stalled playback recovery`() = runTest {
        val port = FakePort().apply {
            native = UsbExclusiveNativeState(
                opened = true, streaming = true, source = "player_pcm",
                handle = 42L, completedAudioFrames = 100L,
            )
            path = UsbExclusiveAudioPathState(
                effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB,
                sinkPlaying = true,
            )
        }
        val owner = UsbExclusiveServiceKeepAliveOwner(backgroundScope, port)

        owner.update("started")
        runCurrent()
        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue("one stalled second must not rebuild the route", port.recoveries.isEmpty())

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("service_keepalive_stalled"), port.recoveries)
        owner.close()
    }

    @Test
    fun `watchdog does not recover when native player output is not expected`() = runTest {
        val scenarios: List<Pair<String, FakePort.() -> Unit>> = listOf(
            "USB disabled" to { usbEnabled = false },
            "transport inactive" to { transportRunning = false },
            "system output" to {
                path = path.copy(effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_SYSTEM)
            },
            "sink paused" to { path = path.copy(sinkPlaying = false) },
            "another native source" to { native = native.copy(source = "external") },
        )
        scenarios.forEach { (name, change) ->
            val port = FakePort().apply {
                native = UsbExclusiveNativeState(
                    opened = true, streaming = true, source = "player_pcm",
                    handle = 42L, completedAudioFrames = 100L,
                )
                path = UsbExclusiveAudioPathState(
                    effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB,
                    sinkPlaying = true,
                )
                change()
            }
            val owner = UsbExclusiveServiceKeepAliveOwner(backgroundScope, port)
            owner.update("started")
            runCurrent()
            advanceTimeBy(2_000L)
            runCurrent()
            assertTrue("unexpected recovery for $name", port.recoveries.isEmpty())
            owner.close()
        }
    }

    @Test
    fun `advanced frames and native counter reset update the baseline without recovery`() = runTest {
        val port = FakePort().apply {
            native = UsbExclusiveNativeState(
                opened = true, streaming = true, source = "player_pcm",
                handle = 42L, completedAudioFrames = 100L,
                runtimeReport = "sampleRate=48000 channels=2 subslotBytes=2 pcmLevel=0/10000",
            )
            path = UsbExclusiveAudioPathState(
                effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB,
                sinkPlaying = true,
            )
        }
        val owner = UsbExclusiveServiceKeepAliveOwner(backgroundScope, port)
        owner.update("started")
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        port.native = port.native.copy(completedAudioFrames = 110L)
        advanceTimeBy(1_000L)
        runCurrent()
        port.native = port.native.copy(completedAudioFrames = 5L)
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(port.recoveries.isEmpty())
        owner.close()
    }

    @Test
    fun `failed stopped transport is recovered without waiting for frame comparison`() = runTest {
        val port = FakePort().apply {
            native = UsbExclusiveNativeState(
                opened = true, streaming = false, source = "player_pcm",
                handle = 42L,
                runtimeReport = "source=player_pcm running=false transportFailed=true lastError=transfer_status=5",
            )
            path = UsbExclusiveAudioPathState(
                effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB,
                sinkPlaying = true,
            )
        }
        val owner = UsbExclusiveServiceKeepAliveOwner(backgroundScope, port)

        owner.update("started")
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("service_keepalive_transport_stopped"), port.recoveries)
        owner.close()
    }

    @Test
    fun `stopped transport recovery waits for an opened failed and settled session`() = runTest {
        val scenarios: List<Pair<String, UsbExclusiveNativeState.() -> UsbExclusiveNativeState>> = listOf(
            "not opened" to { copy(opened = false) },
            "paused" to { copy(paused = true) },
            "transitioning" to { copy(transitioning = true) },
            "no transport failure" to { copy(runtimeReport = "transportFailed=false") },
        )
        scenarios.forEach { (name, change) ->
            val port = FakePort().apply {
                native = UsbExclusiveNativeState(
                    opened = true, streaming = false, source = "player_pcm",
                    handle = 42L, runtimeReport = "transportFailed=true",
                ).change()
                path = UsbExclusiveAudioPathState(
                    effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB,
                    sinkPlaying = true,
                )
            }
            val owner = UsbExclusiveServiceKeepAliveOwner(backgroundScope, port)
            owner.update("started")
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue("unexpected recovery for $name", port.recoveries.isEmpty())
            owner.close()
        }
    }

    @Test
    fun `background transition reasserts foreground and restarts the watchdog immediately`() = runTest {
        val port = FakePort()
        val owner = UsbExclusiveServiceKeepAliveOwner(backgroundScope, port)
        owner.update("started")
        runCurrent()

        owner.requestBackgroundForegroundReassert("app_hidden")
        runCurrent()

        assertTrue(port.reassertReasons.contains("usb_background_transition:app_hidden"))
        assertEquals(1, port.refreshCalls)
        owner.close()
    }

    @Test
    fun `background transition does not tick when foreground promotion fails`() = runTest {
        val port = FakePort().apply {
            serviceForeground = false
            ensureForegroundReady = false
        }
        val owner = UsbExclusiveServiceKeepAliveOwner(backgroundScope, port)
        owner.update("started")
        runCurrent()

        owner.requestBackgroundForegroundReassert("app_hidden")
        runCurrent()

        assertTrue(port.reassertReasons.isEmpty())
        assertEquals(0, port.refreshCalls)
        owner.close()
    }

    @Test
    fun `foreground promotion failure stops the current tick before native refresh`() = runTest {
        val port = FakePort().apply { ensureForegroundReady = false }
        val owner = UsbExclusiveServiceKeepAliveOwner(backgroundScope, port)
        owner.update("started")
        runCurrent()

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("usb_keepalive"), port.foregroundFailures)
        assertEquals(0, port.refreshCalls)
        owner.close()
    }

    @Test
    fun `foreground playback uses a five second interval and leaves the background anchor off`() = runTest {
        val port = FakePort().apply { foreground = true }
        val owner = UsbExclusiveServiceKeepAliveOwner(backgroundScope, port)
        owner.update("started")
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(0, port.refreshCalls)
        assertFalse(port.anchorStarts.contains("started"))

        owner.requestBackgroundForegroundReassert("still_visible")
        runCurrent()
        assertTrue(port.reassertReasons.isEmpty())

        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(1, port.refreshCalls)
        owner.close()
    }

    private class FakePort : UsbExclusiveKeepAlivePort {
        var foreground = false
        var active = true
        var serviceForeground = true
        var ensureForegroundReady = true
        var usbEnabled = true
        var transportRunning = true
        var nowMs = 1_000L
        var native = UsbExclusiveNativeState()
        var path = UsbExclusiveAudioPathState()
        var refreshCalls = 0
        var presentationCalls = 0
        val foregroundFailures = mutableListOf<String>()
        val anchorStarts = mutableListOf<String>()
        val anchorStops = mutableListOf<String>()
        val reassertReasons = mutableListOf<String>()
        val recoveries = mutableListOf<String>()

        override fun appInForeground(): Boolean = foreground
        override fun playbackActive(): Boolean = active
        override fun foregroundStarted(): Boolean = serviceForeground
        override fun reassertForeground(reason: String): Boolean {
            reassertReasons += reason
            return true
        }
        override fun ensureForeground(): Boolean = ensureForegroundReady
        override fun onForegroundFailure(reason: String) { foregroundFailures += reason }
        override fun startAnchor(reason: String) { anchorStarts += reason }
        override fun stopAnchor(reason: String) { anchorStops += reason }
        override fun refreshNative() { refreshCalls += 1 }
        override fun maintainWakeLock() = Unit
        override fun updatePlaybackPresentation() { presentationCalls += 1 }
        override fun nativeState(): UsbExclusiveNativeState = native
        override fun pathState(): UsbExclusiveAudioPathState = path
        override fun wakeLockHeld(): Boolean = true
        override fun anchorDiagnostic(): String = "held"
        override fun usbPlaybackEnabled(): Boolean = usbEnabled
        override fun transportActive(): Boolean = transportRunning
        override fun recover(reason: String) { recoveries += reason }
        override fun elapsedRealtime(): Long = nowMs++
    }
}
