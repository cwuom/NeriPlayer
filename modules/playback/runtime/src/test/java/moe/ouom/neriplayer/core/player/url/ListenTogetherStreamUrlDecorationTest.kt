package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioInfo
import moe.ouom.neriplayer.data.model.playback.PlaybackAudioSource
import moe.ouom.neriplayer.data.model.playback.PlaybackUrlCandidate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ListenTogetherStreamUrlDecorationTest {
    private val localized: (Int) -> String = { "res:$it" }
    private val streamUrl = "https://m701.music.126.net/track.flac"
    private val song = SongItem(
        id = 42L,
        name = "song",
        artist = "artist",
        album = "NeteaseAlbum",
        albumId = 1L,
        durationMs = 60_000L,
        coverUrl = null,
        channelId = ListenTogetherChannels.NETEASE,
        audioId = "42"
    )

    private var previousMediaUrl: String? = null
    private var previousCandidates: List<PlaybackUrlCandidate> = emptyList()
    private var previousIndex = 0
    private var previousNeteaseQuality = ""
    private var previousYouTubeQuality = ""
    private var previousBiliQuality = ""

    @Before
    fun rememberPlayerState() {
        previousMediaUrl = PlayerManager._currentMediaUrl.value
        previousCandidates = PlayerManager.activePlaybackCandidates
        previousIndex = PlayerManager.activePlaybackUrlIndex
        previousNeteaseQuality = PlayerManager.preferredQuality
        previousYouTubeQuality = PlayerManager.youtubePreferredQuality
        previousBiliQuality = PlayerManager.biliPreferredQuality
    }

    @After
    fun restorePlayerState() {
        PlayerManager._currentMediaUrl.value = previousMediaUrl
        PlayerManager.activePlaybackCandidates = previousCandidates
        PlayerManager.activePlaybackUrlIndex = previousIndex
        PlayerManager.preferredQuality = previousNeteaseQuality
        PlayerManager.youtubePreferredQuality = previousYouTubeQuality
        PlayerManager.biliPreferredQuality = previousBiliQuality
    }

    @Test
    fun `stream urls are tagged with one normalized quality fragment`() {
        assertEquals(streamUrl, decorateListenTogetherStreamUrl(streamUrl, PlaybackAudioSource.NETEASE, "dolby"))
        assertEquals(
            "$streamUrl#neriplayer-ltw-quality=netease:lossless",
            decorateListenTogetherStreamUrl(streamUrl, PlaybackAudioSource.NETEASE, " Lossless ")
        )
        assertEquals(
            "https://upos.bilivideo.com/a.m4a#t=5&neriplayer-ltw-quality=bili:high",
            decorateListenTogetherStreamUrl(
                "https://upos.bilivideo.com/a.m4a#t=5&neriplayer-ltw-quality=bili:low",
                PlaybackAudioSource.BILIBILI,
                "high"
            )
        )
    }

    @Test
    fun `stripping quality metadata keeps unrelated fragments`() {
        assertEquals(streamUrl, stripListenTogetherStreamQualityMetadata(streamUrl))
        assertEquals("$streamUrl#t=5", stripListenTogetherStreamQualityMetadata("$streamUrl#t=5"))
        assertEquals(
            "$streamUrl#t=5",
            stripListenTogetherStreamQualityMetadata("$streamUrl#&t=5&neriplayer-ltw-quality=youtube:high")
        )
    }

    @Test
    fun `fallback audio info uses source specific quality options`() {
        val netease = buildListenTogetherFallbackAudioInfo(PlaybackAudioSource.NETEASE, "lossless", localized)
        val youtube = buildListenTogetherFallbackAudioInfo(PlaybackAudioSource.YOUTUBE_MUSIC, "very_high", localized)
        val biliDefault = buildListenTogetherFallbackAudioInfo(PlaybackAudioSource.BILIBILI, "  ", localized)
        val biliMedium = buildListenTogetherFallbackAudioInfo(PlaybackAudioSource.BILIBILI, " Medium ", localized)

        assertEquals(PlaybackAudioSource.NETEASE, netease.source)
        assertEquals("lossless", netease.qualityKey)
        assertEquals(8, netease.qualityOptions.size)
        assertEquals("very_high", youtube.qualityKey)
        assertEquals(4, youtube.qualityOptions.size)
        assertEquals("high", biliDefault.qualityKey)
        assertEquals("res:${CoreCommonR.string.settings_audio_quality_high}", biliDefault.qualityLabel)
        assertEquals(
            listOf("dolby", "hires", "lossless", "high", "medium", "low"),
            biliDefault.qualityOptions.map { it.key }
        )
        assertEquals("medium", biliMedium.qualityKey)
        assertEquals(
            PlaybackAudioInfo(source = PlaybackAudioSource.LOCAL),
            buildListenTogetherFallbackAudioInfo(PlaybackAudioSource.LOCAL, "high", localized)
        )
    }

    @Test
    fun `listen together candidates fall back to existing audio info`() {
        val candidateInfo = PlaybackAudioInfo(source = PlaybackAudioSource.NETEASE, qualityKey = "lossless")
        val resolvedInfo = PlaybackAudioInfo(source = PlaybackAudioSource.NETEASE, qualityKey = "exhigh")
        val existingInfo = PlaybackAudioInfo(source = PlaybackAudioSource.NETEASE, qualityKey = "standard")
        val sharedKey = listenTogetherStreamCacheKey("netease|42", streamUrl)

        assertSame(resolvedInfo, resolve(candidate = null, resolved = resolvedInfo, existing = existingInfo))
        assertSame(
            resolvedInfo,
            resolve(PlaybackUrlCandidate(streamUrl), resolved = resolvedInfo, existing = existingInfo)
        )
        assertNull(
            resolve(PlaybackUrlCandidate(streamUrl, cacheKeyOverride = "song-cache"), resolved = null, existing = existingInfo)
        )
        assertSame(
            candidateInfo,
            resolve(
                PlaybackUrlCandidate(streamUrl, audioInfo = candidateInfo, cacheKeyOverride = sharedKey),
                resolved = resolvedInfo,
                existing = existingInfo
            )
        )
        assertSame(
            existingInfo,
            resolve(PlaybackUrlCandidate(streamUrl, cacheKeyOverride = sharedKey), resolved = null, existing = existingInfo)
        )
    }

    @Test
    fun `current media is a listen together fallback only for its shared candidate`() {
        PlayerManager.activePlaybackUrlIndex = 0
        PlayerManager._currentMediaUrl.value = null
        assertFalse(PlayerManager.isCurrentListenTogetherFallbackMediaUrl())

        PlayerManager._currentMediaUrl.value = streamUrl
        PlayerManager.activePlaybackCandidates = emptyList()
        assertFalse(PlayerManager.isCurrentListenTogetherFallbackMediaUrl())

        val sharedKey = listenTogetherStreamCacheKey("netease|42", streamUrl)
        PlayerManager.activePlaybackCandidates = listOf(
            PlaybackUrlCandidate("https://m701.music.126.net/other.flac", cacheKeyOverride = sharedKey)
        )
        assertFalse(PlayerManager.isCurrentListenTogetherFallbackMediaUrl())

        PlayerManager.activePlaybackCandidates = listOf(PlaybackUrlCandidate(streamUrl))
        assertFalse(PlayerManager.isCurrentListenTogetherFallbackMediaUrl())

        PlayerManager.activePlaybackCandidates = listOf(PlaybackUrlCandidate(streamUrl, cacheKeyOverride = "song-cache"))
        assertFalse(PlayerManager.isCurrentListenTogetherFallbackMediaUrl())

        PlayerManager.activePlaybackCandidates = listOf(PlaybackUrlCandidate(streamUrl, cacheKeyOverride = sharedKey))
        assertTrue(PlayerManager.isCurrentListenTogetherFallbackMediaUrl())
    }

    @Test
    fun `missing media or preview playback requires the authoritative stream`() {
        PlayerManager.activePlaybackUrlIndex = 0
        PlayerManager.activePlaybackCandidates = emptyList()
        PlayerManager._currentMediaUrl.value = null
        assertTrue(PlayerManager.currentPlaybackRequiresListenTogetherAuthoritativeStream())

        PlayerManager._currentMediaUrl.value = "  "
        assertTrue(PlayerManager.currentPlaybackRequiresListenTogetherAuthoritativeStream())

        PlayerManager._currentMediaUrl.value = streamUrl
        assertFalse(PlayerManager.currentPlaybackRequiresListenTogetherAuthoritativeStream())

        PlayerManager.activePlaybackCandidates = listOf(PlaybackUrlCandidate(streamUrl, isPreviewClip = true))
        assertTrue(PlayerManager.currentPlaybackRequiresListenTogetherAuthoritativeStream())

        PlayerManager.activePlaybackCandidates = listOf(PlaybackUrlCandidate(streamUrl))
        assertFalse(PlayerManager.currentPlaybackRequiresListenTogetherAuthoritativeStream())
    }

    @Test
    fun `playback source and preferred quality follow the track platform`() {
        PlayerManager.preferredQuality = "lossless"
        PlayerManager.youtubePreferredQuality = "very_high"
        PlayerManager.biliPreferredQuality = "medium"
        val youtubeByChannel = song.copy(channelId = ListenTogetherChannels.YOUTUBE_MUSIC)
        val youtubeByUri = song.copy(channelId = null, mediaUri = "ytmusic://video/abc123")
        val biliByChannel = song.copy(channelId = ListenTogetherChannels.BILIBILI)
        val biliByAlbum = song.copy(channelId = null, album = "${PlayerManager.BILI_SOURCE_TAG}|BV1xx")

        assertEquals(PlaybackAudioSource.NETEASE, PlayerManager.listenTogetherPlaybackSource(song))
        assertEquals(PlaybackAudioSource.YOUTUBE_MUSIC, PlayerManager.listenTogetherPlaybackSource(youtubeByChannel))
        assertEquals(PlaybackAudioSource.YOUTUBE_MUSIC, PlayerManager.listenTogetherPlaybackSource(youtubeByUri))
        assertEquals(PlaybackAudioSource.BILIBILI, PlayerManager.listenTogetherPlaybackSource(biliByChannel))
        assertEquals(PlaybackAudioSource.BILIBILI, PlayerManager.listenTogetherPlaybackSource(biliByAlbum))
        assertEquals("lossless", PlayerManager.listenTogetherPreferredQualityKey(song))
        assertEquals("very_high", PlayerManager.listenTogetherPreferredQualityKey(youtubeByUri))
        assertEquals("medium", PlayerManager.listenTogetherPreferredQualityKey(biliByAlbum))
    }

    private fun resolve(
        candidate: PlaybackUrlCandidate?,
        resolved: PlaybackAudioInfo?,
        existing: PlaybackAudioInfo?
    ) = resolvePlaybackAudioInfoForListenTogetherStreamCandidate(
        candidate = candidate,
        resolvedAudioInfo = resolved,
        existingAudioInfo = existing
    )
}
