package moe.ouom.neriplayer.api.youtube.protocol

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.api.youtube.challenge.YouTubeStreamingCipherResolver
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlayableAudioParserTest {
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
            override fun resolveSignature(encryptedSignature: String): String {
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
    fun parsePlayableAudioAsync_propagates_cancellation_to_suspend_resolver() = runBlocking {
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/webm; codecs=\"opus\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=async&n=obfuscated",
                    "bitrate": 128000,
                    "approxDurationMs": "70000"
                  }
                ]
              }
            }
            """.trimIndent()
        )
        var resolverCancelled = false
        val cipherResolver = object : YouTubeStreamingCipherResolver {
            override fun resolveSignature(encryptedSignature: String): String? = null

            override fun resolveStreamingUrl(url: String): String = error(
                "the async parser must not use the blocking resolver"
            )

            override suspend fun resolveStreamingUrlAsync(url: String): String {
                try {
                    delay(Long.MAX_VALUE)
                    return url
                } finally {
                    resolverCancelled = true
                }
            }
        }

        val job = async {
            YouTubeMusicPlaybackParser.parsePlayableAudioAsync(
                root = root,
                cipherResolver = cipherResolver
            )
        }
        delay(20.milliseconds)
        job.cancelAndJoin()

        assertTrue(resolverCancelled)
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
    fun parsePlayableAudio_returnsNullWhenThrottlingParameterUnresolved() {
        // #Y4: 唯一候选带 n 但解不出 (resolver 返回空串) 时, 不得返回带混淆 n 的原 URL
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-1&n=obfuscated-n",
                    "bitrate": 128000,
                    "audioSampleRate": "44100",
                    "approxDurationMs": "70000"
                  }
                ]
              }
            }
            """.trimIndent()
        )
        val cipherResolver = object : YouTubeStreamingCipherResolver {
            override fun resolveSignature(encryptedSignature: String): String? = null
            override fun resolveStreamingUrl(url: String): String = ""
        }

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(
            root = root,
            cipherResolver = cipherResolver
        )

        assertNull(playableAudio)
    }

    @Test
    fun parsePlayableAudio_skipsCandidateWithUnresolvedThrottlingParameter() {
        // #Y4: 高码率候选 n 解不出时跳过, 回退到下一个能解出的候选, 而不是返回限速 URL
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-high&n=high-obfuscated",
                    "bitrate": 160000,
                    "audioSampleRate": "48000",
                    "approxDurationMs": "70000"
                  },
                  {
                    "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                    "url": "https://rr1---sn.googlevideo.com/videoplayback?id=audio-low&n=low-obfuscated",
                    "bitrate": 128000,
                    "audioSampleRate": "44100",
                    "approxDurationMs": "70000"
                  }
                ]
              }
            }
            """.trimIndent()
        )
        val cipherResolver = object : YouTubeStreamingCipherResolver {
            override fun resolveSignature(encryptedSignature: String): String? = null
            override fun resolveStreamingUrl(url: String): String {
                return if (url.contains("high-obfuscated")) {
                    ""
                } else {
                    url.replace("low-obfuscated", "low-resolved")
                }
            }
        }

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(
            root = root,
            preferredQualityKey = "very_high",
            cipherResolver = cipherResolver
        )

        assertNotNull(playableAudio)
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-low&n=low-resolved",
            playableAudio?.url
        )
    }

    @Test
    fun parsePlayableAudio_preferM4aHardFiltersToM4aAcrossQualityTiers() {
        // #Y3: LOW/HIGH 挡位下旧逻辑会因排序反转把更高码率的 webm 排到前面
        // preferM4a 必须硬性优先可打标的 m4a, 避免下到 webm
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

        val lowQualityDownload = YouTubeMusicPlaybackParser.parsePlayableAudio(
            root = root,
            preferredQualityKey = "low",
            preferM4a = true
        )
        val highQualityDownload = YouTubeMusicPlaybackParser.parsePlayableAudio(
            root = root,
            preferredQualityKey = "high",
            preferM4a = true
        )

        assertNotNull(lowQualityDownload)
        assertEquals("audio/mp4", lowQualityDownload?.mimeType)
        assertNotNull(highQualityDownload)
        assertEquals("audio/mp4", highQualityDownload?.mimeType)
    }

    @Test
    fun parsePlayableAudio_prewarmsSignatureAndThrottlingBeforeResolvingEitherOne() {
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/webm",
                    "signatureCipher": "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-4%26n%3Dobfuscated-n&sp=signature&s=encrypted-signature",
                    "bitrate": 160000,
                    "audioSampleRate": "48000",
                    "approxDurationMs": "70000"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val calls = mutableListOf<String>()
        val cipherResolver = object : YouTubeStreamingCipherResolver {
            override fun prewarmChallenges(
                encryptedSignature: String?,
                obfuscatedThrottlingParameter: String?
            ) {
                calls.add("prewarm:$encryptedSignature/$obfuscatedThrottlingParameter")
            }

            override fun resolveSignature(encryptedSignature: String): String {
                calls.add("signature")
                return "decoded-signature"
            }

            override fun resolveStreamingUrl(url: String): String {
                calls.add("throttling")
                return url.replace("obfuscated-n", "deobfuscated-n")
            }
        }

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(
            root = root,
            cipherResolver = cipherResolver
        )

        // 合并求解必须发生在两次单独求解之前, 否则缓存暖不上等于白发一次
        assertEquals(
            listOf("prewarm:encrypted-signature/obfuscated-n", "signature", "throttling"),
            calls.toList()
        )
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-4&n=deobfuscated-n&signature=decoded-signature",
            playableAudio?.url
        )
    }

    @Test
    fun parsePlayableAudio_fallsBackToTheNextCandidateWhenTheFirstResolvesBlank() {
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/webm",
                    "signatureCipher": "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-high%26n%3Dbad-n&sp=signature&s=encrypted-high",
                    "bitrate": 160000,
                    "audioSampleRate": "48000",
                    "approxDurationMs": "70000"
                  },
                  {
                    "mimeType": "audio/webm",
                    "signatureCipher": "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-mid%26n%3Dgood-n&sp=signature&s=encrypted-mid",
                    "bitrate": 128000,
                    "audioSampleRate": "48000",
                    "approxDurationMs": "70000"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val prewarmed = mutableListOf<String>()
        val cipherResolver = object : YouTubeStreamingCipherResolver {
            override fun prewarmChallenges(
                encryptedSignature: String?,
                obfuscatedThrottlingParameter: String?
            ) {
                prewarmed.add("$encryptedSignature/$obfuscatedThrottlingParameter")
            }

            override fun resolveSignature(encryptedSignature: String): String = "decoded"

            override fun resolveStreamingUrl(url: String): String {
                // 解不出 n 的候选用空串表示不可用, 解析要继续往下一个候选走
                return if (url.contains("bad-n")) "" else url.replace("good-n", "deobfuscated-n")
            }
        }

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(
            root = root,
            cipherResolver = cipherResolver
        )

        // 每个被丢掉的候选都单独付了一次合并求解, 这正是回退的代价
        assertEquals(
            listOf("encrypted-high/bad-n", "encrypted-mid/good-n"),
            prewarmed.toList()
        )
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=audio-mid&n=deobfuscated-n&signature=decoded",
            playableAudio?.url
        )
    }

    @Test
    fun parsePlayableAudio_respectsCandidateLimitForFastPlaybackFallback() {
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/webm",
                    "signatureCipher": "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-high%26n%3Dbad-n&sp=signature&s=encrypted-high",
                    "bitrate": 160000,
                    "audioSampleRate": "48000"
                  },
                  {
                    "mimeType": "audio/webm",
                    "signatureCipher": "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-mid%26n%3Dgood-n&sp=signature&s=encrypted-mid",
                    "bitrate": 128000,
                    "audioSampleRate": "48000"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val prewarmed = mutableListOf<String>()
        val cipherResolver = object : YouTubeStreamingCipherResolver {
            override fun prewarmChallenges(
                encryptedSignature: String?,
                obfuscatedThrottlingParameter: String?
            ) {
                prewarmed.add("$encryptedSignature/$obfuscatedThrottlingParameter")
            }

            override fun resolveSignature(encryptedSignature: String): String = "decoded"

            override fun resolveStreamingUrl(url: String): String {
                return if (url.contains("bad-n")) "" else url
            }
        }

        val playableAudio = YouTubeMusicPlaybackParser.parsePlayableAudio(
            root = root,
            cipherResolver = cipherResolver,
            maxCandidateCount = 1
        )

        assertNull(playableAudio)
        assertEquals(listOf("encrypted-high/bad-n"), prewarmed)
    }

    @Test
    fun parsePlayableAudio_returnsNullWhenEveryCandidateResolvesBlank() {
        val root = JSONObject(
            """
            {
              "streamingData": {
                "adaptiveFormats": [
                  {
                    "mimeType": "audio/webm",
                    "signatureCipher": "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Daudio-high%26n%3Dbad-n&sp=signature&s=encrypted-high",
                    "bitrate": 160000,
                    "audioSampleRate": "48000",
                    "approxDurationMs": "70000"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val cipherResolver = object : YouTubeStreamingCipherResolver {
            override fun resolveSignature(encryptedSignature: String): String = "decoded"
            override fun resolveStreamingUrl(url: String): String = ""
        }

        assertNull(
            YouTubeMusicPlaybackParser.parsePlayableAudio(
                root = root,
                cipherResolver = cipherResolver
            )
        )
    }
}
