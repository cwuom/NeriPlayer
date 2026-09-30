@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.engine

import androidx.media3.common.MimeTypes
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FfmpegFlacDecoderAvailabilityTest {

    @Test
    fun bundledFfmpegCanDecodeFlacOnDevice() {
        assertTrue("FFmpeg native library must load", FfmpegLibrary.isAvailable())
        assertTrue(
            "FFmpeg native library must support FLAC",
            FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_FLAC)
        )
    }
}
