package moe.ouom.neriplayer.core.api.youtube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.auth.youtube.YouTubeAuthBundle
import moe.ouom.neriplayer.data.auth.youtube.YOUTUBE_MUSIC_ORIGIN
import moe.ouom.neriplayer.data.platform.youtube.resolveAuthorizationHeader
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class YouTubeMusicPlaybackRepositoryTest {

    private val repository = YouTubeMusicPlaybackRepository(OkHttpClient())

    private class FakePoTokenProvider(
        private val queuedTokens: MutableList<String?> = mutableListOf()
    ) : YouTubePoTokenProvider {
        val forceRefreshCalls = mutableListOf<Boolean>()
        var warmSessionCount = 0

        override suspend fun warmSession() {
            warmSessionCount += 1
        }

        override suspend fun getWebRemixGvsPoToken(
            videoId: String,
            visitorData: String,
            remoteHost: String,
            forceRefresh: Boolean
        ): String? {
            forceRefreshCalls += forceRefresh
            return if (queuedTokens.isEmpty()) {
                null
            } else {
                queuedTokens.removeAt(0)
            }
        }
    }

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
    fun parsePlayableAudio_resolvesOnlySelectedCipherCandidate() {
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "signatureCipher": "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-low%26n%3Dlow-obfuscated&sp=signature&s=encrypted-signature-low",
                    "bitrate": 96000,
                    "audioSampleRate": "44100",
                    "approxDurationMs": "123000"
                  },
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "signatureCipher": "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-mid%26n%3Dmid-obfuscated&sp=signature&s=encrypted-signature-mid",
                    "bitrate": 128000,
                    "audioSampleRate": "48000",
                    "approxDurationMs": "123000"
                  },
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "signatureCipher": "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-high%26n%3Dhigh-obfuscated&sp=signature&s=encrypted-signature-high",
                    "bitrate": 160000,
                    "audioSampleRate": "48000",
                    "approxDurationMs": "123000"
                  }
                ]
              }
            }
            """.trimIndent()
        )
        val signatureCalls = mutableListOf<String>()
        val streamingUrlCalls = mutableListOf<String>()
        val cipherResolver = object : YouTubeStreamingCipherResolver {
            override fun resolveSignature(encryptedSignature: String): String? {
                signatureCalls += encryptedSignature
                return when (encryptedSignature) {
                    "encrypted-signature-low" -> "resolved-signature-low"
                    "encrypted-signature-mid" -> "resolved-signature-mid"
                    "encrypted-signature-high" -> "resolved-signature-high"
                    else -> null
                }
            }

            override fun resolveStreamingUrl(url: String): String {
                streamingUrlCalls += url
                return url
                    .replace("low-obfuscated", "low-resolved")
                    .replace("mid-obfuscated", "mid-resolved")
                    .replace("high-obfuscated", "high-resolved")
            }
        }

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(
            root = root,
            preferredQualityKey = "higher",
            cipherResolver = cipherResolver
        )

        assertNotNull(playableAudio)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-mid&n=mid-resolved&signature=resolved-signature-mid",
            playableAudio?.url
        )
        assertEquals(listOf("encrypted-signature-mid"), signatureCalls)
        assertEquals(1, streamingUrlCalls.size)
        assertTrue(streamingUrlCalls.single().contains("id=audio-mid"))
        assertFalse(streamingUrlCalls.any { it.contains("audio-low") })
        assertFalse(streamingUrlCalls.any { it.contains("audio-high") })
    }

    @Test
    fun parsePlayableAudio_fallsBackToNextCipherCandidateWhenPreferredOneFails() {
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "signatureCipher": "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-low%26n%3Dlow-obfuscated&sp=signature&s=encrypted-signature-low",
                    "bitrate": 96000,
                    "audioSampleRate": "44100",
                    "approxDurationMs": "123000"
                  },
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "signatureCipher": "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-mid%26n%3Dmid-obfuscated&sp=signature&s=encrypted-signature-mid",
                    "bitrate": 128000,
                    "audioSampleRate": "48000",
                    "approxDurationMs": "123000"
                  },
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "signatureCipher": "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-high%26n%3Dhigh-obfuscated&sp=signature&s=encrypted-signature-high",
                    "bitrate": 160000,
                    "audioSampleRate": "48000",
                    "approxDurationMs": "123000"
                  }
                ]
              }
            }
            """.trimIndent()
        )
        val signatureCalls = mutableListOf<String>()
        val streamingUrlCalls = mutableListOf<String>()
        val cipherResolver = object : YouTubeStreamingCipherResolver {
            override fun resolveSignature(encryptedSignature: String): String? {
                signatureCalls += encryptedSignature
                return when (encryptedSignature) {
                    "encrypted-signature-mid" -> null
                    "encrypted-signature-high" -> "resolved-signature-high"
                    "encrypted-signature-low" -> "resolved-signature-low"
                    else -> null
                }
            }

            override fun resolveStreamingUrl(url: String): String {
                streamingUrlCalls += url
                return url
                    .replace("low-obfuscated", "low-resolved")
                    .replace("mid-obfuscated", "mid-resolved")
                    .replace("high-obfuscated", "high-resolved")
            }
        }

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(
            root = root,
            preferredQualityKey = "higher",
            cipherResolver = cipherResolver
        )

        assertNotNull(playableAudio)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-high&n=high-resolved&signature=resolved-signature-high",
            playableAudio?.url
        )
        assertEquals(
            listOf("encrypted-signature-mid", "encrypted-signature-high"),
            signatureCalls
        )
        assertEquals(1, streamingUrlCalls.size)
        assertTrue(streamingUrlCalls.single().contains("id=audio-high"))
        assertFalse(signatureCalls.contains("encrypted-signature-low"))
        assertFalse(streamingUrlCalls.any { it.contains("audio-low") })
        assertFalse(streamingUrlCalls.any { it.contains("audio-mid") })
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
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529,
              "appInstallData":"app-install-123",
              "coldConfigData":"cold-config-123",
              "SERIALIZED_COLD_HASH_DATA":"cold-hash-123",
              "SERIALIZED_HOT_HASH_DATA":"hot-hash-123",
              "deviceExperimentId":"device-exp-123",
              "rolloutToken":"rollout-token-123"
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

        val authBundle = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val poTokenProvider = FakePoTokenProvider(mutableListOf("po-token-should-not-be-used"))
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle },
            poTokenProvider = poTokenProvider,
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
    fun kickoffPlayableAudioPrefetch_reusesInflightResolutionForImmediatePlayback() = runBlocking {
        val bootstrapRequestCount = AtomicInteger(0)
        val playerRequestCount = AtomicInteger(0)
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29"
            });
            </script>
            </html>
        """.trimIndent()
        val webRemixPlayerResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[
                  {
                    "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
                    "url":"https://rr1---sn.googlevideo.com/videoplayback?id=audio-prefetch",
                    "bitrate":128000,
                    "audioSampleRate":"44100",
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
                    val body = when {
                        request.url.host == "music.youtube.com" && request.url.encodedPath == "/" -> {
                            bootstrapRequestCount.incrementAndGet()
                            Thread.sleep(120)
                            bootstrapHtml to "text/html; charset=utf-8"
                        }

                        request.url.encodedPath.contains("/youtubei/v1/player") -> {
                            playerRequestCount.incrementAndGet()
                            Thread.sleep(200)
                            webRemixPlayerResponse to "application/json; charset=utf-8"
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

        val authBundle = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle }
        )

        playbackRepository.kickoffPlayableAudioPrefetch(
            videoId = "prefetch-video",
            preferredQualityOverride = "very_high",
            requireDirect = true,
            preferM4a = true
        )

        val playableAudio = playbackRepository.getBestPlayableAudio(
            videoId = "prefetch-video",
            preferredQualityOverride = "very_high",
            requireDirect = true,
            preferM4a = true
        )

        assertNotNull(playableAudio)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-prefetch",
            playableAudio?.url
        )
        assertEquals(1, bootstrapRequestCount.get())
        assertEquals(1, playerRequestCount.get())
    }

    @Test
    fun getBestPlayableAudio_sharesInflightBootstrapAcrossConcurrentVideos() = runBlocking {
        val bootstrapRequestCount = AtomicInteger(0)
        val playerRequestCount = AtomicInteger(0)
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529
            });
            </script>
            </html>
        """.trimIndent()
        val directResponseA = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[
                  {
                    "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
                    "url":"https://example.com/audio-a.m4a",
                    "bitrate":128000,
                    "audioSampleRate":"44100",
                    "approxDurationMs":"223041"
                  }
                ]
              },
              "videoDetails":{"lengthSeconds":"223"}
            }
        """.trimIndent()
        val directResponseB = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[
                  {
                    "mimeType":"audio/webm; codecs=\"opus\"",
                    "url":"https://example.com/audio-b.webm",
                    "bitrate":160000,
                    "audioSampleRate":"48000",
                    "approxDurationMs":"180000"
                  }
                ]
              },
              "videoDetails":{"lengthSeconds":"180"}
            }
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val body = when {
                        request.url.host == "music.youtube.com" && request.url.encodedPath == "/" -> {
                            bootstrapRequestCount.incrementAndGet()
                            Thread.sleep(150)
                            bootstrapHtml to "text/html; charset=utf-8"
                        }

                        request.url.encodedPath.contains("/youtubei/v1/player") -> {
                            playerRequestCount.incrementAndGet()
                            val requestBody = Buffer().apply {
                                request.body?.writeTo(this)
                            }.readUtf8()
                            if (requestBody.contains("\"videoId\":\"video-a\"")) {
                                directResponseA to "application/json; charset=utf-8"
                            } else {
                                directResponseB to "application/json; charset=utf-8"
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

        val authBundle = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle }
        )

        val first = async {
            playbackRepository.getBestPlayableAudio(videoId = "video-a")
        }
        val second = async {
            playbackRepository.getBestPlayableAudio(videoId = "video-b")
        }

        assertEquals("https://example.com/audio-a.m4a", first.await()?.url)
        assertEquals("https://example.com/audio-b.webm", second.await()?.url)
        assertEquals(1, bootstrapRequestCount.get())
        assertEquals(2, playerRequestCount.get())
    }

    @Test
    fun getBestPlayableAudio_tvRequestUsesYoutubeHostAndPlayerJsSignatureTimestampFallback() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "USER_SESSION_ID":"user-session-123",
              "LOGGED_IN":true
            });
            </script>
            </html>
        """.trimIndent()
        val playerJs = """
            var ytPlayerConfig = {
              signatureTimestamp: 20529
            };
        """.trimIndent()
        val blockedPlayerResponse = """
            {"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"blocked"}}
        """.trimIndent()
        val tvPlayerResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[
                  {
                    "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
                    "signatureCipher":"url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Dtv-audio%26n%3Dobfuscated-n&sp=sig&s=encrypted-signature",
                    "bitrate":130588,
                    "audioSampleRate":"44100",
                    "contentLength":"3611036",
                    "approxDurationMs":"223074"
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
                        request.url.encodedPath == "/s/player/test-player/base.js" -> {
                            playerJs to "application/javascript; charset=utf-8"
                        }
                        request.url.encodedPath.contains("/youtubei/v1/player") -> {
                            when (request.header("X-YouTube-Client-Name")) {
                                "67" -> blockedPlayerResponse to "application/json; charset=utf-8"
                                "7" -> tvPlayerResponse to "application/json; charset=utf-8"
                                else -> blockedPlayerResponse to "application/json; charset=utf-8"
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

        val authBundle = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val poTokenProvider = FakePoTokenProvider(mutableListOf("po-token-should-not-be-used"))
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle },
            poTokenProvider = poTokenProvider,
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
            "https://rr1---sn.googlevideo.com/videoplayback?id=tv-audio&n=resolved-n&sig=resolved-signature",
            playableAudio?.url
        )

        assertTrue(
            requests.any { request ->
                request.url.host == "music.youtube.com" &&
                    request.url.encodedPath == "/s/player/test-player/base.js"
            }
        )

        val tvRequest = requests.first { request ->
            request.header("X-YouTube-Client-Name") == "7"
        }
        assertEquals("www.youtube.com", tvRequest.url.host)
    }

    @Test
    fun getBestPlayableAudio_webRemixDirectStreamAppendsPoToken() = runBlocking {
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529,
              "appInstallData":"app-install-123",
              "coldConfigData":"cold-config-123",
              "coldHashData":"cold-hash-123",
              "hotHashData":"hot-hash-123",
              "deviceExperimentId":"device-exp-123",
              "rolloutToken":"rollout-token-123"
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
                    "url":"https://rr1---sn.googlevideo.com/videoplayback?id=audio-direct&source=youtube&n=resolved-n&sig=resolved-signature",
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

        val authBundle = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val poTokenProvider = FakePoTokenProvider(mutableListOf("po-token-123"))
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle },
            poTokenProvider = poTokenProvider,
            streamingCipherResolverFactory = { _ ->
                object : YouTubeStreamingCipherResolver {
                    override fun resolveSignature(encryptedSignature: String): String? = null

                    override fun resolveStreamingUrl(url: String): String = url
                }
            }
        )

        val playableAudio = playbackRepository.getBestPlayableAudio(
            videoId = "demo-video",
            forceRefresh = false
        )

        assertNotNull(playableAudio)
        assertEquals(YouTubePlayableStreamType.DIRECT, playableAudio?.streamType)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-direct&source=youtube&n=resolved-n&sig=resolved-signature&pot=po-token-123",
            playableAudio?.url
        )
        assertEquals(listOf(false), poTokenProvider.forceRefreshCalls)
    }

    @Test
    fun getBestPlayableAudio_forceRefreshRequestsFreshPoToken() = runBlocking {
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "STS":20529
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
                    "url":"https://rr1---sn.googlevideo.com/videoplayback?id=audio-direct&source=youtube&n=resolved-n&sig=resolved-signature",
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

        val authBundle = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val poTokenProvider = FakePoTokenProvider(
            mutableListOf("po-token-1", "po-token-2")
        )
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle },
            poTokenProvider = poTokenProvider,
            streamingCipherResolverFactory = { _ ->
                object : YouTubeStreamingCipherResolver {
                    override fun resolveSignature(encryptedSignature: String): String? = null

                    override fun resolveStreamingUrl(url: String): String = url
                }
            }
        )

        val firstPlayableAudio = playbackRepository.getBestPlayableAudio(
            videoId = "demo-video",
            forceRefresh = false
        )
        val refreshedPlayableAudio = playbackRepository.getBestPlayableAudio(
            videoId = "demo-video",
            forceRefresh = true
        )

        assertNotNull(firstPlayableAudio)
        assertNotNull(refreshedPlayableAudio)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-direct&source=youtube&n=resolved-n&sig=resolved-signature&pot=po-token-1",
            firstPlayableAudio?.url
        )
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-direct&source=youtube&n=resolved-n&sig=resolved-signature&pot=po-token-2",
            refreshedPlayableAudio?.url
        )
        assertEquals(listOf(false, true), poTokenProvider.forceRefreshCalls)
    }

    @Test
    fun getBestPlayableAudio_prefersWebRemixDirectAndStopsBeforeFallbackClients() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529,
              "appInstallData":"app-install-123",
              "coldConfigData":"cold-config-123",
              "coldHashData":"cold-hash-123",
              "hotHashData":"hot-hash-123",
              "deviceExperimentId":"device-exp-123",
              "rolloutToken":"rollout-token-123"
            });
            </script>
            </html>
        """.trimIndent()
        val tvDirectPlayerResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[
                  {
                    "mimeType":"audio/webm; codecs=\"opus\"",
                    "url":"https://rr1---sn.googlevideo.com/videoplayback?id=audio-tv&source=youtube&c=TVHTML5&n=resolved-tv&sig=tv-signature",
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
        val webRemixDirectResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[
                  {
                    "mimeType":"audio/webm; codecs=\"opus\"",
                    "url":"https://rr1---sn.googlevideo.com/videoplayback?id=audio-web-remix&source=youtube&c=WEB_REMIX&n=resolved-web&sig=web-signature&pot=po-token-123",
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
                            when (request.header("X-YouTube-Client-Name")) {
                                "67" -> webRemixDirectResponse to "application/json; charset=utf-8"
                                "7" -> tvDirectPlayerResponse to "application/json; charset=utf-8"
                                else -> """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}""" to "application/json; charset=utf-8"
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

        val authBundle = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val poTokenProvider = FakePoTokenProvider(mutableListOf("po-token-should-not-be-used"))
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle },
            poTokenProvider = poTokenProvider,
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
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-web-remix&source=youtube&c=WEB_REMIX&n=resolved-web&sig=web-signature&pot=po-token-123",
            playableAudio?.url
        )
        assertEquals(1, requests.count { it.url.encodedPath.contains("/youtubei/v1/player") })
        assertTrue(
            requests.any { request ->
                request.url.encodedPath.contains("/youtubei/v1/player") &&
                    request.header("X-YouTube-Client-Name") == "67"
            }
        )
        assertFalse(
            requests.any { request ->
                request.url.encodedPath.contains("/youtubei/v1/player") &&
                    request.header("X-YouTube-Client-Name") == "7"
            }
        )
        assertTrue(poTokenProvider.forceRefreshCalls.isEmpty())
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
    fun parsePlayableAudio_veryHighPlaybackPrefersHigherBitrateOpusOverM4aFallback() {
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-aac-140",
                    "bitrate": 130625,
                    "audioSampleRate": "44100",
                    "contentLength": "3606154",
                    "approxDurationMs": "222741"
                  },
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-opus-251",
                    "bitrate": 149704,
                    "audioSampleRate": "48000",
                    "contentLength": "3830033",
                    "approxDurationMs": "222741"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val playbackAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(
            root = root,
            preferredQualityKey = "very_high"
        )
        val downloadAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(
            root = root,
            preferredQualityKey = "very_high",
            preferM4a = true
        )

        assertNotNull(playbackAudio)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-opus-251",
            playbackAudio?.url
        )
        assertEquals("audio/webm", playbackAudio?.mimeType)

        assertNotNull(downloadAudio)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-aac-140",
            downloadAudio?.url
        )
        assertEquals("audio/mp4", downloadAudio?.mimeType)
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
    fun selectPreferredPlayableAudio_prefersHigherQualityTvDirectOverLowerQualityWebRemixDirect() {
        val webRemixDirectAudio = YouTubePlayableAudio(
            url = "https://rr1---sn.googlevideo.com/videoplayback?id=web-remix-direct&source=youtube&c=WEB_REMIX&n=resolved-n&sig=resolved-signature&pot=po-token-123",
            durationMs = 223_000L,
            mimeType = "audio/webm",
            contentLength = 3_586_688L,
            streamType = YouTubePlayableStreamType.DIRECT,
            bitrateKbps = 96,
            sampleRateHz = 44_100
        )
        val tvDirectAudio = YouTubePlayableAudio(
            url = "https://rr1---sn.googlevideo.com/videoplayback?id=tv-direct&source=youtube&c=TVHTML5&n=resolved-tv&sig=tv-signature",
            durationMs = 223_000L,
            mimeType = "audio/webm",
            contentLength = 3_611_036L,
            streamType = YouTubePlayableStreamType.DIRECT,
            bitrateKbps = 141,
            sampleRateHz = 48_000
        )

        val selected = repository.selectPreferredPlayableAudio(
            current = webRemixDirectAudio,
            incoming = tvDirectAudio,
            currentClientName = "WEB_REMIX",
            incomingClientName = "TVHTML5"
        )

        assertSame(tvDirectAudio, selected)
    }

    @Test
    fun selectPreferredPlayableAudio_prefersWebRemixDirectAsTieBreakerWhenQualityEquivalent() {
        val webRemixDirectAudio = YouTubePlayableAudio(
            url = "https://rr1---sn.googlevideo.com/videoplayback?id=web-remix-direct&source=youtube&c=WEB_REMIX&pot=po-token-123",
            durationMs = 223_000L,
            mimeType = "audio/webm",
            contentLength = 3_586_688L,
            streamType = YouTubePlayableStreamType.DIRECT,
            bitrateKbps = 141,
            sampleRateHz = 48_000
        )
        val tvDirectAudio = YouTubePlayableAudio(
            url = "https://rr1---sn.googlevideo.com/videoplayback?id=tv-direct&source=youtube&c=TVHTML5",
            durationMs = 223_000L,
            mimeType = "audio/webm",
            contentLength = 3_586_688L,
            streamType = YouTubePlayableStreamType.DIRECT,
            bitrateKbps = 141,
            sampleRateHz = 48_000
        )

        val selected = repository.selectPreferredPlayableAudio(
            current = webRemixDirectAudio,
            incoming = tvDirectAudio,
            currentClientName = "WEB_REMIX",
            incomingClientName = "TVHTML5"
        )

        assertSame(webRemixDirectAudio, selected)
    }

    @Test
    fun selectPreferredPlayableAudio_prefersDirectOverWebRemixHls() {
        val tvDirectAudio = YouTubePlayableAudio(
            url = "https://rr1---sn.googlevideo.com/videoplayback?id=tv-direct&source=youtube&c=TVHTML5",
            durationMs = 223_000L,
            mimeType = "audio/webm",
            contentLength = 3_611_036L,
            streamType = YouTubePlayableStreamType.DIRECT
        )
        val webRemixHlsAudio = YouTubePlayableAudio(
            url = "https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/playlist/index.m3u8/pot/po-token-123",
            durationMs = 223_000L,
            mimeType = "application/x-mpegURL",
            contentLength = 3_586_688L,
            streamType = YouTubePlayableStreamType.HLS
        )

        val selected = repository.selectPreferredPlayableAudio(
            current = webRemixHlsAudio,
            incoming = tvDirectAudio,
            currentClientName = "WEB_REMIX",
            incomingClientName = "TVHTML5"
        )

        assertSame(tvDirectAudio, selected)
    }

    @Test
    fun getBestPlayableAudio_prefersExistingDirectWithoutFetchingLaterHlsManifest() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529
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
                                "7" -> hlsPlayerResponse to "application/json; charset=utf-8"
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

        val authBundle = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle },
            streamingCipherResolverFactory = { _ ->
                object : YouTubeStreamingCipherResolver {
                    override fun resolveSignature(encryptedSignature: String): String? = null

                    override fun resolveStreamingUrl(url: String): String = url
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
            "https://rr1---sn.googlevideo.com/videoplayback?id=web-remix-direct&n=resolved-n&sig=resolved-signature",
            playableAudio?.url
        )
        assertEquals(
            "67",
            requests.firstOrNull { it.url.encodedPath.contains("/youtubei/v1/player") }
                ?.header("X-YouTube-Client-Name")
        )
        assertTrue(
            requests.none { request -> request.url.host == "manifest.googlevideo.com" }
        )
    }

    @Test
    fun getBestPlayableAudio_prefersLaterTvDirectOverWebRemixHlsAndCarriesPoTokenOnManifestRequest() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529
            });
            </script>
            </html>
        """.trimIndent()
        val webRemixHlsResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/playlist/master.m3u8"
              },
              "videoDetails":{"lengthSeconds":"223"}
            }
        """.trimIndent()
        val tvDirectResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[
                  {
                    "mimeType":"audio/webm; codecs=\"opus\"",
                    "url":"https://rr1---sn.googlevideo.com/videoplayback?id=tv-direct&source=youtube&c=TVHTML5",
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
                                "67" -> webRemixHlsResponse to "application/json; charset=utf-8"
                                "7" -> tvDirectResponse to "application/json; charset=utf-8"
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

        val authBundle = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val poTokenProvider = FakePoTokenProvider(mutableListOf("po-token-123"))
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle },
            poTokenProvider = poTokenProvider,
            streamingCipherResolverFactory = { _ ->
                object : YouTubeStreamingCipherResolver {
                    override fun resolveSignature(encryptedSignature: String): String? = null

                    override fun resolveStreamingUrl(url: String): String = url
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
            "https://rr1---sn.googlevideo.com/videoplayback?id=tv-direct&source=youtube&c=TVHTML5",
            playableAudio?.url
        )
        assertTrue(
            requests.any { request ->
                request.url.host == "manifest.googlevideo.com" &&
                    request.url.toString().contains("po-token-123")
            }
        )
    }

    @Test
    fun getBestPlayableAudio_triesTvFallbackBeforeRefreshingBootstrapWhenWebRemixReturnsUnavailable() = runBlocking {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("zh-CN"))
        try {
        val requests = mutableListOf<okhttp3.Request>()
        var bootstrapRequestCount = 0
        var webRemixRequestCount = 0
        var tvRequestCount = 0
        val initialBootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-initial",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529,
              "LOGGED_IN":true,
              "USER_SESSION_ID":"user-session-123"
            });
            </script>
            </html>
        """.trimIndent()
        val refreshedBootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-refreshed",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529,
              "LOGGED_IN":true,
              "USER_SESSION_ID":"user-session-456"
            });
            </script>
            </html>
        """.trimIndent()
        val blockedPlayerResponse = """
            {"playabilityStatus":{"status":"ERROR","reason":"This video is unavailable."}}
        """.trimIndent()
        val tvDirectResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[
                  {
                    "mimeType":"audio/webm; codecs=\"opus\"",
                    "url":"https://rr1---sn.googlevideo.com/videoplayback?id=audio-tv-fallback&source=youtube&c=TVHTML5",
                    "bitrate":140073,
                    "audioSampleRate":"48000",
                    "contentLength":"3830033",
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
                            bootstrapRequestCount += 1
                            if (bootstrapRequestCount == 1) {
                                initialBootstrapHtml to "text/html; charset=utf-8"
                            } else {
                                refreshedBootstrapHtml to "text/html; charset=utf-8"
                            }
                        }
                        request.url.encodedPath.contains("/youtubei/v1/player") -> {
                            if (request.header("X-YouTube-Client-Name") == "67") {
                                webRemixRequestCount += 1
                                blockedPlayerResponse to "application/json; charset=utf-8"
                            } else if (request.header("X-YouTube-Client-Name") == "7") {
                                tvRequestCount += 1
                                tvDirectResponse to "application/json; charset=utf-8"
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

        val authBundle = YouTubeAuthBundle(
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
            forceRefresh = false
        )

        assertNotNull(playableAudio)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-tv-fallback&source=youtube&c=TVHTML5",
            playableAudio?.url
        )
        assertEquals(1, bootstrapRequestCount)
        assertTrue(webRemixRequestCount >= 1)
        assertTrue(tvRequestCount >= 1)
        val firstWebRemixRequestIndex = requests.indexOfFirst { request ->
            request.url.encodedPath.contains("/youtubei/v1/player") &&
                request.header("X-YouTube-Client-Name") == "67"
        }
        val firstTvRequestIndex = requests.indexOfFirst { request ->
            request.url.encodedPath.contains("/youtubei/v1/player") &&
                request.header("X-YouTube-Client-Name") == "7"
        }
        assertTrue(firstWebRemixRequestIndex in 0 until firstTvRequestIndex)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun getBestPlayableAudio_webRemixManifestWithExistingPoToken_skipsRedundantPoTokenFetch() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529
            });
            </script>
            </html>
        """.trimIndent()
        val webRemixHlsResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/playlist/master.m3u8?pot=embedded-pot"
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
                            if (request.header("X-YouTube-Client-Name") == "67") {
                                webRemixHlsResponse to "application/json; charset=utf-8"
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

        val authBundle = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val poTokenProvider = FakePoTokenProvider(mutableListOf("po-token-should-not-be-used"))
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle },
            poTokenProvider = poTokenProvider
        )

        val playableAudio = playbackRepository.getBestPlayableAudio(
            videoId = "demo-video",
            forceRefresh = true
        )

        assertNotNull(playableAudio)
        assertEquals(YouTubePlayableStreamType.HLS, playableAudio?.streamType)
        assertEquals(
            "https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/playlist/audio/itag/234/playlist/index.m3u8?pot=embedded-pot",
            playableAudio?.url
        )
        assertTrue(poTokenProvider.forceRefreshCalls.isEmpty())
        assertTrue(
            requests.any { request ->
                request.url.host == "manifest.googlevideo.com" &&
                    request.url.toString().contains("pot=embedded-pot")
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
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529,
              "appInstallData":"app-install-123",
              "coldConfigData":"cold-config-123",
              "coldHashData":"cold-hash-123",
              "hotHashData":"hot-hash-123",
              "deviceExperimentId":"device-exp-123",
              "rolloutToken":"rollout-token-123"
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

        val authBundle = YouTubeAuthBundle(
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
        assertEquals(
            "https://music.youtube.com/watch?v=demo-video&list=RDAMVMdemo-video",
            webRemixRequest.header("Referer")
        )
        assertEquals("1.20260321.00.00", webRemixRequest.header("X-YouTube-Client-Version"))
        assertEquals("true", webRemixRequest.header("X-YouTube-Bootstrap-Logged-In"))
        assertNull(webRemixRequest.header("X-Browser-Channel"))
        assertNull(webRemixRequest.header("X-Browser-Copyright"))
        assertNull(webRemixRequest.header("X-Browser-Year"))
        assertNull(webRemixRequest.header("X-Browser-Validation"))

        val requestBody = Buffer().apply {
            webRemixRequest.body?.writeTo(this)
        }.readUtf8()
        assertTrue(requestBody.contains("\"visitorData\":\"visitor-data-123\""))
        assertTrue(requestBody.contains("\"clientVersion\":\"1.20260321.00.00\""))
        assertTrue(requestBody.contains("\"clientScreen\":\"WATCH_FULL_SCREEN\""))
        assertTrue(requestBody.contains("\"remoteHost\":\"13.114.209.29\""))
        assertTrue(requestBody.contains("\"playlistId\":\"RDAMVMdemo-video\""))
        assertTrue(requestBody.contains("\"playbackContext\""))
        assertTrue(requestBody.contains("\"signatureTimestamp\":20529"))
        assertTrue(requestBody.contains("\"adSignalsInfo\""))
        assertTrue(requestBody.contains("\"referer\":\"https://music.youtube.com/\""))
        assertTrue(
            requestBody.contains(
                "\"originalUrl\":\"https://music.youtube.com/\""
            )
        )
        assertFalse(requestBody.contains("\"params\":\"igMDCNgE\""))
        assertTrue(requestBody.contains("\"connectionType\":\"CONN_CELLULAR_4G\""))
        assertTrue(requestBody.contains("\"screenWidthPoints\":771"))
        assertTrue(requestBody.contains("\"appInstallData\":\"app-install-123\""))
        assertTrue(requestBody.contains("\"coldConfigData\":\"cold-config-123\""))
        assertTrue(requestBody.contains("\"coldHashData\":\"cold-hash-123\""))
        assertTrue(requestBody.contains("\"hotHashData\":\"hot-hash-123\""))
        assertTrue(requestBody.contains("\"deviceExperimentId\":\"device-exp-123\""))
        assertTrue(requestBody.contains("\"rolloutToken\":\"rollout-token-123\""))
        assertTrue(Regex("\"cpn\":\"[A-Za-z0-9_-]{16}\"").containsMatchIn(requestBody))

        val manifestRequest = requests.first { request ->
            request.url.host == "manifest.googlevideo.com"
        }
        assertEquals("https://music.youtube.com", manifestRequest.header("Origin"))
        assertEquals("https://music.youtube.com/", manifestRequest.header("Referer"))
        assertEquals("RepoUserAgent/1.0", manifestRequest.header("User-Agent"))
        assertTrue(manifestRequest.header("X-Origin").isNullOrBlank())
        assertTrue(manifestRequest.header("X-Goog-AuthUser").isNullOrBlank())
        assertTrue(manifestRequest.header("Authorization").isNullOrBlank())
        assertTrue(manifestRequest.header("Cookie").isNullOrBlank())
    }

    @Test
    fun getBestPlayableAudio_triesTvFallbackBeforeRefreshingBootstrap() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        var bootstrapRequestCount = 0
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529,
              "LOGGED_IN":true,
              "USER_SESSION_ID":"user-session-123"
            });
            </script>
            </html>
        """.trimIndent()
        val webRemixBlockedResponse = """
            {"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"blocked"}}
        """.trimIndent()
        val tvPlayerResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[
                  {
                    "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
                    "url":"https://rr1---sn.googlevideo.com/videoplayback?id=audio-tv",
                    "bitrate":130588,
                    "audioSampleRate":"44100",
                    "contentLength":"3611036",
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

        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    requests += request
                    val body = when {
                        request.url.host == "music.youtube.com" && request.url.encodedPath == "/" -> {
                            bootstrapRequestCount += 1
                            bootstrapHtml to "text/html; charset=utf-8"
                        }
                        request.url.encodedPath.contains("/youtubei/v1/player") &&
                            request.header("X-YouTube-Client-Name") == "67" -> {
                            webRemixBlockedResponse to "application/json; charset=utf-8"
                        }
                        request.url.encodedPath.contains("/youtubei/v1/player") &&
                            request.header("X-YouTube-Client-Name") == "7" &&
                            request.header("User-Agent") ==
                            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)" -> {
                            tvPlayerResponse to "application/json; charset=utf-8"
                        }
                        request.url.encodedPath.contains("/youtubei/v1/player") -> {
                            blockedPlayerResponse to "application/json; charset=utf-8"
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

        val authBundle = YouTubeAuthBundle(
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
        assertEquals("https://rr1---sn.googlevideo.com/videoplayback?id=audio-tv", playableAudio?.url)
        assertEquals(1, bootstrapRequestCount)

        val tvRequest = requests.first { request ->
            request.url.encodedPath.contains("/youtubei/v1/player") &&
                request.header("X-YouTube-Client-Name") == "7"
        }
        val tvRequestBody = Buffer().apply {
            tvRequest.body?.writeTo(this)
        }.readUtf8()
        assertEquals("https://www.youtube.com/", tvRequest.header("Referer"))
        assertTrue(tvRequestBody.contains("\"signatureTimestamp\":20529"))
        assertFalse(tvRequestBody.contains("\"referer\":\"https://music.youtube.com/\""))
    }

    @Test
    fun clearAuthBoundCaches_rebuildsPlaybackBootstrapEvenWhenCookieHeaderIsStable() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        var bootstrapRequestCount = 0
        var webRemixPlayerRequestCount = 0
        val guestBootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-guest",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529,
              "LOGGED_IN":false
            });
            </script>
            </html>
        """.trimIndent()
        val memberBootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-member",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529,
              "USER_SESSION_ID":"user-session-123",
              "LOGGED_IN":true
            });
            </script>
            </html>
        """.trimIndent()
        val blockedPlayerResponse = """
            {"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"blocked"}}
        """.trimIndent()
        val firstWebRemixPlayerResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[
                  {
                    "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
                    "url":"https://rr1---sn.googlevideo.com/videoplayback?id=audio-guest",
                    "bitrate":128000,
                    "audioSampleRate":"44100",
                    "contentLength":"3586688",
                    "approxDurationMs":"223041"
                  }
                ]
              },
              "videoDetails":{"lengthSeconds":"223"}
            }
        """.trimIndent()
        val secondWebRemixPlayerResponse = """
            {
              "playabilityStatus":{"status":"OK"},
              "streamingData":{
                "adaptiveFormats":[
                  {
                    "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
                    "url":"https://rr1---sn.googlevideo.com/videoplayback?id=audio-member",
                    "bitrate":128000,
                    "audioSampleRate":"44100",
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
                            bootstrapRequestCount += 1
                            if (bootstrapRequestCount == 1) {
                                guestBootstrapHtml to "text/html; charset=utf-8"
                            } else {
                                memberBootstrapHtml to "text/html; charset=utf-8"
                            }
                        }
                        request.url.encodedPath.contains("/youtubei/v1/player") -> {
                            if (request.header("X-YouTube-Client-Name") == "67") {
                                webRemixPlayerRequestCount += 1
                                if (webRemixPlayerRequestCount == 1) {
                                    firstWebRemixPlayerResponse to "application/json; charset=utf-8"
                                } else {
                                    secondWebRemixPlayerResponse to "application/json; charset=utf-8"
                                }
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

        val authBundle = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle }
        )

        val firstPlayableAudio = playbackRepository.getBestPlayableAudio(
            videoId = "demo-video",
            forceRefresh = true
        )
        playbackRepository.clearAuthBoundCaches()
        val secondPlayableAudio = playbackRepository.getBestPlayableAudio(
            videoId = "demo-video",
            forceRefresh = false
        )

        assertNotNull(firstPlayableAudio)
        assertNotNull(secondPlayableAudio)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-guest",
            firstPlayableAudio?.url
        )
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-member",
            secondPlayableAudio?.url
        )
        assertEquals(2, bootstrapRequestCount)
        assertEquals(2, webRemixPlayerRequestCount)

        val webRemixRequests = requests.filter { request ->
            request.url.encodedPath.contains("/youtubei/v1/player") &&
                request.header("X-YouTube-Client-Name") == "67"
        }
        assertEquals(2, webRemixRequests.size)
        assertEquals("visitor-guest", webRemixRequests[0].header("X-Goog-Visitor-Id"))
        assertEquals("visitor-member", webRemixRequests[1].header("X-Goog-Visitor-Id"))
        assertFalse(webRemixRequests[0].header("Authorization").orEmpty().contains("_u"))
        assertTrue(webRemixRequests[1].header("Authorization").orEmpty().contains("_u"))

        val secondRequestBody = Buffer().apply {
            webRemixRequests[1].body?.writeTo(this)
        }.readUtf8()
        assertTrue(secondRequestBody.contains("\"visitorData\":\"visitor-member\""))
        assertTrue(secondRequestBody.contains("\"userInterfaceTheme\":\"USER_INTERFACE_THEME_LIGHT\""))
    }

    @Test
    fun warmBootstrapAsync_prefetchesBootstrapAndWarmsPoTokenSession() {
        val bootstrapRequests = AtomicInteger(0)
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"0",
              "remoteHost":"13.114.209.29",
              "STS":20529,
              "LOGGED_IN":true,
              "USER_SESSION_ID":"user-session-123"
            });
            </script>
            </html>
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val body = when {
                        request.url.host == "music.youtube.com" && request.url.encodedPath == "/" -> {
                            bootstrapRequests.incrementAndGet()
                            bootstrapHtml to "text/html; charset=utf-8"
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

        val poTokenProvider = FakePoTokenProvider()
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = {
                YouTubeAuthBundle(
                    cookieHeader = "SAPISID=sap-value; SID=sid-value",
                    xGoogAuthUser = "0",
                    userAgent = "RepoUserAgent/1.0"
                )
            },
            poTokenProvider = poTokenProvider
        )

        playbackRepository.warmBootstrapAsync()

        var warmed = false
        repeat(100) {
            if (bootstrapRequests.get() > 0) {
                warmed = true
                return@repeat
            }
            Thread.sleep(20)
        }

        assertTrue(warmed)
        assertEquals(1, bootstrapRequests.get())

        var poSessionWarmed = false
        repeat(100) {
            if (poTokenProvider.warmSessionCount > 0) {
                poSessionWarmed = true
                return@repeat
            }
            Thread.sleep(20)
        }

        assertTrue(poSessionWarmed)
        assertEquals(1, poTokenProvider.warmSessionCount)
    }

    @Test
    fun shouldClearAuthBoundCachesForFingerprintChange_skipsInitialFingerprintSync() {
        assertFalse(
            repository.shouldClearAuthBoundCachesForFingerprintChange(
                previousFingerprint = null,
                nextFingerprint = "fingerprint-a"
            )
        )
        assertFalse(
            repository.shouldClearAuthBoundCachesForFingerprintChange(
                previousFingerprint = "fingerprint-a",
                nextFingerprint = "fingerprint-a"
            )
        )
        assertTrue(
            repository.shouldClearAuthBoundCachesForFingerprintChange(
                previousFingerprint = "fingerprint-a",
                nextFingerprint = "fingerprint-b"
            )
        )
    }

    @Test
    fun getBestPlayableAudio_bootstrapAuthRefresh_usesRefreshedAuthHeadersForPlayerRequest() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val refreshedBootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-refreshed",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"3",
              "remoteHost":"13.114.209.29",
              "STS":20529,
              "LOGGED_IN":true,
              "USER_SESSION_ID":"user-session-123"
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
                    "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
                    "url":"https://rr1---sn.googlevideo.com/videoplayback?id=audio-refreshed",
                    "bitrate":128000,
                    "audioSampleRate":"44100",
                    "contentLength":"3586688",
                    "approxDurationMs":"223041"
                  }
                ]
              },
              "videoDetails":{"lengthSeconds":"223"}
            }
        """.trimIndent()

        val staleAuth = YouTubeAuthBundle(
            cookieHeader = "SAPISID=stale-sap-value; SID=stale-sid-value",
            xGoogAuthUser = "0",
            userAgent = "RepoUserAgent/1.0"
        )
        val refreshedAuth = YouTubeAuthBundle(
            cookieHeader = "SAPISID=refreshed-sap-value; SID=refreshed-sid-value",
            xGoogAuthUser = "3",
            userAgent = "RepoUserAgent/1.0"
        )
        var authBundle = staleAuth

        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    requests += request
                    val response = when {
                        request.url.encodedPath == "/" &&
                            (request.url.host == "music.youtube.com" ||
                                request.url.host == "www.youtube.com") -> {
                            if (request.header("Cookie").orEmpty().contains("stale-sap-value")) {
                                authBundle = refreshedAuth
                                Response.Builder()
                                    .request(request)
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(403)
                                    .message("Forbidden")
                                    .body("{}".toResponseBody("application/json; charset=utf-8".toMediaType()))
                                    .build()
                            } else {
                                Response.Builder()
                                    .request(request)
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(200)
                                    .message("OK")
                                    .body(
                                        refreshedBootstrapHtml.toResponseBody(
                                            "text/html; charset=utf-8".toMediaType()
                                        )
                                    )
                                    .build()
                            }
                        }
                        request.url.encodedPath.contains("/youtubei/v1/player") -> {
                            val body = if (request.header("X-YouTube-Client-Name") == "67") {
                                webRemixPlayerResponse
                            } else {
                                blockedPlayerResponse
                            }
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                                .build()
                        }
                        else -> {
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body("{}".toResponseBody("application/json; charset=utf-8".toMediaType()))
                                .build()
                        }
                    }
                    response
                }
            )
            .build()

        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle }
        )

        val playableAudio = playbackRepository.getBestPlayableAudio(
            videoId = "demo-video",
            forceRefresh = true
        )

        val webRemixRequest = requireNotNull(
            requests.firstOrNull { request ->
                request.url.encodedPath.contains("/youtubei/v1/player") &&
                    request.header("X-YouTube-Client-Name") == "67"
            }
        ) {
            requests.joinToString(
                prefix = "Missing WEB_REMIX player request. Seen requests: [",
                postfix = "]"
            ) { request ->
                "${request.method} ${request.url} client=${request.header("X-YouTube-Client-Name")}"
            }
        }

        assertNotNull(playableAudio)
        assertEquals("3", webRemixRequest.header("X-Goog-AuthUser"))
        assertTrue(webRemixRequest.header("Cookie").orEmpty().contains("refreshed-sap-value"))
        assertEquals(
            refreshedAuth.resolveAuthorizationHeader(
                origin = YOUTUBE_MUSIC_ORIGIN,
                userSessionId = "user-session-123"
            ),
            webRemixRequest.header("Authorization")
        )
    }

    @Test
    fun getBestPlayableAudio_rebuildsBootstrapWhenAuthUserChangesWithSameCookies() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        var bootstrapRequestCount = 0
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-shared",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"0",
              "remoteHost":"13.114.209.29",
              "STS":20529,
              "LOGGED_IN":true,
              "USER_SESSION_ID":"user-session-123"
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
                    "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
                    "url":"https://rr1---sn.googlevideo.com/videoplayback?id=audio-shared",
                    "bitrate":128000,
                    "audioSampleRate":"44100",
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
                            bootstrapRequestCount += 1
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

        var authBundle = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            userAgent = "RepoUserAgent/1.0"
        )
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle }
        )

        playbackRepository.getBestPlayableAudio(videoId = "demo-video-1", forceRefresh = false)
        authBundle = authBundle.copy(xGoogAuthUser = "3")
        playbackRepository.getBestPlayableAudio(videoId = "demo-video-2", forceRefresh = false)

        val webRemixRequests = requests.filter { request ->
            request.url.encodedPath.contains("/youtubei/v1/player") &&
                request.header("X-YouTube-Client-Name") == "67"
        }

        assertEquals(2, bootstrapRequestCount)
        assertEquals(2, webRemixRequests.size)
        assertEquals("0", webRemixRequests[0].header("X-Goog-AuthUser"))
        assertEquals("3", webRemixRequests[1].header("X-Goog-AuthUser"))
    }

    @Test
    fun getBestPlayableAudio_usesBootstrapSessionIndexWhenStoredAuthUserMissing() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        var bootstrapRequestCount = 0
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-shared",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529,
              "LOGGED_IN":true,
              "USER_SESSION_ID":"user-session-123"
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
                    "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
                    "url":"https://rr1---sn.googlevideo.com/videoplayback?id=audio-shared",
                    "bitrate":128000,
                    "audioSampleRate":"44100",
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
                            bootstrapRequestCount += 1
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

        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = {
                YouTubeAuthBundle(
                    cookieHeader = "SAPISID=sap-value; SID=sid-value",
                    userAgent = "RepoUserAgent/1.0"
                )
            }
        )

        playbackRepository.getBestPlayableAudio(videoId = "demo-video-1", forceRefresh = false)

        val webRemixRequests = requests.filter { request ->
            request.url.encodedPath.contains("/youtubei/v1/player") &&
                request.header("X-YouTube-Client-Name") == "67"
        }

        assertEquals(1, bootstrapRequestCount)
        assertEquals(1, webRemixRequests.size)
        assertEquals("7", webRemixRequests[0].header("X-Goog-AuthUser"))
    }

    @Test
    fun getBestPlayableAudio_requireDirect_ignoresCachedHlsAndRefetchesDirect() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        var webRemixRequestCount = 0
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529
            });
            </script>
            </html>
        """.trimIndent()
        val webRemixHlsResponse = """
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
                    "url":"https://rr1---sn.googlevideo.com/videoplayback?id=web-remix-direct",
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
                                "67" -> {
                                    webRemixRequestCount += 1
                                    if (webRemixRequestCount == 1) {
                                        webRemixHlsResponse to "application/json; charset=utf-8"
                                    } else {
                                        webRemixDirectResponse to "application/json; charset=utf-8"
                                    }
                                }
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

        val authBundle = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle }
        )

        playbackRepository.prefetchPlayableAudioUrl(
            videoId = "demo-video",
            preferredQualityOverride = "very_high",
            requireDirect = false
        )
        val playableAudio = playbackRepository.getBestPlayableAudio(
            videoId = "demo-video",
            preferredQualityOverride = "very_high",
            requireDirect = true
        )

        assertNotNull(playableAudio)
        assertEquals(YouTubePlayableStreamType.DIRECT, playableAudio?.streamType)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=web-remix-direct",
            playableAudio?.url
        )
        assertEquals(2, webRemixRequestCount)
        assertTrue(
            requests.any { request -> request.url.host == "manifest.googlevideo.com" }
        )
    }

    @Test
    fun getBestPlayableAudio_propagatesCancellationDuringPlayerProfileFallback() = runBlocking {
        val bootstrapHtml = """
            <html>
            <script>
            ytcfg.set({
              "INNERTUBE_API_KEY":"test-api-key",
              "INNERTUBE_CLIENT_VERSION":"1.20260321.00.00",
              "VISITOR_DATA":"visitor-data-123",
              "jsUrl":"/s/player/test-player/base.js",
              "SESSION_INDEX":"7",
              "remoteHost":"13.114.209.29",
              "STS":20529,
              "LOGGED_IN":true
            });
            </script>
            </html>
        """.trimIndent()
        val blockedPlayerResponse = """
            {"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"blocked"}}
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    when {
                        request.url.host == "music.youtube.com" && request.url.encodedPath == "/" -> {
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(bootstrapHtml.toResponseBody("text/html; charset=utf-8".toMediaType()))
                                .build()
                        }
                        request.url.encodedPath.contains("/youtubei/v1/player") &&
                            request.header("X-YouTube-Client-Name") == "67" -> {
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(
                                    blockedPlayerResponse.toResponseBody(
                                        "application/json; charset=utf-8".toMediaType()
                                    )
                                )
                                .build()
                        }
                        request.url.encodedPath.contains("/youtubei/v1/player") &&
                            request.header("X-YouTube-Client-Name") == "7" -> {
                            throw CancellationException("cancelled during tv player request")
                        }
                        else -> {
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body("{}".toResponseBody("application/json; charset=utf-8".toMediaType()))
                                .build()
                        }
                    }
                }
            )
            .build()

        val authBundle = YouTubeAuthBundle(
            cookieHeader = "SAPISID=sap-value; SID=sid-value",
            xGoogAuthUser = "7",
            userAgent = "RepoUserAgent/1.0"
        )
        val playbackRepository = YouTubeMusicPlaybackRepository(
            okHttpClient = client,
            authProvider = { authBundle }
        )

        try {
            playbackRepository.getBestPlayableAudio(
                videoId = "demo-video",
                forceRefresh = true,
                preferM4a = true
            )
            fail("Expected cancellation to be propagated")
        } catch (error: CancellationException) {
            assertEquals("cancelled during tv player request", error.message)
        }
    }
}
