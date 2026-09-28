package moe.ouom.neriplayer.core.api.youtube

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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

    private fun streaming(data: String): JSONObject = JSONObject("""{"streamingData":$data}""")
}
