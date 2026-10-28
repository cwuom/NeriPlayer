package moe.ouom.neriplayer.core.player.audio.focus

import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class StartupAudioFocusControllerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val shadowAudioManager: ShadowAudioManager = shadowOf(audioManager)

    @Before
    fun setUp() {
        StartupAudioFocusController.release("test_setup")
        shadowAudioManager.setIsMusicActive(false)
        shadowAudioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @After
    fun tearDown() {
        StartupAudioFocusController.release("test_teardown")
    }

    @Test
    fun `idle preemption leaves existing music playing without requesting focus`() {
        shadowAudioManager.setIsMusicActive(true)

        refreshForeground()

        assertNull(shadowAudioManager.lastAudioFocusRequest)
        assertTrue(audioManager.isMusicActive)
    }

    @Test
    fun `idle preemption still takes media focus when no music is playing`() {
        refreshForeground()

        assertEquals(AudioManager.AUDIOFOCUS_GAIN, shadowAudioManager.lastAudioFocusRequest.durationHint)
    }

    @Test
    fun `other active playback blocks preemption even without music stream activity`() {
        shadowAudioManager.setActivePlaybackConfigurationsFor(
            listOf(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        )

        refreshForeground()

        assertNull(shadowAudioManager.lastAudioFocusRequest)
    }

    @Test
    fun `active external playback releases an existing idle focus lease`() {
        refreshForeground()
        val firstRequest = shadowAudioManager.lastAudioFocusRequest
        shadowAudioManager.setIsMusicActive(true)

        refreshForeground()

        assertSame(firstRequest, shadowAudioManager.lastAudioFocusRequest)
        assertSame(firstRequest.audioFocusRequest, shadowAudioManager.lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `playback state query failure skips idle preemption`() {
        val unavailableAudioContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this

            override fun getSystemService(name: String): Any? {
                check(name != AUDIO_SERVICE) { "Audio service unavailable" }
                return super.getSystemService(name)
            }
        }

        refreshForeground(context = unavailableAudioContext)

        assertNull(shadowAudioManager.lastAudioFocusRequest)
    }

    @Test
    fun `foreground refresh does not reclaim focus after another player starts`() {
        refreshForeground()
        val firstRequest = shadowAudioManager.lastAudioFocusRequest
        firstRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        shadowAudioManager.setIsMusicActive(true)

        refreshForeground()

        assertSame(firstRequest, shadowAudioManager.lastAudioFocusRequest)
        assertNotNull(shadowAudioManager.lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `a later idle refresh can preempt after external music stops`() {
        shadowAudioManager.setIsMusicActive(true)
        refreshForeground()
        assertNull(shadowAudioManager.lastAudioFocusRequest)

        shadowAudioManager.setIsMusicActive(false)
        refreshForeground()

        assertNotNull(shadowAudioManager.lastAudioFocusRequest)
    }

    @Test
    fun `active player transport leaves focus to playback`() {
        refreshForeground(transportActive = true)

        assertNull(shadowAudioManager.lastAudioFocusRequest)
    }

    @Test
    fun `mixed playback never preempts focus`() {
        refreshForeground(allowMixedPlayback = true)

        assertNull(shadowAudioManager.lastAudioFocusRequest)
    }

    @Test
    fun `disabled preemption never requests focus`() {
        refreshForeground(enabled = false)

        assertNull(shadowAudioManager.lastAudioFocusRequest)
    }

    @Test
    fun `active USB exclusive playback still acquires its focus guard`() {
        shadowAudioManager.setIsMusicActive(true)

        refreshForeground(usbExclusiveNativeActive = true, transportActive = true)

        assertNotNull(shadowAudioManager.lastAudioFocusRequest)
    }

    @Test
    fun `starting USB transport still requests playback focus while music is active`() {
        shadowAudioManager.setIsMusicActive(true)

        assertTrue(
            StartupAudioFocusController.acquireUsbExclusiveTransportFocus(
                context = context,
                enabled = true,
                reason = "test_playback"
            )
        )

        assertNotNull(shadowAudioManager.lastAudioFocusRequest)
    }

    private fun refreshForeground(
        context: Context = this.context,
        enabled: Boolean = true,
        allowMixedPlayback: Boolean = false,
        usbExclusiveNativeActive: Boolean = false,
        transportActive: Boolean = false
    ) {
        StartupAudioFocusController.updateForForeground(
            context = context,
            enabled = enabled,
            allowMixedPlayback = allowMixedPlayback,
            usbExclusivePlayback = usbExclusiveNativeActive,
            usbExclusiveNativeActive = usbExclusiveNativeActive,
            transportActive = transportActive,
            reason = "test_foreground"
        )
    }
}
