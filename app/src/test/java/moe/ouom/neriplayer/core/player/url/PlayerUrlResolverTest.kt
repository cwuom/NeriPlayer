package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.resolver.netease.NeteasePlaybackResponseParser
import moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo
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

        assertEquals("high", audioInfo.qualityKey)
        assertEquals(R.string.settings_audio_quality_high.toString(), audioInfo.qualityLabel)
    }

    @Test
    fun buildNeteaseOfflineCacheAudioInfo_usesPreferredQualityAndSource() {
        val audioInfo = buildNeteaseOfflineCacheAudioInfo("lossless") { it.toString() }

        assertEquals(PlaybackAudioSource.NETEASE, audioInfo.source)
        assertEquals("lossless", audioInfo.qualityKey)
        assertEquals(R.string.quality_lossless.toString(), audioInfo.qualityLabel)
        assertEquals(8, audioInfo.qualityOptions.size)
        assertTrue(audioInfo.mimeType.isNullOrBlank())
    }

    @Test
    fun buildNeteaseOfflineCacheAudioInfo_fallsBackWhenPreferredQualityBlank() {
        val audioInfo = buildNeteaseOfflineCacheAudioInfo("   ") { it.toString() }

        assertEquals("exhigh", audioInfo.qualityKey)
        assertEquals(R.string.quality_very_high.toString(), audioInfo.qualityLabel)
    }

    @Test
    fun buildNeteasePlaybackAudioInfo_usesReportedLevelInsteadOfRequestedQuality() {
        val audioInfo = buildNeteasePlaybackAudioInfo(
            parsed = NeteasePlaybackResponseParser.PlaybackResult.Success(
                url = "https://m701.music.126.net/track.mp3",
                type = "mp3",
                level = "exhigh",
                bitrateKbps = 320
            ),
            resolvedQualityKey = "jymaster",
            fallbackDurationMs = 180_000L,
            getLocalizedString = { it.toString() }
        )

        assertEquals("exhigh", audioInfo.qualityKey)
        assertEquals(R.string.quality_very_high.toString(), audioInfo.qualityLabel)
        assertEquals("MP3", audioInfo.codecLabel)
        assertEquals(320, audioInfo.bitrateKbps)
    }

    @Test
    fun buildNeteasePlaybackAudioInfo_conservativelyLabelsUnknownMp3AsStandard() {
        val audioInfo = buildNeteasePlaybackAudioInfo(
            parsed = NeteasePlaybackResponseParser.PlaybackResult.Success(
                url = "https://m701.music.126.net/track.mp3",
                type = "mp3"
            ),
            resolvedQualityKey = "jymaster",
            fallbackDurationMs = 180_000L,
            getLocalizedString = { it.toString() }
        )

        assertEquals("standard", audioInfo.qualityKey)
        assertEquals(R.string.quality_standard.toString(), audioInfo.qualityLabel)
    }

    @Test
    fun buildNeteaseSuccessResult_exposesFlacMimeTypeToMedia3() {
        val result = buildNeteaseSuccessResult(
            parsed = NeteasePlaybackResponseParser.PlaybackResult.Success(
                url = "http://m801.music.126.net/track.flac",
                type = "flac",
                level = "lossless",
                bitrateKbps = 960
            ),
            resolvedQualityKey = "hires",
            fallbackDurationMs = 242_819L,
            getLocalizedString = { it.toString() }
        )

        assertEquals("https://m801.music.126.net/track.flac", result.url)
        assertEquals("audio/flac", result.mimeType)
        assertEquals("audio/flac", result.audioInfo?.mimeType)
    }

    @Test
    fun inferBiliQualityKey_detectsLosslessTagsAndFlacStreams() {
        val taggedLossless = BiliAudioStreamInfo(
            id = 30280,
            mimeType = "audio/mp4",
            bitrateKbps = 320,
            qualityTag = "lossless",
            url = "https://upos-sz-mirror.bilivideo.com/lossless.m4a"
        )
        val flac = BiliAudioStreamInfo(
            id = 30251,
            mimeType = "audio/flac; codecs=\"flac\"",
            bitrateKbps = 900,
            qualityTag = null,
            url = "https://upos-sz-mirror.bilivideo.com/lossless.flac"
        )

        assertEquals("lossless", inferBiliQualityKey(taggedLossless))
        assertEquals("lossless", inferBiliQualityKey(flac))
    }

    @Test
    fun shouldRetryNeteaseWithLowerQualityAfterLogin_onlyRetriesBeforeLastTier() {
        assertTrue(shouldRetryNeteaseWithLowerQualityAfterLogin(0, 3))
        assertTrue(shouldRetryNeteaseWithLowerQualityAfterLogin(1, 3))
        assertTrue(shouldRetryNeteaseWithLowerQualityAfterLogin(2, 3))
        assertTrue(!shouldRetryNeteaseWithLowerQualityAfterLogin(3, 3))
        assertTrue(!shouldRetryNeteaseWithLowerQualityAfterLogin(-1, 3))
    }
}
