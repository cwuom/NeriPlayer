package moe.ouom.neriplayer.core.api.netease

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseCommentActionTest {
    @Test
    fun `missing login rejects before requesting verification`(): Unit = runBlocking {
        val client = NeteaseClient { error("Verification must not start without login") }
        assertEquals(301, JSONObject(client.setSongCommentLiked(1L, "42", true)).getInt("code"))
        assertEquals(301, JSONObject(client.sendSongComment(1L, "text")).getInt("code"))
    }

    @Test
    fun `account switch during verification cannot send the old account action`(): Unit = runBlocking {
        lateinit var client: NeteaseClient
        client = NeteaseClient {
            client.logout()
            "test-check-token"
        }
        client.setPersistedCookies(mapOf("MUSIC_U" to "test-account", "__csrf" to "test-csrf"))
        assertEquals(301, JSONObject(client.setSongCommentLiked(1L, "42", true)).getInt("code"))
        client.setPersistedCookies(mapOf("MUSIC_U" to "test-account", "__csrf" to "test-csrf"))
        assertEquals(301, JSONObject(client.sendSongComment(1L, "text", "42")).getInt("code"))
    }

    @Test
    fun `empty verification fails locally without sending a write or inventing an api code`(): Unit = runBlocking {
        val client = NeteaseClient { "" }
        client.setPersistedCookies(mapOf("MUSIC_U" to "test-account", "__csrf" to "test-csrf"))
        val failure = runCatching { client.setSongCommentLiked(1L, "42", true) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("NetEase comment verification unavailable", failure?.message)
        assertTrue(runCatching { client.sendSongComment(1L, "text") }.exceptionOrNull() is IllegalStateException)
    }
}
