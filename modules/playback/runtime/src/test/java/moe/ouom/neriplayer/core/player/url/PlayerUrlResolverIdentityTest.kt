package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.bilibili.playback.BiliAudioStreamInfo
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayableAudio
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayableStreamType
import moe.ouom.neriplayer.platform.netease.api.playback.parser.NeteasePlaybackResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUrlResolverIdentityTest {
    private val localized: (Int) -> String = { "res:$it" }

    @Test
    fun `netease quality labels are localized for every known level`() {
        mapOf(
            "standard" to CoreCommonR.string.quality_standard,
            "higher" to CoreCommonR.string.settings_audio_quality_higher,
            "exhigh" to CoreCommonR.string.quality_very_high,
            "lossless" to CoreCommonR.string.quality_lossless,
            "hires" to CoreCommonR.string.quality_hires,
            "jyeffect" to CoreCommonR.string.quality_hd_surround,
            "sky" to CoreCommonR.string.quality_surround,
            "jymaster" to CoreCommonR.string.settings_audio_quality_jymaster
        ).forEach { (key, resId) ->
            assertEquals(key, "res:$resId", qualityLabelForNetease(key, localized))
        }
        assertEquals("dolby", qualityLabelForNetease("dolby", localized))
    }

    @Test
    fun `netease audio info falls back to raw type and estimated bitrate`() {
        val flac = buildNeteasePlaybackAudioInfo(
            parsed = success(type = "flac", bitrateKbps = null, contentLength = 2_400_000L),
            resolvedQualityKey = "lossless",
            fallbackDurationMs = 60_000L,
            getLocalizedString = localized
        )
        val blankType = buildNeteasePlaybackAudioInfo(
            parsed = success(type = "  ", bitrateKbps = 128),
            resolvedQualityKey = "standard",
            fallbackDurationMs = 60_000L,
            getLocalizedString = localized
        )
        val noType = buildNeteasePlaybackAudioInfo(
            parsed = success(type = null, bitrateKbps = 128),
            resolvedQualityKey = "standard",
            fallbackDurationMs = 60_000L,
            getLocalizedString = localized
        )
        val preview = buildNeteasePlaybackAudioInfo(
            parsed = success(type = "mp3", bitrateKbps = 320, notice = NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP),
            resolvedQualityKey = "exhigh",
            fallbackDurationMs = 60_000L,
            getLocalizedString = localized
        )

        assertEquals("FLAC", flac.codecLabel)
        assertEquals("audio/flac", flac.mimeType)
        assertEquals(320, flac.bitrateKbps)
        assertEquals("  ", blankType.codecLabel)
        assertNull(blankType.mimeType)
        assertNull(noType.codecLabel)
        assertEquals(128, noType.bitrateKbps)
        assertEquals("MP3", preview.codecLabel)
        assertNull(preview.bitrateKbps)
    }

    @Test
    fun `netease success upgrades http and flags preview clips`() {
        val preview = buildNeteaseSuccessResult(
            parsed = success(
                url = "http://m701.music.126.net/clip.mp3",
                type = "mp3",
                bitrateKbps = 128,
                notice = NeteasePlaybackResponseParser.Notice.PREVIEW_CLIP
            ),
            resolvedQualityKey = "standard",
            fallbackDurationMs = 30_000L,
            getLocalizedString = localized
        )
        val full = buildNeteaseSuccessResult(
            parsed = success(url = "https://m701.music.126.net/full.flac", type = "flac", bitrateKbps = 900),
            resolvedQualityKey = "lossless",
            fallbackDurationMs = 30_000L,
            getLocalizedString = localized
        )

        assertEquals("https://m701.music.126.net/clip.mp3", preview.url)
        assertEquals("res:${CoreCommonR.string.player_netease_preview_only}", preview.noticeMessage)
        assertTrue(preview.isPreviewClip)
        assertEquals("audio/mpeg", preview.mimeType)
        assertEquals("https://m701.music.126.net/full.flac", full.url)
        assertNull(full.noticeMessage)
        assertFalse(full.isPreviewClip)
        assertEquals("lossless", full.audioInfo?.qualityKey)
    }

    @Test
    fun `netease quality candidates walk down from the preferred level`() {
        assertEquals(listOf("exhigh", "higher", "standard"), buildNeteaseQualityCandidates("  "))
        assertEquals(
            listOf("lossless", "exhigh", "higher", "standard"),
            buildNeteaseQualityCandidates(" Lossless ")
        )
        assertEquals(listOf("standard"), buildNeteaseQualityCandidates("standard"))
        assertEquals(listOf("dolby", "exhigh", "standard"), buildNeteaseQualityCandidates("dolby"))
    }

    @Test
    fun `only missing permission or url failures retry with lower quality`() {
        assertTrue(shouldRetryNeteaseWithLowerQuality(NeteasePlaybackResponseParser.FailureReason.NO_PERMISSION))
        assertTrue(shouldRetryNeteaseWithLowerQuality(NeteasePlaybackResponseParser.FailureReason.NO_PLAY_URL))
        assertFalse(shouldRetryNeteaseWithLowerQuality(NeteasePlaybackResponseParser.FailureReason.UNKNOWN))
    }

    @Test
    fun `bili representation identity normalizes optional stream fields`() {
        assertEquals(
            "30280|hires|audio/flac|1411",
            buildBiliRepresentationIdentity(
                BiliAudioStreamInfo(
                    id = 30280,
                    mimeType = " Audio/FLAC ",
                    bitrateKbps = 1411,
                    qualityTag = " HiRes ",
                    url = "https://upos.bilivideo.com/a.flac"
                )
            )
        )
        assertEquals(
            "||audio/mp4|128",
            buildBiliRepresentationIdentity(
                BiliAudioStreamInfo(
                    id = null,
                    mimeType = "audio/mp4",
                    bitrateKbps = 128,
                    qualityTag = null,
                    url = "https://upos.bilivideo.com/a.m4a"
                )
            )
        )
    }

    @Test
    fun `youtube representation identity extracts the itag from query or path`() {
        fun itag(url: String) = buildYouTubeRepresentationIdentity(YouTubePlayableAudio(url = url))
            .substringBefore('|')

        assertEquals("140", itag("https://rr1.googlevideo.com/videoplayback?itag=140&mime=audio%2Fmp4"))
        assertEquals("251", itag("https://rr1.googlevideo.com/videoplayback?itag=140&xtags=a&itag=251"))
        assertEquals("18", itag("https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fitag%3D18"))
        assertEquals("22", itag("https://rr1.googlevideo.com/videoplayback?itag=22&sig=%zz"))
        assertEquals("234", itag("https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/234/source/yt"))
        assertEquals("", itag("https://rr1.googlevideo.com/videoplayback?id=1"))
    }

    @Test
    fun `youtube representation identity keeps format fields in a stable order`() {
        assertEquals(
            "251|audio/webm; codecs=\"opus\"|160|48000|HLS",
            buildYouTubeRepresentationIdentity(
                YouTubePlayableAudio(
                    url = "https://rr1.googlevideo.com/videoplayback?itag=251",
                    mimeType = " Audio/WebM; codecs=\"opus\" ",
                    streamType = YouTubePlayableStreamType.HLS,
                    bitrateKbps = 160,
                    sampleRateHz = 48_000
                )
            )
        )
        assertEquals(
            "||||DIRECT",
            buildYouTubeRepresentationIdentity(YouTubePlayableAudio(url = "https://example.com/audio"))
        )
    }

    private fun success(
        url: String = "https://m701.music.126.net/track",
        type: String?,
        bitrateKbps: Int?,
        contentLength: Long? = null,
        notice: NeteasePlaybackResponseParser.Notice? = null
    ) = NeteasePlaybackResponseParser.PlaybackResult.Success(
        url = url,
        type = type,
        bitrateKbps = bitrateKbps,
        contentLength = contentLength,
        notice = notice
    )
}
