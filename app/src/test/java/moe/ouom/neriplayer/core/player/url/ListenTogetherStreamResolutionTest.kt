package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.model.PlaybackUrlCandidate
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherStreamResolutionTest {

    @Test
    fun `full direct result is eligible for listen together sharing`() {
        assertTrue(
            isShareableListenTogetherStreamResolution(
                SongUrlResult.Success(url = "https://m701.music.126.net/full.mp3")
            )
        )
    }

    @Test
    fun `preview result is never eligible for listen together sharing`() {
        assertFalse(
            isShareableListenTogetherStreamResolution(
                SongUrlResult.Success(
                    url = "https://m701.music.126.net/preview.mp3",
                    isPreviewClip = true
                )
            )
        )
    }

    @Test
    fun `preview fallback candidate is excluded from a shareable result`() {
        val fullUrl = "https://m701.music.126.net/full.mp3"
        val previewUrl = "https://m701.music.126.net/preview.mp3"

        assertEquals(
            listOf(fullUrl),
            shareableListenTogetherStreamUrls(
                SongUrlResult.Success(
                    url = fullUrl,
                    fallbackCandidates = listOf(
                        PlaybackUrlCandidate(
                            url = previewUrl,
                            isPreviewClip = true
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `shared stream replaces a local preview without retaining its notice`() {
        val previewUrl = "https://m701.music.126.net/preview.mp3"
        val sharedUrl = "https://m702.music.126.net/controller-full.mp3"
        val merged = mergeListenTogetherFallbackResult(
            localResult = SongUrlResult.Success(
                url = previewUrl,
                noticeMessage = "preview",
                isPreviewClip = true
            ),
            listenTogetherFallback = SongUrlResult.Success(url = sharedUrl)
        ) as SongUrlResult.Success

        assertEquals(sharedUrl, merged.url)
        assertFalse(merged.isPreviewClip)
        assertNull(merged.noticeMessage)
        assertEquals(listOf(sharedUrl), merged.playbackCandidates().map { it.url })
    }

    @Test
    fun `non http result is not eligible for listen together sharing`() {
        assertFalse(
            isShareableListenTogetherStreamResolution(
                SongUrlResult.Success(url = "file:///private/audio.m4a")
            )
        )
    }

    @Test
    fun `current preview candidate is excluded while full fallback remains shareable`() {
        val preview = PlaybackUrlCandidate(
            url = "https://m701.music.126.net/preview.mp3",
            isPreviewClip = true
        )
        val fullFallback = PlaybackUrlCandidate(
            url = "https://m702.music.126.net/full.mp3"
        )

        assertEquals(
            listOf(fullFallback.url),
            collectListenTogetherShareableStreamUrls(
                currentMediaUrl = preview.url,
                currentPlaybackCandidate = preview,
                activePlaybackCandidates = listOf(preview, fullFallback),
                allowUntrackedCurrentStream = false
            )
        )
    }

    @Test
    fun `untracked direct stream requires a non netease source`() {
        val url = "https://rr1---sn.googlevideo.com/videoplayback"

        assertEquals(
            emptyList<String>(),
            collectListenTogetherShareableStreamUrls(
                currentMediaUrl = url,
                currentPlaybackCandidate = null,
                activePlaybackCandidates = emptyList(),
                allowUntrackedCurrentStream = false
            )
        )
        assertEquals(
            listOf(url),
            collectListenTogetherShareableStreamUrls(
                currentMediaUrl = url,
                currentPlaybackCandidate = null,
                activePlaybackCandidates = emptyList(),
                allowUntrackedCurrentStream = true
            )
        )
    }

    @Test
    fun `shared stream candidate preserves prior audio quality metadata`() {
        val priorAudioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.YOUTUBE_MUSIC,
            qualityKey = "high",
            qualityLabel = "High"
        )
        val sharedCandidate = PlaybackUrlCandidate(
            url = "https://rr1---sn.googlevideo.com/videoplayback",
            cacheKeyOverride = "$LISTEN_TOGETHER_STREAM_CACHE_KEY_PREFIX-session"
        )

        assertEquals(
            priorAudioInfo,
            resolvePlaybackAudioInfoForListenTogetherStreamCandidate(
                candidate = sharedCandidate,
                resolvedAudioInfo = null,
                existingAudioInfo = priorAudioInfo
            )
        )
        assertNull(
            resolvePlaybackAudioInfoForListenTogetherStreamCandidate(
                candidate = PlaybackUrlCandidate(url = "https://example.com/audio.mp3"),
                resolvedAudioInfo = null,
                existingAudioInfo = priorAudioInfo
            )
        )
    }

    @Test
    fun `shared stream fallback has quality metadata when no prior stream exists`() {
        val audioInfo = buildListenTogetherFallbackAudioInfo(
            source = PlaybackAudioSource.YOUTUBE_MUSIC,
            preferredQualityKey = "high",
            getLocalizedString = { it.toString() }
        )

        assertEquals(PlaybackAudioSource.YOUTUBE_MUSIC, audioInfo.source)
        assertEquals("high", audioInfo.qualityKey)
        assertEquals(R.string.settings_audio_quality_high.toString(), audioInfo.qualityLabel)
        assertEquals(4, audioInfo.qualityOptions.size)
    }
}
