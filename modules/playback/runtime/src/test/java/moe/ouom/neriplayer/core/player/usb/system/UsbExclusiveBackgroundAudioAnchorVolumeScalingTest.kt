package moe.ouom.neriplayer.core.player.usb.system

import android.content.Context
import android.media.AudioManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class UsbExclusiveBackgroundAudioAnchorVolumeScalingTest {

    private val acquired = mutableListOf<UsbExclusiveBackgroundAudioAnchorVolumeGuardToken>()

    @After
    fun releaseAnchors() {
        acquired.forEach(UsbExclusiveBackgroundAudioAnchorVolumeGuard::release)
    }

    @Test
    fun `user changes need an anchor and a stable route before they rescale`() {
        val state = UsbExclusiveBackgroundAudioAnchorVolumeGuardState()
        assertNull(state.applyUserVolumeChange(0.5f))

        state.acquire(0.6f)
        state.observeRouteVolume(0.5f)

        assertEquals(0.6f, state.applyUserVolumeChange(0.1f)!!, 0f)
        assertEquals(0.6f, state.currentVolumeFractionOrNull()!!, 0f)
    }

    @Test
    fun `stable routes rescale the anchor towards silence or full volume`() {
        val state = stableRoute(anchor = 0.6f, route = 0.5f)

        assertEquals(0.3f, state.applyUserVolumeChange(0.25f)!!, EPSILON)
        assertEquals(0.65f, state.applyUserVolumeChange(0.625f)!!, EPSILON)
        assertEquals(0.65f, state.currentVolumeFractionOrNull()!!, EPSILON)
    }

    @Test
    fun `muted and saturated routes clamp instead of dividing by their range`() {
        assertEquals(0f, stableRoute(anchor = 0.6f, route = 0f).applyUserVolumeChange(0f)!!, 0f)
        assertEquals(1f, stableRoute(anchor = 0.6f, route = 0.99995f).applyUserVolumeChange(1f)!!, 0f)
    }

    @Test
    fun `anchors capture the music stream position within its volume range`() {
        val token = UsbExclusiveBackgroundAudioAnchorVolumeGuard.acquire(
            context(audioManager(min = 1, max = 11, current = 5))
        )
        acquired += token!!

        assertEquals(0.4f, UsbExclusiveBackgroundAudioAnchorVolumeGuard.currentVolumeFractionOrNull()!!, EPSILON)
    }

    @Test
    fun `anchors are not taken without a readable music volume range`() {
        val denied = audioManager(min = 0, max = 15, current = 3).also {
            `when`(it.getStreamVolume(AudioManager.STREAM_MUSIC)).thenThrow(SecurityException("denied"))
        }

        listOf(
            context(audioService = null),
            context(audioManager(min = 4, max = 4, current = 4)),
            context(denied)
        ).forEach { assertNull(UsbExclusiveBackgroundAudioAnchorVolumeGuard.acquire(it)) }
    }

    private fun stableRoute(anchor: Float, route: Float) =
        UsbExclusiveBackgroundAudioAnchorVolumeGuardState().apply {
            acquire(anchor)
            observeRouteVolume(route)
            observeRouteVolume(route)
        }

    private fun audioManager(min: Int, max: Int, current: Int): AudioManager =
        mock(AudioManager::class.java).also {
            `when`(it.getStreamMinVolume(AudioManager.STREAM_MUSIC)).thenReturn(min)
            `when`(it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).thenReturn(max)
            `when`(it.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(current)
        }

    private fun context(audioService: Any?): Context {
        val application = mock(Context::class.java)
        `when`(application.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioService)
        return mock(Context::class.java).also { `when`(it.applicationContext).thenReturn(application) }
    }

    private companion object {
        const val EPSILON = 0.0001f
    }
}
