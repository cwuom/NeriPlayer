package moe.ouom.neriplayer.platform.youtube.api.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlaybackStreamAccessOwnerTest {
    private val owner = YouTubePlaybackStreamAccessOwner(
        okHttpClient = OkHttpClient(),
        poTokenProvider = null,
        scope = CoroutineScope(Dispatchers.Unconfined)
    )

    @Test
    fun `diagnostic itag retains only numeric stream identifiers`() {
        val stream = "https://rr1---sn.googlevideo.com/videoplayback?source=youtube"
        assertEquals("234", diagnosticItag("$stream&itag=234"))
        assertEquals("<unknown>", diagnosticItag("$stream&itag=234bad"))
        assertEquals("<unknown>", diagnosticItag(stream))
    }

    @Test
    fun `prefetch is needed for tokenless manifest but not for embedded token`() {
        assertFalse(owner.shouldPrefetchWebRemixPoToken(JSONObject("{}")))
        assertTrue(owner.shouldPrefetchWebRemixPoToken(streaming("""
            {"hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo"}
        """)))
        assertFalse(owner.shouldPrefetchWebRemixPoToken(streaming("""
            {"hlsManifestUrl":"https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/pot/token"}
        """)))
    }

    @Test
    fun `prefetch scans audio formats and ignores non audio or already tokenized streams`() {
        assertFalse(owner.shouldPrefetchWebRemixPoToken(streaming("""
            {"adaptiveFormats":[
              {"mimeType":"video/mp4","url":"https://rr1---sn.googlevideo.com/videoplayback?source=youtube"},
              {"mimeType":"audio/mp4","url":"https://rr1---sn.googlevideo.com/videoplayback?source=youtube&pot=existing"}
            ]}
        """)))
        assertTrue(owner.shouldPrefetchWebRemixPoToken(streaming("""
            {"adaptiveFormats":[{"mimeType":"audio/mp4; codecs=aac","url":"https://rr1---sn.googlevideo.com/videoplayback?source=youtube"}]}
        """)))
        assertTrue(owner.shouldPrefetchWebRemixPoToken(streaming("""
            {"formats":[{"mimeType":"audio/webm","signatureCipher":"url=encoded"}]}
        """)))
        assertFalse(owner.shouldPrefetchWebRemixPoToken(streaming("""
            {"formats":[{"mimeType":"audio/webm","url":"https://example.org/audio.webm"}]}
        """)))
    }

    @Test
    fun `missing po token without a prefetch is reported as empty`() = runBlocking {
        val result = owner.resolveMissingWebRemixPoToken("demo", prefetchedPoToken = null, awaitLateToken = true)

        assertNull(result.token)
        assertEquals(YouTubePoTokenUnavailableReason.EMPTY, result.unavailableReason)
    }

    @Test
    fun `provider that already returned blank is reported as empty`() = runBlocking {
        listOf(true, false).forEach { awaitLateToken ->
            val result = owner.resolveMissingWebRemixPoToken(
                "demo",
                prefetchedPoToken = CompletableDeferred(" "),
                awaitLateToken = awaitLateToken
            )

            assertNull("awaitLateToken=$awaitLateToken", result.token)
            assertEquals(
                "awaitLateToken=$awaitLateToken",
                YouTubePoTokenUnavailableReason.EMPTY,
                result.unavailableReason
            )
        }
    }

    @Test
    fun `late token is recovered only while a retry can still use it`() = runBlocking {
        val recovered = owner.resolveMissingWebRemixPoToken(
            "demo",
            prefetchedPoToken = CompletableDeferred("late-token"),
            awaitLateToken = true
        )
        val tooLate = owner.resolveMissingWebRemixPoToken(
            "demo",
            prefetchedPoToken = CompletableDeferred("late-token"),
            awaitLateToken = false
        )

        assertEquals("late-token", recovered.token)
        assertNull(recovered.unavailableReason)
        assertNull(tooLate.token)
        assertEquals(YouTubePoTokenUnavailableReason.TIMEOUT, tooLate.unavailableReason)
    }

    @Test
    fun `pending token without a retry left is a timeout and does not wait`() = runBlocking {
        val startedAtMs = System.currentTimeMillis()

        val result = owner.resolveMissingWebRemixPoToken(
            "demo",
            prefetchedPoToken = CompletableDeferred(),
            awaitLateToken = false
        )

        assertNull(result.token)
        assertEquals(YouTubePoTokenUnavailableReason.TIMEOUT, result.unavailableReason)
        assertTrue(System.currentTimeMillis() - startedAtMs < 500L)
    }

    private fun streaming(data: String): JSONObject = JSONObject("""{"streamingData":$data}""")
}
