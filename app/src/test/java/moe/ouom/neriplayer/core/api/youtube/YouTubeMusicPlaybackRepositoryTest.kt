package moe.ouom.neriplayer.core.api.youtube

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

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
    fun parsePlayableAudio_resolvesCipherSignatureAndDeobfuscatesStreamingUrl() {
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "signatureCipher": "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-4%26n%3Dobfuscated-n&sp=signature&s=encrypted-signature",
                    "bitrate": 128000,
                    "audioSampleRate": "48000",
                    "approxDurationMs": "70000"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val cipherResolver = object : YouTubeStreamingCipherResolver {
            override fun resolveSignature(encryptedSignature: String): String? {
                assertEquals("encrypted-signature", encryptedSignature)
                return "decoded-signature"
            }

            override fun resolveStreamingUrl(url: String): String {
                return url.replace("obfuscated-n", "deobfuscated-n")
            }
        }

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(
            root = root,
            cipherResolver = cipherResolver
        )

        assertNotNull(playableAudio)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-4&n=deobfuscated-n&signature=decoded-signature",
            playableAudio?.url
        )
        assertEquals(70_000L, playableAudio?.durationMs)
    }

    @Test
    fun getBestPlayableAudio_usesInjectedCipherResolverForPlayerApiDirectStream() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7"
            });
            </script>
            </html>
        """.trimIndent()
        val blockedPlayerResponse = """
            {"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"blocked"}}
        """.trimIndent()
        val webRemixPlayerResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[
                  {
                    "mimeType":"audio/webm; codecs=\"opus\"",
                    "signatureCipher":"url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-cipher%26n%3Dobfuscated-n&sp=sig&s=encrypted-signature",
                    "bitrate":128646,
                    "audioSampleRate":"48000",
                    "contentLength":"3586688",
                    "approxDurationMs":"223041"
                  }
                ]
              },
              "videoDetails":{"lengthSeconds":"223"}
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    requests += request
                    val body = when {
                        request.url.host == "music.youtube.com" && request.url.encodedPath == "/" -> {
                            bootstrapHtml to "text/html; charset=utf-8"
                        }
                        request.url.encodedPath.contains("/youtubei/v1/player") -> {
                            if (request.header("X-YouTube-Client-Name") == "67") {
                                webRemixPlayerResponse to "application/json; charset=utf-8"
                            } else {
                                blockedPlayerResponse to "application/json; charset=utf-8"
                            }
                        }
                        else -> "{}" to "application/json; charset=utf-8"
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.first.toResponseBody(body.second.toMediaType()))
                        .build()
                }
            )
            .build()

        val authBundle = moe.ouom.neriplayer.data.YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle },
            streamingCipherResolverFactory = { _ ->
                object : YouTubeStreamingCipherResolver {
                    override fun resolveSignature(encryptedSignature: String): String? {
                        return if (encryptedSignature == "encrypted-signature") {
                            "resolved-signature"
                        } else {
                            null
                        }
                    }

                    override fun resolveStreamingUrl(url: String): String {
                        return url.replace("obfuscated-n", "resolved-n")
                    }
                }
            }
        )

        val playableAudio = playbackRepository.getBestPlayableAudio(
            videoId = "demo-video",
            forceRefresh = true
        )

        assertNotNull(playableAudio)
        assertEquals(YouTubePlayableStreamType.DIRECT, playableAudio?.streamType)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-cipher&n=resolved-n&sig=resolved-signature",
            playableAudio?.url
        )
        assertEquals(223_041L, playableAudio?.durationMs)
        assertTrue(
            requests.none { request ->
                request.url.host == "www.youtube.com" &&
                    request.url.encodedPath.contains("/watch")
            }
        )
    }

    @Test
    fun getBestPlayableAudio_prefersWebRemixDirectOverEarlierHlsProfile() = runBlocking {
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7"
            });
            </script>
            </html>
        """.trimIndent()
        val tvHlsPlayerResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/playlist/master.m3u8"
              },
              "videoDetails":{"lengthSeconds":"223"}
            }
        """.trimIndent()
        val webRemixDirectResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[
                  {
                    "mimeType":"audio/webm; codecs=\"opus\"",
                    "signatureCipher":"url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-web-remix%26n%3Dobfuscated-n&sp=sig&s=encrypted-signature",
                    "bitrate":128646,
                    "audioSampleRate":"48000",
                    "contentLength":"3586688",
                    "approxDurationMs":"223041"
                  }
                ]
              },
              "videoDetails":{"lengthSeconds":"223"}
            }
        """.trimIndent()
        val masterManifest = """
            #EXTM3U
            #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/playlist/audio/itag/234/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="234",NAME="Default"
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val body = when {
                        request.url.host == "music.youtube.com" && request.url.encodedPath == "/" -> {
                            bootstrapHtml to "text/html; charset=utf-8"
                        }
                        request.url.encodedPath.contains("/youtubei/v1/player") -> {
                            when (request.header("X-YouTube-Client-Name")) {
                                "85" -> tvHlsPlayerResponse to "application/json; charset=utf-8"
                                "67" -> webRemixDirectResponse to "application/json; charset=utf-8"
                                else -> """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}""" to "application/json; charset=utf-8"
                            }
                        }
                        request.url.host == "manifest.googlevideo.com" -> {
                            masterManifest to "application/x-mpegURL"
                        }
                        else -> "{}" to "application/json; charset=utf-8"
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.first.toResponseBody(body.second.toMediaType()))
                        .build()
                }
            )
            .build()

        val authBundle = moe.ouom.neriplayer.data.YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle },
            streamingCipherResolverFactory = { _ ->
                object : YouTubeStreamingCipherResolver {
                    override fun resolveSignature(encryptedSignature: String): String? {
                        return if (encryptedSignature == "encrypted-signature") {
                            "resolved-signature"
                        } else {
                            null
                        }
                    }

                    override fun resolveStreamingUrl(url: String): String {
                        return url.replace("obfuscated-n", "resolved-n")
                    }
                }
            }
        )

        val playableAudio = playbackRepository.getBestPlayableAudio(
            videoId = "demo-video",
            forceRefresh = true
        )

        assertNotNull(playableAudio)
        assertEquals(YouTubePlayableStreamType.DIRECT, playableAudio?.streamType)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-web-remix&n=resolved-n&sig=resolved-signature",
            playableAudio?.url
        )
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
    fun selectPreferredPlayableAudio_prefersDirectOverHls() {
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

        assertSame(directAudio, selected)
    }

    @Test
    fun selectPreferredPlayableAudio_prefersWebRemixDirectOverOtherDirectClient() {
        val androidDirectAudio = YouTubePlayableAudio(
            url = "https://rr1---sn.googlevideo.com/videoplayback?id=android-direct",
            durationMs = 223_000L,
            mimeType = "audio/webm",
            contentLength = 3_611_036L,
            streamType = YouTubePlayableStreamType.DIRECT
        )
        val webRemixDirectAudio = YouTubePlayableAudio(
            url = "https://rr1---sn.googlevideo.com/videoplayback?id=web-remix-direct&n=resolved-n&sig=resolved-signature",
            durationMs = 223_000L,
            mimeType = "audio/webm",
            contentLength = 3_586_688L,
            streamType = YouTubePlayableStreamType.DIRECT
        )

        val selected = repository.selectPreferredPlayableAudio(
            current = androidDirectAudio,
            incoming = webRemixDirectAudio,
            currentClientName = "ANDROID",
            incomingClientName = "WEB_REMIX"
        )

        assertSame(webRemixDirectAudio, selected)
    }

    @Test
    fun getBestPlayableAudio_prefersLaterDirectOverEarlierHlsAcrossPlayerClients() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7"
            });
            </script>
            </html>
        """.trimIndent()
        val hlsPlayerResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/playlist/master.m3u8"
              },
              "videoDetails":{"lengthSeconds":"223"}
            }
        """.trimIndent()
        val webRemixDirectResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[
                  {
                    "mimeType":"audio/webm; codecs=\"opus\"",
                    "url":"https://rr1---sn.googlevideo.com/videoplayback?id=web-remix-direct&n=resolved-n&sig=resolved-signature",
                    "bitrate":128646,
                    "audioSampleRate":"48000",
                    "contentLength":"3586688",
                    "approxDurationMs":"223041"
                  }
                ]
              },
              "videoDetails":{"lengthSeconds":"223"}
            }
        """.trimIndent()
        val blockedPlayerResponse = """
            {"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"blocked"}}
        """.trimIndent()
        val masterManifest = """
            #EXTM3U
            #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/playlist/audio/itag/234/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="234",NAME="Default"
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    requests += request
                    val body = when {
                        request.url.host == "music.youtube.com" && request.url.encodedPath == "/" -> {
                            bootstrapHtml to "text/html; charset=utf-8"
                        }
                        request.url.encodedPath.contains("/youtubei/v1/player") -> {
                            when (request.header("X-YouTube-Client-Name")) {
                                "85" -> hlsPlayerResponse to "application/json; charset=utf-8"
                                "67" -> webRemixDirectResponse to "application/json; charset=utf-8"
                                else -> blockedPlayerResponse to "application/json; charset=utf-8"
                            }
                        }
                        request.url.host == "manifest.googlevideo.com" -> {
                            masterManifest to "application/x-mpegURL"
                        }
                        else -> "{}" to "application/json; charset=utf-8"
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.first.toResponseBody(body.second.toMediaType()))
                        .build()
                }
            )
            .build()

        val authBundle = moe.ouom.neriplayer.data.YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle }
        )

        val playableAudio = playbackRepository.getBestPlayableAudio(
            videoId = "demo-video",
            forceRefresh = true
        )

        assertNotNull(playableAudio)
        assertEquals(YouTubePlayableStreamType.DIRECT, playableAudio?.streamType)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=web-remix-direct&n=resolved-n&sig=resolved-signature",
            playableAudio?.url
        )
        assertTrue(
            requests.any { request ->
                request.url.host == "manifest.googlevideo.com"
            }
        )
        assertTrue(
            requests.any { request ->
                request.url.encodedPath.contains("/youtubei/v1/player") &&
                    request.header("X-YouTube-Client-Name") == "67"
            }
        )
    }

    @Test
    fun getBestPlayableAudio_webRemixRequestCarriesVisitorDataAndStreamHeaders() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7"
            });
            </script>
            </html>
        """.trimIndent()
        val blockedPlayerResponse = """
            {"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"blocked"}}
        """.trimIndent()
        val webRemixPlayerResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/playlist/master.m3u8"
              },
              "videoDetails":{"lengthSeconds":"223"}
            }
        """.trimIndent()
        val masterManifest = """
            #EXTM3U
            #EXT-X-MEDIA:URI="https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/playlist/audio/itag/234/playlist/index.m3u8",TYPE=AUDIO,GROUP-ID="234",NAME="Default"
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    requests += request
                    val body = when {
                        request.url.host == "music.youtube.com" && request.url.encodedPath == "/" -> {
                            bootstrapHtml to "text/html; charset=utf-8"
                        }
                        request.url.encodedPath.contains("/youtubei/v1/player") -> {
                            if (request.header("X-YouTube-Client-Name") == "67") {
                                webRemixPlayerResponse to "application/json; charset=utf-8"
                            } else {
                                blockedPlayerResponse to "application/json; charset=utf-8"
                            }
                        }
                        request.url.host == "manifest.googlevideo.com" -> {
                            masterManifest to "application/x-mpegURL"
                        }
                        else -> "{}" to "application/json; charset=utf-8"
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.first.toResponseBody(body.second.toMediaType()))
                        .build()
                }
            )
            .build()

        val authBundle = moe.ouom.neriplayer.data.YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle }
        )

        val playableAudio = playbackRepository.getBestPlayableAudio(
            videoId = "demo-video",
            forceRefresh = true
        )

        assertNotNull(playableAudio)
        assertEquals(YouTubePlayableStreamType.HLS, playableAudio?.streamType)
        assertEquals(
            "https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/playlist/audio/itag/234/playlist/index.m3u8",
            playableAudio?.url
        )

        val webRemixRequest = requests.first { request ->
            request.url.encodedPath.contains("/youtubei/v1/player") &&
                request.header("X-YouTube-Client-Name") == "67"
        }
        assertEquals("visitor-data-123", webRemixRequest.header("X-Goog-Visitor-Id"))
        assertEquals("https://music.youtube.com", webRemixRequest.header("Origin"))
        assertEquals("https://music.youtube.com", webRemixRequest.header("X-Origin"))
        assertEquals("1.20260321.00.00", webRemixRequest.header("X-YouTube-Client-Version"))

        val requestBody = Buffer().apply {
            webRemixRequest.body?.writeTo(this)
        }.readUtf8()
        assertTrue(requestBody.contains("\"visitorData\":\"visitor-data-123\""))
        assertTrue(requestBody.contains("\"clientVersion\":\"1.20260321.00.00\""))

        val manifestRequest = requests.first { request ->
            request.url.host == "manifest.googlevideo.com"
        }
        assertEquals("https://music.youtube.com", manifestRequest.header("Origin"))
        assertEquals("https://music.youtube.com", manifestRequest.header("X-Origin"))
        assertEquals("https://music.youtube.com/", manifestRequest.header("Referer"))
        assertEquals("7", manifestRequest.header("X-Goog-AuthUser"))
        assertTrue(manifestRequest.header("Authorization").orEmpty().startsWith("SAPISIDHASH "))
    }
}
