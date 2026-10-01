package moe.ouom.neriplayer.api.youtube.potoken

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlaybackManifestPoTokenTest {
    @Test
    fun `appends token as manifest path segment and preserves existing token`() {
        val master = "https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/"
        val withToken = appendWebRemixManifestPoToken(master, "token-123")
        assertEquals("${master}pot/token-123", withToken)
        assertTrue(hasWebRemixManifestPoToken(withToken))
        assertEquals(withToken, appendWebRemixManifestPoToken(withToken, "another"))
        assertEquals(master, appendWebRemixManifestPoToken(master, ""))
    }

    @Test
    fun `uses query token outside manifest path and carries token to playlist`() {
        val master = "https://manifest.googlevideo.com/playlist/master.m3u8"
        val playlist = "https://manifest.googlevideo.com/playlist/audio.m3u8"
        val withToken = appendWebRemixManifestPoToken(master, "token-123")
        assertEquals("$master?pot=token-123", withToken)
        assertEquals("$playlist?pot=token-123", carryForwardWebRemixManifestPoToken(withToken, playlist))
        assertEquals(playlist, carryForwardWebRemixManifestPoToken(master, playlist))
        assertFalse(hasWebRemixManifestPoToken(master))
    }

    @Test
    fun `path token is carried to manifest playlist without overwriting its own token`() {
        val master = "https://manifest.googlevideo.com/api/manifest/hls_variant/id/demo/pot/master-token"
        val playlist = "https://manifest.googlevideo.com/api/manifest/hls_playlist/id/demo"
        assertEquals("$playlist/pot/master-token", carryForwardWebRemixManifestPoToken(master, playlist))
        assertEquals("$playlist?pot=existing", carryForwardWebRemixManifestPoToken(master, "$playlist?pot=existing"))
        assertEquals("", carryForwardWebRemixManifestPoToken(master, ""))
    }
}
