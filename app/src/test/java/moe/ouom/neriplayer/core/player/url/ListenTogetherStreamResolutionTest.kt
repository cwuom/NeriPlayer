package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.model.PlaybackUrlCandidate
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo
import moe.ouom.neriplayer.listentogether.mapping.trustedListenTogetherStreamUrls
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherChannels
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
    fun `shared stream stays behind the listener local quality result`() {
        val listenerUrl = "https://m701.music.126.net/listener-high.mp3"
        val controllerUrl = "https://m702.music.126.net/controller-low.mp3"
        val listenerAudioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.NETEASE,
            qualityKey = "lossless",
            qualityLabel = "Lossless"
        )
        val merged = mergeListenTogetherFallbackResult(
            localResult = SongUrlResult.Success(
                url = listenerUrl,
                audioInfo = listenerAudioInfo
            ),
            listenTogetherFallback = SongUrlResult.Success(url = controllerUrl)
        ) as SongUrlResult.Success

        assertEquals(listenerUrl, merged.url)
        assertEquals(listenerAudioInfo, merged.audioInfo)
        assertEquals(
            listOf(listenerUrl, controllerUrl),
            merged.playbackCandidates().map { it.url }
        )
    }

    @Test
    fun `matching shared quality is preferred over listener result`() {
        val listenerUrl = "https://m701.music.126.net/listener-standard.mp3"
        val controllerUrl = decorateListenTogetherStreamUrl(
            "https://m702.music.126.net/controller-lossless.flac",
            PlaybackAudioSource.NETEASE,
            "lossless"
        )
        val merged = mergeListenTogetherFallbackResult(
            localResult = SongUrlResult.Success(
                url = listenerUrl,
                audioInfo = PlaybackAudioInfo(
                    source = PlaybackAudioSource.NETEASE,
                    qualityKey = "standard",
                    qualityLabel = "Standard"
                )
            ),
            listenTogetherFallback = SongUrlResult.Success(
                url = controllerUrl,
                audioInfo = PlaybackAudioInfo(
                    source = PlaybackAudioSource.NETEASE,
                    qualityKey = "lossless",
                    qualityLabel = "Lossless"
                )
            ),
            preferredQualityKey = "lossless"
        ) as SongUrlResult.Success

        assertEquals(controllerUrl, merged.url)
        assertEquals("lossless", merged.audioInfo?.qualityKey)
    }

    @Test
    fun `mismatched shared quality does not replace listener result`() {
        val listenerUrl = "https://m701.music.126.net/listener-lossless.flac"
        val controllerUrl = decorateListenTogetherStreamUrl(
            "https://m702.music.126.net/controller-standard.mp3",
            PlaybackAudioSource.NETEASE,
            "standard"
        )
        val merged = mergeListenTogetherFallbackResult(
            localResult = SongUrlResult.Success(
                url = listenerUrl,
                audioInfo = PlaybackAudioInfo(
                    source = PlaybackAudioSource.NETEASE,
                    qualityKey = "lossless",
                    qualityLabel = "Lossless"
                )
            ),
            listenTogetherFallback = SongUrlResult.Success(
                url = controllerUrl,
                audioInfo = PlaybackAudioInfo(
                    source = PlaybackAudioSource.NETEASE,
                    qualityKey = "standard",
                    qualityLabel = "Standard"
                )
            ),
            preferredQualityKey = "lossless"
        ) as SongUrlResult.Success

        assertEquals(listenerUrl, merged.url)
        assertEquals("lossless", merged.audioInfo?.qualityKey)
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

    @Test
    fun `listener chooses the nearest actual Netease quality`() {
        val urls = listOf(
            decorateListenTogetherStreamUrl(
                "https://m701.music.126.net/sky.flac",
                PlaybackAudioSource.NETEASE,
                "sky"
            ),
            decorateListenTogetherStreamUrl(
                "https://m701.music.126.net/lossless.flac",
                PlaybackAudioSource.NETEASE,
                "lossless"
            ),
            decorateListenTogetherStreamUrl(
                "https://m701.music.126.net/exhigh.mp3",
                PlaybackAudioSource.NETEASE,
                "exhigh"
            )
        )

        val ordered = orderListenTogetherStreamUrlsForPreference(
            streamUrls = urls,
            source = PlaybackAudioSource.NETEASE,
            preferredQualityKey = "hires"
        )

        assertEquals("lossless", listenTogetherQualityKeyFromStreamUrl(
            ordered.first(),
            PlaybackAudioSource.NETEASE
        ))
        assertEquals("lossless", listenTogetherQualityKeyFromStreamUrl(
            urls[1],
            PlaybackAudioSource.NETEASE
        ))
    }

    @Test
    fun `listener chooses a lower Netease tier when it is equally near`() {
        val urls = listOf(
            decorateListenTogetherStreamUrl(
                "https://m701.music.126.net/exhigh.mp3",
                PlaybackAudioSource.NETEASE,
                "exhigh"
            ),
            decorateListenTogetherStreamUrl(
                "https://m701.music.126.net/hires.flac",
                PlaybackAudioSource.NETEASE,
                "hires"
            )
        )

        val ordered = orderListenTogetherStreamUrlsForPreference(
            streamUrls = urls,
            source = PlaybackAudioSource.NETEASE,
            preferredQualityKey = "lossless"
        )

        assertEquals(
            "exhigh",
            listenTogetherQualityKeyFromStreamUrl(ordered.first(), PlaybackAudioSource.NETEASE)
        )
    }

    @Test
    fun `listener chooses the closest Bili stream for its own preference`() {
        val urls = listOf(
            decorateListenTogetherStreamUrl(
                "https://upos-sz-mirror.bilivideo.com/hires.flac",
                PlaybackAudioSource.BILIBILI,
                "hires"
            ),
            decorateListenTogetherStreamUrl(
                "https://upos-sz-mirror.bilivideo.com/lossless.flac",
                PlaybackAudioSource.BILIBILI,
                "lossless"
            ),
            decorateListenTogetherStreamUrl(
                "https://upos-sz-mirror.bilivideo.com/high.m4a",
                PlaybackAudioSource.BILIBILI,
                "high"
            )
        )

        val ordered = orderListenTogetherStreamUrlsForPreference(
            streamUrls = urls,
            source = PlaybackAudioSource.BILIBILI,
            preferredQualityKey = "medium"
        )

        assertEquals(
            "high",
            listenTogetherQualityKeyFromStreamUrl(ordered.first(), PlaybackAudioSource.BILIBILI)
        )
    }

    @Test
    fun `YouTube high stream is used as the actual fallback quality`() {
        val taggedUrl = decorateListenTogetherStreamUrl(
            "https://rr1---sn.googlevideo.com/videoplayback",
            PlaybackAudioSource.YOUTUBE_MUSIC,
            "high"
        )
        val ordered = orderListenTogetherStreamUrlsForPreference(
            streamUrls = listOf(taggedUrl),
            source = PlaybackAudioSource.YOUTUBE_MUSIC,
            preferredQualityKey = "very_high"
        )
        val audioInfo = buildListenTogetherFallbackAudioInfo(
            source = PlaybackAudioSource.YOUTUBE_MUSIC,
            preferredQualityKey = listenTogetherQualityKeyFromStreamUrl(
                ordered.first(),
                PlaybackAudioSource.YOUTUBE_MUSIC
            ).orEmpty(),
            getLocalizedString = { it.toString() }
        )

        assertEquals("high", audioInfo.qualityKey)
        assertEquals(R.string.settings_audio_quality_high.toString(), audioInfo.qualityLabel)
    }

    @Test
    fun `quality fragment survives stream trust validation`() {
        val taggedUrl = decorateListenTogetherStreamUrl(
            "https://m701.music.126.net/lossless.flac",
            PlaybackAudioSource.NETEASE,
            "lossless"
        )

        assertEquals(
            listOf(taggedUrl),
            trustedListenTogetherStreamUrls(
                channelId = ListenTogetherChannels.NETEASE,
                streamUrls = listOf(taggedUrl)
            )
        )
    }

    @Test
    fun `Bili mountaintoys CDN remains eligible for shared stream delivery`() {
        val streamUrl = "https://b-demo.edge.mountaintoys.cn/upgcxcode/audio.m4s"

        assertEquals(
            listOf(streamUrl),
            trustedListenTogetherStreamUrls(
                channelId = ListenTogetherChannels.BILIBILI,
                streamUrls = listOf(streamUrl)
            )
        )
    }

    @Test
    fun `incoming Bili candidates are capped at two`() {
        val urls = listOf(
            "https://upos-sz-mirror.bilivideo.com/high.m4a",
            "https://upos-sz-mirror.bilivideo.com/lossless.flac",
            "https://upos-sz-mirror.bilivideo.com/hires.flac"
        )

        assertEquals(
            urls.take(2),
            trustedListenTogetherStreamUrls(
                channelId = ListenTogetherChannels.BILIBILI,
                streamUrls = urls
            )
        )
    }

    @Test
    fun `incoming YouTube candidates are capped at one`() {
        val urls = listOf(
            "https://rr1---sn.googlevideo.com/videoplayback?itag=251",
            "https://rr2---sn.googlevideo.com/videoplayback?itag=140"
        )

        assertEquals(
            urls.take(1),
            trustedListenTogetherStreamUrls(
                channelId = ListenTogetherChannels.YOUTUBE_MUSIC,
                streamUrls = urls
            )
        )
    }

    @Test
    fun `only the controller may publish a current room stream`() {
        assertTrue(
            shouldPublishCurrentListenTogetherStream(
                listenTogetherActive = false,
                isCurrentUserController = false
            )
        )
        assertTrue(
            shouldPublishCurrentListenTogetherStream(
                listenTogetherActive = true,
                isCurrentUserController = true
            )
        )
        assertFalse(
            shouldPublishCurrentListenTogetherStream(
                listenTogetherActive = true,
                isCurrentUserController = false
            )
        )
    }

    @Test
    fun `untracked stream never inherits a prior song quality label`() {
        val staleAudioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.NETEASE,
            qualityKey = "lossless",
            qualityLabel = "Lossless"
        )

        assertNull(
            resolveListenTogetherPublishedStreamAudioInfo(
                matchingCandidate = null,
                currentCandidate = null,
                currentAudioInfo = staleAudioInfo
            )
        )
    }

    @Test
    fun `current candidate may retain its resolved quality label`() {
        val currentCandidate = PlaybackUrlCandidate(
            url = "https://m701.music.126.net/current.mp3"
        )
        val currentAudioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.NETEASE,
            qualityKey = "exhigh",
            qualityLabel = "Very high"
        )

        assertEquals(
            currentAudioInfo,
            resolveListenTogetherPublishedStreamAudioInfo(
                matchingCandidate = currentCandidate,
                currentCandidate = currentCandidate,
                currentAudioInfo = currentAudioInfo
            )
        )
    }

    @Test
    fun `quality metadata is removed before the media request`() {
        val taggedUrl = decorateListenTogetherStreamUrl(
            "https://m701.music.126.net/lossless.flac",
            PlaybackAudioSource.NETEASE,
            "lossless"
        )

        assertEquals(
            "https://m701.music.126.net/lossless.flac",
            stripListenTogetherStreamQualityMetadata(taggedUrl)
        )
    }

    @Test
    fun `Bili sharing falls back to medium and low when high is absent`() {
        val streams = selectBiliListenTogetherShareableStreams(
            listOf(
                BiliAudioStreamInfo(
                    id = 30280,
                    mimeType = "audio/mp4",
                    bitrateKbps = 128,
                    qualityTag = null,
                    url = "https://upos-sz-mirror.bilivideo.com/medium.m4a"
                ),
                BiliAudioStreamInfo(
                    id = 30280,
                    mimeType = "audio/mp4",
                    bitrateKbps = 64,
                    qualityTag = null,
                    url = "https://upos-sz-mirror.bilivideo.com/low.m4a"
                )
            )
        )

        assertEquals(listOf("medium", "low"), streams.map(::inferBiliQualityKey))
    }

    @Test
    fun `Bili sharing keeps at most two default quality tiers`() {
        val streams = selectBiliListenTogetherShareableStreams(
            listOf(
                BiliAudioStreamInfo(
                    id = 30251,
                    mimeType = "audio/flac",
                    bitrateKbps = 900,
                    qualityTag = "hires",
                    url = "https://upos-sz-mirror.bilivideo.com/hires.flac"
                ),
                BiliAudioStreamInfo(
                    id = 30280,
                    mimeType = "audio/flac",
                    bitrateKbps = 700,
                    qualityTag = "lossless",
                    url = "https://upos-sz-mirror.bilivideo.com/lossless.flac"
                ),
                BiliAudioStreamInfo(
                    id = 30280,
                    mimeType = "audio/mp4",
                    bitrateKbps = 192,
                    qualityTag = null,
                    url = "https://upos-sz-mirror.bilivideo.com/high.m4a"
                ),
                BiliAudioStreamInfo(
                    id = 30280,
                    mimeType = "audio/mp4",
                    bitrateKbps = 128,
                    qualityTag = null,
                    url = "https://upos-sz-mirror.bilivideo.com/medium.m4a"
                )
            )
        )

        assertEquals(
            listOf("high", "lossless"),
            streams.map(::inferBiliQualityKey)
        )
    }

    @Test
    fun `Bili CDN fallbacks do not consume other quality slots`() {
        val urls = buildBiliListenTogetherStreamUrls(
            selectedStreams = listOf(
                BiliAudioStreamInfo(
                    id = 30251,
                    mimeType = "audio/flac",
                    bitrateKbps = 900,
                    qualityTag = "hires",
                    url = "https://upos-sz-mirror.bilivideo.com/hires.flac",
                    candidateUrls = listOf(
                        "https://upos-sz-mirror.bilivideo.com/hires.flac",
                        "https://b-demo.edge.mountaintoys.cn/hires.flac"
                    )
                ),
                BiliAudioStreamInfo(
                    id = 30280,
                    mimeType = "audio/flac",
                    bitrateKbps = 700,
                    qualityTag = "lossless",
                    url = "https://upos-sz-mirror.bilivideo.com/lossless.flac",
                    candidateUrls = listOf(
                        "https://upos-sz-mirror.bilivideo.com/lossless.flac",
                        "https://b-demo.edge.mountaintoys.cn/lossless.flac"
                    )
                ),
                BiliAudioStreamInfo(
                    id = 30280,
                    mimeType = "audio/mp4",
                    bitrateKbps = 192,
                    qualityTag = null,
                    url = "https://upos-sz-mirror.bilivideo.com/high.m4a",
                    candidateUrls = listOf(
                        "https://upos-sz-mirror.bilivideo.com/high.m4a",
                        "https://b-demo.edge.mountaintoys.cn/high.m4a"
                    )
                )
            )
        )

        assertEquals(2, urls.size)
        assertEquals(
            listOf("hires", "lossless"),
            urls.map { listenTogetherQualityKeyFromStreamUrl(it, PlaybackAudioSource.BILIBILI) }
        )
        assertTrue(urls.all { it.contains("neriplayer-ltw-quality=bili:") })
    }

    @Test
    fun `Netease share groups keep the default three quality slots`() {
        assertEquals(
            listOf(
                listOf("exhigh", "higher", "standard"),
                listOf("lossless"),
                listOf("sky")
            ),
            buildListenTogetherNeteaseQualityGroups("exhigh")
        )
    }

    @Test
    fun `YouTube sharing resolves only the controller preferred quality`() {
        assertEquals(
            listOf("very_high"),
            buildListenTogetherYouTubeQualityOrder("very_high")
        )
        assertEquals(
            listOf("high"),
            buildListenTogetherYouTubeQualityOrder("high")
        )
    }

    @Test
    fun `preview media is not treated as a usable local stream for link requests`() {
        assertFalse(
            hasUsableListenTogetherLocalDirectStream(
                currentSongMatchesTarget = true,
                currentSongHasDirectStream = false,
                currentMediaHasDirectStream = true,
                currentPlaybackCandidateIsPreview = true
            )
        )
        assertTrue(
            hasUsableListenTogetherLocalDirectStream(
                currentSongMatchesTarget = true,
                currentSongHasDirectStream = false,
                currentMediaHasDirectStream = true,
                currentPlaybackCandidateIsPreview = false
            )
        )
        assertFalse(
            hasUsableListenTogetherLocalDirectStream(
                currentSongMatchesTarget = false,
                currentSongHasDirectStream = true,
                currentMediaHasDirectStream = true,
                currentPlaybackCandidateIsPreview = false
            )
        )
    }
}
