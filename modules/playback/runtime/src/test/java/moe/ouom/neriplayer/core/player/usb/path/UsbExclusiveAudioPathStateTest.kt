package moe.ouom.neriplayer.core.player.usb.path

import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveAudioPathState

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveAudioPathStateTest {

    @After
    fun resetTracker() {
        UsbExclusiveAudioPathTracker.updateRequested(enabled = false)
        UsbExclusiveAudioPathTracker.clearForcedSystemFallback()
        UsbExclusiveAudioPathTracker.updateConfigured(
            usingNative = false,
            fallbackReason = null,
            inputFormat = "none"
        )
        UsbExclusiveAudioPathTracker.updatePlaybackParameters(speed = 1f, pitch = 1f)
        UsbExclusiveAudioPathTracker.updateSkipSilence(enabled = false)
        UsbExclusiveAudioPathTracker.updatePlaying(playing = false, usingNative = false)
        UsbExclusiveAudioPathTracker.updateVolume(volume = 1f)
    }

    @Test
    fun `default path state describes an idle system sink`() {
        val state = UsbExclusiveAudioPathState()

        assertEquals(UsbExclusiveAudioPathState.REQUESTED_SYSTEM, state.requestedPath)
        assertEquals(UsbExclusiveAudioPathState.EFFECTIVE_SYSTEM, state.effectivePath)
        assertNull(state.fallbackReason)
        assertEquals("none", state.inputFormat)
        assertFalse(state.sinkPlaying)
        assertFalse(state.nativePaused)
        assertEquals(1f, state.requestedVolume, 0f)
    }

    @Test
    fun `tracker reflects native configure pause and resume transitions`() {
        UsbExclusiveAudioPathTracker.updateRequested(enabled = true)
        val generationAfterRequest = UsbExclusiveAudioPathTracker.state.value.generation

        UsbExclusiveAudioPathTracker.updateConfigured(
            usingNative = true,
            fallbackReason = null,
            inputFormat = "48000 Hz 2ch 16-bit"
        )

        val pausedState = UsbExclusiveAudioPathTracker.state.value
        assertEquals(UsbExclusiveAudioPathState.REQUESTED_NATIVE_USB, pausedState.requestedPath)
        assertEquals(UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB, pausedState.effectivePath)
        assertEquals("48000 Hz 2ch 16-bit", pausedState.inputFormat)
        assertFalse(pausedState.sinkPlaying)
        assertTrue(pausedState.nativePaused)
        assertEquals(generationAfterRequest + 1L, pausedState.generation)

        UsbExclusiveAudioPathTracker.updatePlaying(playing = true, usingNative = true)
        val playingState = UsbExclusiveAudioPathTracker.state.value
        assertTrue(playingState.sinkPlaying)
        assertFalse(playingState.nativePaused)

        UsbExclusiveAudioPathTracker.updateNativePaused(paused = true, sinkPlaying = false)
        val pausedAgainState = UsbExclusiveAudioPathTracker.state.value
        assertFalse(pausedAgainState.sinkPlaying)
        assertTrue(pausedAgainState.nativePaused)
    }

    @Test
    fun `tracker records compatibility and control diagnostics`() {
        UsbExclusiveAudioPathTracker.forceSystemFallback("equalizer_requires_system_processor")
        UsbExclusiveAudioPathTracker.updateConfigured(
            usingNative = false,
            fallbackReason = UsbExclusiveAudioPathTracker.forcedSystemFallbackReason(),
            inputFormat = "44100 Hz 2ch 24-bit"
        )
        UsbExclusiveAudioPathTracker.updatePlaybackParameters(speed = 1.25f, pitch = 0.95f)
        UsbExclusiveAudioPathTracker.updateSkipSilence(enabled = true)
        UsbExclusiveAudioPathTracker.updateVolume(volume = 0.35f)

        val state = UsbExclusiveAudioPathTracker.state.value
        assertEquals(UsbExclusiveAudioPathState.EFFECTIVE_SYSTEM, state.effectivePath)
        assertEquals("equalizer_requires_system_processor", state.fallbackReason)
        assertEquals("speed=1.25 pitch=0.95", state.requestedPlaybackParameters)
        assertTrue(state.skipSilence)
        assertEquals(0.35f, state.requestedVolume, 0f)
    }

    @Test
    fun `service comparison ignores bookkeeping generation but keeps path changes`() {
        val first = UsbExclusiveAudioPathState(generation = 4L)
        val samePath = first.copy(generation = 9L)
        val changedPath = first.copy(
            effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB,
            generation = 10L
        )

        assertTrue(sameUsbExclusiveAudioPathConfiguration(first, samePath))
        assertFalse(sameUsbExclusiveAudioPathConfiguration(first, changedPath))
    }

    @Test
    fun `system volume ramps do not repeatedly refresh the service before USB route changes`() = runTest {
        val initial = UsbExclusiveAudioPathState(requestedVolume = 0f)
        val fade = (1..24).map { step -> initial.copy(requestedVolume = step / 24f) }
        val requested = fade.last().copy(requestedPath = UsbExclusiveAudioPathState.REQUESTED_NATIVE_USB)
        val native = requested.copy(effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_NATIVE_USB)
        val playing = native.copy(sinkPlaying = true)
        val paused = playing.copy(sinkPlaying = false, nativePaused = true)
        val fallback = paused.copy(
            effectivePath = UsbExclusiveAudioPathState.EFFECTIVE_SYSTEM,
            fallbackReason = "device_disconnected"
        )
        val routeChanges = listOf(requested, native, playing, paused, fallback)

        val observed = (listOf(initial) + fade + routeChanges).asFlow()
            .distinctUntilChanged(::sameUsbExclusiveAudioPathConfiguration)
            .toList()

        assertEquals(listOf(initial) + routeChanges, observed)
    }
}
