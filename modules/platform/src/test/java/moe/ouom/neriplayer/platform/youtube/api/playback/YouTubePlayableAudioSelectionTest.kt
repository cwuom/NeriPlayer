package moe.ouom.neriplayer.platform.youtube.api.playback

import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayableAudio
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayableStreamType
import moe.ouom.neriplayer.data.model.youtube.playback.YouTubePlayerClientProfile
import moe.ouom.neriplayer.platform.youtube.api.protocol.YOUTUBE_PLAYER_ANDROID_MUSIC_CLIENT_NAME
import moe.ouom.neriplayer.platform.youtube.api.protocol.YOUTUBE_PLAYER_ANDROID_VR_CLIENT_NAME
import moe.ouom.neriplayer.platform.youtube.api.protocol.YOUTUBE_PLAYER_TV_CLIENT_NAME
import moe.ouom.neriplayer.platform.youtube.api.protocol.YOUTUBE_PLAYER_VISIONOS_CLIENT_NAME
import moe.ouom.neriplayer.platform.youtube.api.protocol.YOUTUBE_PLAYER_WEB_CREATOR_CLIENT_NAME
import moe.ouom.neriplayer.platform.youtube.api.protocol.YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlayableAudioSelectionTest {
    @Test
    fun `quality comparisons use bitrate sample rate mime length and duration in order`() {
        val base = audio("base")
        val strongerBitrate = base.copy(url = "bitrate", bitrateKbps = 161)
        val strongerSampleRate = base.copy(url = "sample", sampleRateHz = 48_000)
        val strongerMime = base.copy(url = "mime", mimeType = "audio/mp4")
        val strongerLength = base.copy(url = "length", contentLength = 1_001)
        val strongerDuration = base.copy(url = "duration", durationMs = 2_001)
        listOf(strongerBitrate, strongerSampleRate, strongerMime, strongerLength, strongerDuration)
            .forEach { better ->
                assertSame(better, YouTubePlayableAudioSelection.selectPreferred(base, better))
                assertSame(better, YouTubePlayableAudioSelection.selectPreferred(better, base))
            }
        assertSame(base, YouTubePlayableAudioSelection.selectPreferred(base, base.copy(url = "equal")))
    }

    @Test
    fun `equal quality uses client priority for direct and HLS separately`() {
        val direct = audio("direct")
        val hls = direct.copy(streamType = YouTubePlayableStreamType.HLS)
        val priorities = listOf(
            YOUTUBE_PLAYER_VISIONOS_CLIENT_NAME,
            YOUTUBE_PLAYER_ANDROID_VR_CLIENT_NAME,
            YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME,
            YOUTUBE_PLAYER_TV_CLIENT_NAME,
            YOUTUBE_PLAYER_WEB_CREATOR_CLIENT_NAME,
            YOUTUBE_PLAYER_ANDROID_MUSIC_CLIENT_NAME,
            "UNKNOWN",
            ""
        )
        priorities.zipWithNext().forEach { (higher, lower) ->
            assertSame(
                direct,
                YouTubePlayableAudioSelection.selectPreferred(
                    direct, direct.copy(url = "lower"), higher, lower
                )
            )
        }
        assertSame(
            hls,
            YouTubePlayableAudioSelection.selectPreferred(
                hls, hls.copy(url = "lower"),
                YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME,
                YOUTUBE_PLAYER_TV_CLIENT_NAME
            )
        )
        assertSame(
            direct,
            YouTubePlayableAudioSelection.selectPreferred(
                direct.copy(url = "lower"), direct,
                "TVHTML5_DOWNGRADED", YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME
            )
        )
    }

    @Test
    fun `first accepted direct can return only when quality and client permit`() {
        val direct = audio("direct")
        val webRemix = profile(YOUTUBE_PLAYER_WEB_REMIX_CLIENT_NAME)
        assertTrue(YouTubePlayableAudioSelection.shouldReturnImmediately(webRemix, direct, true, "high", false))
        assertFalse(YouTubePlayableAudioSelection.shouldReturnImmediately(webRemix, direct, false, "high", false))
        assertFalse(YouTubePlayableAudioSelection.shouldReturnImmediately(webRemix, direct.copy(streamType = YouTubePlayableStreamType.HLS), true, "high", false))
        assertFalse(YouTubePlayableAudioSelection.shouldReturnImmediately(webRemix, direct.copy(bitrateKbps = 96), true, "high", false))
        assertTrue(YouTubePlayableAudioSelection.shouldReturnImmediately(webRemix, direct.copy(bitrateKbps = 96, mimeType = "audio/mp4"), true, "high", true))
        assertFalse(YouTubePlayableAudioSelection.shouldReturnImmediately(profile("UNKNOWN"), direct, true, "high", false))
    }

    @Test
    fun `strict recovery accepts non Google streams and token bearing web clients`() {
        assertTrue(isTrustedYouTubeDirectUrlForStrictRecovery("https://example.org/audio.mp4"))
        val stream = "https://rr1---sn.googlevideo.com/videoplayback?source=youtube"
        assertFalse(isTrustedYouTubeDirectUrlForStrictRecovery(stream))
        assertFalse(isTrustedYouTubeDirectUrlForStrictRecovery("$stream&c=WEB_REMIX&pot="))
        assertTrue(isTrustedYouTubeDirectUrlForStrictRecovery("$stream&c=web_remix&pot=token"))
        assertFalse(isTrustedYouTubeDirectUrlForStrictRecovery("$stream&c=UNKNOWN&pot=token"))
    }

    private fun audio(url: String) = YouTubePlayableAudio(
        url = url,
        mimeType = "audio/webm",
        bitrateKbps = 160,
        sampleRateHz = 44_100,
        contentLength = 1_000,
        durationMs = 2_000
    )

    private fun profile(name: String) = YouTubePlayerClientProfile(
        clientId = "test",
        clientName = name,
        clientVersion = "1",
        userAgent = "test",
        endpointPath = "/youtubei/v1/player"
    )
}
