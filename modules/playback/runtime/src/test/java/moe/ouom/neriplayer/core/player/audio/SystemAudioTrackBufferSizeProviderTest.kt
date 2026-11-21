@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.audio

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemAudioTrackBufferSizeProviderTest {
    @Test
    fun `high rate pcm keeps enough output data for a short feed gap`() {
        for (sampleRate in listOf(176_400, 192_000)) {
            for (frameSize in listOf(4, 8)) {
                val platformMinimum = sampleRate * frameSize * 80 / 1_000
                val bufferBytes = SystemAudioTrackBufferSizeProvider.getBufferSizeInBytes(
                    platformMinimum,
                    if (frameSize == 4) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_FLOAT,
                    DefaultAudioSink.OUTPUT_MODE_PCM,
                    frameSize,
                    sampleRate,
                    C.RATE_UNSET_INT,
                    1.0
                )

                assertEquals(sampleRate * frameSize * 2, bufferBytes)
            }
        }
    }

    @Test
    fun `large pcm frames stay within the additional buffer budget and retain whole frames`() {
        val frameSize = 24
        val bufferBytes = SystemAudioTrackBufferSizeProvider.getBufferSizeInBytes(
            32_768, C.ENCODING_PCM_FLOAT, DefaultAudioSink.OUTPUT_MODE_PCM,
            frameSize, 384_000, C.RATE_UNSET_INT, 1.0
        )

        assertEquals(4_194_288, bufferBytes)
        assertEquals(0, bufferBytes % frameSize)
    }

    @Test
    fun `device minimum is honored when larger than the additional buffer budget`() {
        val bufferBytes = SystemAudioTrackBufferSizeProvider.getBufferSizeInBytes(
            4_500_000, C.ENCODING_PCM_FLOAT, DefaultAudioSink.OUTPUT_MODE_PCM,
            8, 192_000, C.RATE_UNSET_INT, 1.0
        )

        assertEquals(4_500_000, bufferBytes)
    }

    @Test
    fun `normal pcm and encoded output retain default sizing`() {
        val defaultProvider = DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT
        for (sampleRate in listOf(44_100, 96_000)) {
            assertEquals(
                defaultProvider.getBufferSizeInBytes(
                    14_144, C.ENCODING_PCM_16BIT, DefaultAudioSink.OUTPUT_MODE_PCM,
                    4, sampleRate, C.RATE_UNSET_INT, 1.0
                ),
                SystemAudioTrackBufferSizeProvider.getBufferSizeInBytes(
                    14_144, C.ENCODING_PCM_16BIT, DefaultAudioSink.OUTPUT_MODE_PCM,
                    4, sampleRate, C.RATE_UNSET_INT, 1.0
                )
            )
        }
        assertEquals(
            defaultProvider.getBufferSizeInBytes(
                14_144, C.ENCODING_AC3, DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH,
                1, 192_000, C.RATE_UNSET_INT, 1.0
            ),
            SystemAudioTrackBufferSizeProvider.getBufferSizeInBytes(
                14_144, C.ENCODING_AC3, DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH,
                1, 192_000, C.RATE_UNSET_INT, 1.0
            )
        )
    }
}
