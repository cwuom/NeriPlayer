package moe.ouom.neriplayer.core.api.youtube

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import okhttp3.OkHttpClient

class YouTubeMusicPlaybackRepositoryTest {

    private val repository = YouTubeMusicPlaybackRepository(OkHttpClient())

    @Test
    fun parsePlayableAudio_usesApproxDurationMsWhenPresent() {
        val root = JSONObject(
            """
            {
              "videoDetails": {
                "lengthSeconds": "124"
              },
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-1",
                    "bitrate": 128000,
                    "audioSampleRate": "44100",
                    "contentLength": "2003029",
                    "approxDurationMs": "123715"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(root)

        assertNotNull(playableAudio)
        assertEquals("https://rr1---sn.googlevideo.com/videoplayback?id=audio-1", playableAudio?.url)
        assertEquals(123_715L, playableAudio?.durationMs)
        assertEquals("audio/mp4", playableAudio?.mimeType)
        assertEquals(2_003_029L, playableAudio?.contentLength)
    }

    @Test
    fun parsePlayableAudio_fallsBackToVideoDetailsDurationWhenApproxDurationMissing() {
        val root = JSONObject(
            """
            {
              "videoDetails": {
                "lengthSeconds": "321"
              },
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-2",
                    "bitrate": 160000,
                    "audioSampleRate": "48000"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(root)

        assertNotNull(playableAudio)
        assertEquals(321_000L, playableAudio?.durationMs)
        assertEquals("audio/webm", playableAudio?.mimeType)
    }

    @Test
    fun parsePlayableAudio_resolvesUnsignedSignatureCipherUrl() {
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "signatureCipher": "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-3&sp=sig&sig=test-signature",
                    "bitrate": 96000,
                    "audioSampleRate": "44100",
                    "approxDurationMs": "65432"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(root)

        assertNotNull(playableAudio)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-3&sig=test-signature",
            playableAudio?.url
        )
        assertEquals(65_432L, playableAudio?.durationMs)
    }

    @Test
    fun parsePlayableAudio_prefersLowerBitrateForStandardQuality() {
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-low",
                    "bitrate": 64000,
                    "audioSampleRate": "44100",
                    "approxDurationMs": "123000"
                  },
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-high",
                    "bitrate": 160000,
                    "audioSampleRate": "48000",
                    "approxDurationMs": "123000"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(
            root = root,
            preferredQualityKey = "standard"
        )

        assertNotNull(playableAudio)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-low",
            playableAudio?.url
        )
    }

    @Test
    fun parsePlayableAudio_prefersHighThresholdForHigherQuality() {
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-medium",
                    "bitrate": 96000,
                    "audioSampleRate": "44100",
                    "approxDurationMs": "123000"
                  },
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-high",
                    "bitrate": 128000,
                    "audioSampleRate": "48000",
                    "approxDurationMs": "123000"
                  },
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-very-high",
                    "bitrate": 160000,
                    "audioSampleRate": "48000",
                    "approxDurationMs": "123000"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(
            root = root,
            preferredQualityKey = "higher"
        )

        assertNotNull(playableAudio)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-high",
            playableAudio?.url
        )
    }

    @Test
    fun parsePlayableAudio_prefersHighestBitrateForVeryHighQuality() {
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-low",
                    "bitrate": 64000,
                    "audioSampleRate": "44100",
                    "approxDurationMs": "123000"
                  },
                  {
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-very-high",
                    "bitrate": 160000,
                    "audioSampleRate": "48000",
                    "approxDurationMs": "123000"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(
            root = root,
            preferredQualityKey = "very_high"
        )

        assertNotNull(playableAudio)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-very-high",
            playableAudio?.url
        )
        assertEquals("audio/mp4", playableAudio?.mimeType)
    }

    @Test
    fun selectAudioPlaylist_prefersHighestBitrateHlsTrackForVeryHighQuality() {
        val manifest = """
            #EXTM3U
            #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/233/sgoap/clen%3D1361514%3Bdur%3D223.143%3Bgir%3Dyes%3Bitag%3D139/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="233",NAME="Default"
            #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/234/sgoap/clen%3D3611036%3Bdur%3D223.074%3Bgir%3Dyes%3Bitag%3D140/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="234",NAME="Default"
        """.trimIndent()

        val selected = YouTubeMusicHlsManifestParser.selectAudioPlaylist(
            masterManifest = manifest,
            preferredQualityKey = "very_high",
            durationMs = 223_000L
        )

        assertNotNull(selected)
        assertEquals(140, selected?.audioItag)
        assertEquals(3_611_036L, selected?.contentLength)
    }

    @Test
    fun selectAudioPlaylist_prefersLowestBitrateHlsTrackForLowQuality() {
        val manifest = """
            #EXTM3U
            #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/233/sgoap/clen%3D1361514%3Bdur%3D223.143%3Bgir%3Dyes%3Bitag%3D139/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="233",NAME="Default"
            #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_playlist/itag/234/sgoap/clen%3D3611036%3Bdur%3D223.074%3Bgir%3Dyes%3Bitag%3D140/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="234",NAME="Default"
        """.trimIndent()

        val selected = YouTubeMusicHlsManifestParser.selectAudioPlaylist(
            masterManifest = manifest,
            preferredQualityKey = "low",
            durationMs = 223_000L
        )

        assertNotNull(selected)
        assertEquals(139, selected?.audioItag)
        assertEquals(1_361_514L, selected?.contentLength)
    }

    @Test
    fun selectAudioPlaylist_resolvesRelativeUriAgainstMasterManifestUrl() {
        val manifest = """
            #EXTM3U
            #EXT-X-MEDIA:URI="audio/itag/234/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="234",NAME="Default"
        """.trimIndent()

        val selected = YouTubeMusicHlsManifestParser.selectAudioPlaylist(
            masterManifest = manifest,
            masterManifestUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/playlist/master.m3u8",
            preferredQualityKey = "very_high",
            durationMs = 223_000L
        )

        assertNotNull(selected)
        assertEquals(
            "https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/playlist/audio/itag/234/playlist/index.m3u8",
            selected?.uri
        )
    }

    @Test
    fun selectPreferredPlayableAudio_prefersHlsOverDirect() {
        val directAudio = YouTubePlayableAudio(
            url = "https://rr1---sn.googlevideo.com/videoplayback?id=direct",
            durationMs = 223_000L,
            mimeType = "audio/webm",
            contentLength = 3_500_000L,
            streamType = YouTubePlayableStreamType.DIRECT
        )
        val hlsAudio = YouTubePlayableAudio(
            url = "https://manifest.googlevideo.com/api/manifest/hls_playlist/id/demo/playlist/index.m3u8",
            durationMs = 223_000L,
            mimeType = "application/x-mpegURL",
            contentLength = 3_611_036L,
            streamType = YouTubePlayableStreamType.HLS
        )

        val selected = repository.selectPreferredPlayableAudio(
            current = directAudio,
            incoming = hlsAudio
        )

        assertSame(hlsAudio, selected)
    }
}
