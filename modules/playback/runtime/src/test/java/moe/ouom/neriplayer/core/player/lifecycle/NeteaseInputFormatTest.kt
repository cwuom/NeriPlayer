package moe.ouom.neriplayer.core.player.lifecycle

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.extractor.FlacStreamMetadata
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.testing.FakeExoPlayer
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NeteaseInputFormatTest {
    private val info = PlaybackAudioInfo(PlaybackAudioSource.NETEASE, qualityKey = "hires", bitrateKbps = 3129)

    @Test
    fun `FLAC stream info fills the existing specification label`() {
        // 取自测试音频 synthetic-hires-raw.flac 的 STREAMINFO。
        val header = "664c6143000000220400040000001000001217700370000177000dd0e989e9556334ac746c39d72d6aea".hexToByteArray()
        val input = FlacStreamMetadata(header, 8).getFormat(header, null)
        val detected = info.withNeteaseInputFormat(input)
        assertEquals("24 bit · 96 kHz · 3129 kbps", detected.specLabel)
        assertEquals(info.qualityKey, detected.qualityKey)
    }

    @Test
    fun `lossy input reports its sampling rate and clears the previous lossless depth`() {
        val previous = info.copy(bitDepth = 24, sampleRateHz = 96000)
        for (mime in listOf(MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_AAC)) {
            val input = Format.Builder().setSampleMimeType(mime).setSampleRate(44100)
                .setPcmEncoding(C.ENCODING_PCM_16BIT).build()
            assertEquals("44.1 kHz · 3129 kbps", previous.withNeteaseInputFormat(input).specLabel)
        }
    }

    @Test
    fun `ALAC preserves twenty valid bits even without a matching PCM encoding`() {
        val config = "000010000014280a0e0200ff00000000000000000000ac44".hexToByteArray()
        val input = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_ALAC)
            .setInitializationData(listOf(config)).build()
        assertEquals("20 bit · 44.1 kHz · 3129 kbps", info.withNeteaseInputFormat(input).specLabel)
    }

    @Test
    fun `incomplete headers leave unknown fields out`() {
        for (mime in listOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC)) {
            val input = Format.Builder().setSampleMimeType(mime).setInitializationData(listOf(byteArrayOf(0))).build()
            assertEquals("3129 kbps", info.withNeteaseInputFormat(input).specLabel)
        }
    }

    @Test
    fun `a late format callback cannot change the next stream's label`() {
        val manager = PlayerManager
        val url = "https://example.org/current.flac"
        val fake = FakeExoPlayer().apply { mediaItem = MediaItem.fromUri(url) }
        val timeline = mock(Timeline::class.java)
        val current = MediaPeriodId("current")
        val previous = MediaPeriodId("previous")
        `when`(fake.player.currentTimeline).thenReturn(timeline)
        `when`(timeline.getUidOfPeriod(0)).thenReturn(current.periodUid)
        fun event(period: MediaPeriodId) = EventTime(0, timeline, 0, period, 0, timeline, 0, period, 0, 0)
        val input = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setPcmEncoding(C.ENCODING_PCM_24BIT).setSampleRate(96000).build()
        val savedInfo = manager._currentPlaybackAudioInfo.value
        val savedUrl = manager._currentMediaUrl.value
        val savedToken = manager.loadedMediaRequestToken
        fake.installInto(manager) {
            try {
                manager.loadedMediaRequestToken = manager.playbackRequestToken
                manager._currentMediaUrl.value = url
                manager._currentPlaybackAudioInfo.value = info
                manager.updateNeteaseInputFormat(event(previous), input)
                assertEquals(info, manager._currentPlaybackAudioInfo.value)
                manager.updateNeteaseInputFormat(event(current), input)
                assertEquals("24 bit · 96 kHz · 3129 kbps", manager._currentPlaybackAudioInfo.value?.specLabel)
            } finally {
                manager._currentPlaybackAudioInfo.value = savedInfo
                manager._currentMediaUrl.value = savedUrl
                manager.loadedMediaRequestToken = savedToken
            }
        }
    }
}
