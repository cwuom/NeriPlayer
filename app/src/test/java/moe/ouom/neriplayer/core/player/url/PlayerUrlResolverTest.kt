package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUrlResolverTest {

    @Test
    fun buildYouTubeOfflineCacheAudioInfo_usesPreferredQualityAndSource() {
        val audioInfo = buildYouTubeOfflineCacheAudioInfo("high") { it.toString() }

        assertEquals(PlaybackAudioSource.YOUTUBE_MUSIC, audioInfo.source)
        assertEquals("high", audioInfo.qualityKey)
        assertEquals(R.string.settings_audio_quality_high.toString(), audioInfo.qualityLabel)
        assertEquals(4, audioInfo.qualityOptions.size)
        assertTrue(audioInfo.mimeType.isNullOrBlank())
    }

    @Test
    fun buildYouTubeOfflineCacheAudioInfo_fallsBackWhenPreferredQualityBlank() {
        val audioInfo = buildYouTubeOfflineCacheAudioInfo("   ") { it.toString() }

        assertEquals("very_high", audioInfo.qualityKey)
        assertEquals(R.string.quality_very_high.toString(), audioInfo.qualityLabel)
    }
}
