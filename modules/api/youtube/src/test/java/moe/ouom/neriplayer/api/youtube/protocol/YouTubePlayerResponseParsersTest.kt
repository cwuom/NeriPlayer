package moe.ouom.neriplayer.api.youtube.protocol

import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.api.youtube.challenge.YouTubeStreamingCipherResolver
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlayerResponseParsersTest {
    @Test
    fun metadataKeepsDurationAndMimeIndependentOfPlayableUrl() {
        assertNull(YouTubeMusicPlaybackParser.parsePreferredAudioMetadata(JSONObject()))

        val durationOnly = JSONObject().put(
            "videoDetails",
            JSONObject().put("lengthSeconds", "42")
        )
        assertEquals(42_000L, YouTubeMusicPlaybackParser.parsePreferredAudioMetadata(durationOnly)?.durationMs)

        val mimeOnly = response(audioFormat("audio/mp4", url = ""))
        val metadata = YouTubeMusicPlaybackParser.parsePreferredAudioMetadata(mimeOnly)
        assertEquals("audio/mp4", metadata?.mimeType)
        assertEquals(0L, metadata?.durationMs)
    }

    @Test
    fun playableAudioUsesVideoDurationAndOmitsNonPositiveAudioProperties() {
        val root = response(
            audioFormat("audio/mp4", url = "https://rr1---sn.googlevideo.com/videoplayback?id=plain")
                .put("bitrate", 0)
                .put("audioSampleRate", "0")
                .put("approxDurationMs", "0")
        ).put("videoDetails", JSONObject().put("lengthSeconds", "8"))

        val audio = YouTubeMusicPlaybackParser.parsePlayableAudio(root)

        assertEquals(8_000L, audio?.durationMs)
        assertNull(audio?.bitrateKbps)
        assertNull(audio?.sampleRateHz)
    }

    @Test
    fun equalBitrateCandidatesPreferM4aContainer() {
        val webm = audioFormat("audio/webm", url = "https://example.com/webm")
            .put("bitrate", 128_000)
            .put("audioSampleRate", "48000")
        val m4a = audioFormat("audio/mp4", url = "https://example.com/m4a")
            .put("bitrate", 128_000)
            .put("audioSampleRate", "48000")

        val audio = YouTubeMusicPlaybackParser.parsePlayableAudio(response(webm, m4a))

        assertEquals("https://example.com/m4a", audio?.url)
    }

    @Test
    fun diagnosticsExposeCipherShapeWithoutLeakingTheStreamUrl() {
        val direct = audioFormat(
            "audio/mp4",
            url = "https://rr1---sn.googlevideo.com/videoplayback?id=direct&n=slow"
        )
        val cipher = audioFormat("audio/webm", url = "")
            .put(
                "signatureCipher",
                "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fid%3Dcipher%26n%3Dslow&signature=ready"
            )

        val description = YouTubeMusicPlaybackParser.describeAudioCandidates(response(direct, cipher))

        assertTrue(description.contains("direct=true,cipher=false,n=true,s=false,sig=false"))
        assertTrue(description.contains("direct=false,cipher=true,n=true,s=false,sig=true"))
        assertFalse(description.contains("rr1---sn.googlevideo.com"))
    }

    @Test
    fun asyncCipherResolvesSignatureBeforeTheStreamingUrl() = runBlocking {
        val cipher = audioFormat("audio/mp4", url = "")
            .put(
                "signatureCipher",
                "url=https%3A%2F%2Frr1---sn.googlevideo.com%2Fvideoplayback%3Fn%3Dslow&sp=signature&s=encrypted"
            )
        val calls = mutableListOf<String>()
        val resolver = object : YouTubeStreamingCipherResolver {
            override fun resolveSignature(encryptedSignature: String): String? =
                error("blocking signature resolver must not run")

            override fun resolveStreamingUrl(url: String): String =
                error("blocking stream resolver must not run")

            override suspend fun prewarmChallengesAsync(
                encryptedSignature: String?,
                obfuscatedThrottlingParameter: String?
            ) {
                calls += "prewarm:$encryptedSignature:$obfuscatedThrottlingParameter"
            }

            override suspend fun resolveSignatureAsync(encryptedSignature: String): String {
                calls += "signature:$encryptedSignature"
                return "resolved"
            }

            override suspend fun resolveStreamingUrlAsync(url: String): String {
                calls += "stream"
                return url.replace("n=slow", "n=ready")
            }
        }

        val audio = YouTubeMusicPlaybackParser.parsePlayableAudioAsync(response(cipher), cipherResolver = resolver)

        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?n=ready&signature=resolved",
            audio?.url
        )
        assertEquals(listOf("prewarm:encrypted:slow", "signature:encrypted", "stream"), calls)
    }

    @Test
    fun missingCipherUrlAndUnresolvableSignatureFallBackToAnotherCandidate() {
        val missingUrl = audioFormat("audio/mp4", url = "")
            .put("signatureCipher", "s=encrypted")
            .put("bitrate", 192_000)
        val blankSignature = audioFormat("audio/mp4", url = "")
            .put("signatureCipher", "url=https%3A%2F%2Fexample.com%2Fblank&s=")
            .put("bitrate", 160_000)
        val usable = audioFormat("audio/mp4", url = "https://example.com/usable")
            .put("bitrate", 128_000)

        val audio = YouTubeMusicPlaybackParser.parsePlayableAudio(
            response(missingUrl, blankSignature, usable)
        )

        assertEquals("https://example.com/usable", audio?.url)
    }

    @Test
    fun streamDetectionRequiresGoogleVideoHostAndYoutubeSource() {
        val valid = "https://rr1---sn.googlevideo.com/videoplayback?source=youtube"
        assertTrue(isYouTubeGoogleVideoStream(valid))
        assertFalse(isYouTubeGoogleVideoStream("https://rr1---sn.googlevideo.com/videoplayback"))
        assertFalse(isYouTubeGoogleVideoStream("https://example.com/videoplayback?source=youtube"))
        assertFalse(isYouTubeGoogleVideoStream("not a URL"))

        assertTrue(YouTubeMusicPlaybackParser.hasDirectGoogleVideoAudioStream(response(audioFormat("audio/mp4", valid))))
        assertFalse(YouTubeMusicPlaybackParser.hasDirectGoogleVideoAudioStream(
            response(audioFormat("video/mp4", valid), audioFormat("audio/mp4", "https://example.com/audio"))
        ))
    }

    @Test
    fun hlsIgnoresNonAudioAndBlankEntries() {
        val manifest = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=VIDEO,URI="https://example.com/video.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,URI=" "
            #EXT-X-MEDIA:TYPE=AUDIO,URI="https://example.com/audio.m3u8"
        """.trimIndent()

        val playlists = YouTubeMusicHlsManifestParser.collectAudioPlaylists(manifest)

        assertEquals(1, playlists.size)
        assertEquals("https://example.com/audio.m3u8", playlists.single().uri)
    }

    @Test
    fun hlsResolvesRelativeUriAndUsesContentLengthWhenItagIsUnknown() {
        val manifest = "#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio.m3u8?clen=200000\""

        val playlist = YouTubeMusicHlsManifestParser.selectAudioPlaylist(
            manifest,
            masterManifestUrl = "https://example.com/master.m3u8",
            durationMs = 10_000L
        )

        assertNotNull(playlist)
        assertEquals("https://example.com/audio.m3u8?clen=200000", playlist?.uri)
        assertEquals(160_000, playlist?.estimatedBitrate)
        assertNull(playlist?.audioItag)
    }

    @Test
    fun hlsReadsPathItagAndKeepsAbsoluteUriWhenMasterIsMalformed() {
        val absolute = "https://example.com/audio/itag/251/playlist.m3u8"
        val playlist = YouTubeMusicHlsManifestParser.selectAudioPlaylist(
            "#EXT-X-MEDIA:TYPE=AUDIO,URI=\"$absolute\"",
            masterManifestUrl = "not a URI",
        )

        assertEquals(absolute, playlist?.uri)
        assertEquals(251, playlist?.audioItag)
        assertEquals(256_000, playlist?.estimatedBitrate)

        val unresolved = YouTubeMusicHlsManifestParser.selectAudioPlaylist(
            "#EXT-X-MEDIA:TYPE=AUDIO,URI=\"relative.m3u8\"",
            masterManifestUrl = "http://[invalid",
        )
        assertEquals("relative.m3u8", unresolved?.uri)

        val withoutBase = YouTubeMusicHlsManifestParser.selectAudioPlaylist(
            "#EXT-X-MEDIA:TYPE=AUDIO,URI=\"relative.m3u8\"",
            masterManifestUrl = "",
        )
        assertEquals("relative.m3u8", withoutBase?.uri)
    }

    private fun audioFormat(mimeType: String, url: String): JSONObject = JSONObject()
        .put("mimeType", mimeType)
        .put("url", url)

    private fun response(vararg formats: JSONObject): JSONObject {
        val array = JSONArray()
        formats.forEach(array::put)
        return JSONObject().put("streamingData", JSONObject().put("adaptiveFormats", array))
    }
}
