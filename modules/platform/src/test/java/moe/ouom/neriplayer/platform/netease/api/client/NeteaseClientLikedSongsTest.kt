package moe.ouom.neriplayer.platform.netease.api.client

import moe.ouom.neriplayer.data.model.netease.crypto.CryptoMode
import moe.ouom.neriplayer.platform.netease.playlist.parseNeteaseLikedPlaylistId
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

class NeteaseClientLikedSongsTest {

    @Test
    fun `liking a song uses the radio like weapi contract with default playback time`() {
        val response = """{"code":200,"playlistId":88}"""
        val client = clientWithResponse(response)

        assertEquals(response, client.likeSong(42L))

        val params = mapOf<String, Any>(
            "alg" to "itembased",
            "trackId" to "42",
            "like" to true,
            "time" to "3"
        )
        verify(client).callWeApi("/radio/like", params, true)
        verify(client).request(
            "https://music.163.com/weapi/radio/like",
            params,
            CryptoMode.WEAPI,
            "POST",
            true,
            false
        )
    }

    @Test
    fun `unliking a song keeps the boolean and explicit playback time`() {
        val client = clientWithResponse("""{"code":200}""")

        client.likeSong(42L, like = false, time = 12L)

        verify(client).request(
            "https://music.163.com/weapi/radio/like",
            mapOf("alg" to "itembased", "trackId" to "42", "like" to false, "time" to "12"),
            CryptoMode.WEAPI,
            "POST",
            true,
            false
        )
    }

    @Test
    fun `liked playlist requires owned special type and a positive id regardless of name`() {
        val client = clientWithResponse(
            """
            {"code":200,"playlist":[
              {"id":10,"name":"我喜欢的音乐备份","specialType":0,"creator":{"userId":7}},
              {"id":11,"name":"我喜欢的音乐","specialType":5,"creator":{"userId":8}},
              {"id":0,"name":"我喜欢的音乐","specialType":5,"creator":{"userId":7}},
              {"id":-12,"name":"我喜欢的音乐","specialType":5,"creator":{"userId":7}},
              {"id":88,"name":"renamed favorites","specialType":5,"creator":{"userId":7}}
            ]}
            """.trimIndent()
        )

        val result = JSONObject(client.getLikedPlaylistId(7L))

        assertEquals(200, result.getInt("code"))
        assertEquals(88L, result.getLong("playlistId"))
    }

    @Test
    fun `ordinary playlists named like favorites cannot become the liked playlist`() {
        val client = clientWithResponse(
            """{"code":200,"playlist":[
              {"id":10,"name":"我喜欢的音乐","specialType":0,"creator":{"userId":7}},
              {"id":11,"name":"我喜欢的音乐","specialType":5,"creator":{"userId":8}}
            ]}"""
        )

        assertEquals(404, JSONObject(client.getLikedPlaylistId(7L)).getInt("code"))
    }

    @Test
    fun `a successful playlist lookup can confirm that the liked playlist is absent`() {
        for (response in listOf(
            """{"code":200,"playlist":[]}""",
            """{"code":200,"playlist":[{"id":10,"specialType":0,"creator":{"userId":7}}]}"""
        )) {
            val raw = clientWithResponse(response).getLikedPlaylistId(7L)
            val parsed = parseNeteaseLikedPlaylistId(raw)

            assertEquals(404, JSONObject(raw).getInt("code"))
            assertTrue(parsed.success)
            assertNull(parsed.playlistId)
        }
    }

    @Test
    fun `a missing or malformed playlist list cannot confirm that favorites are absent`() {
        for (response in listOf(
            """{"code":200}""",
            """{"code":200,"playlist":null}""",
            """{"code":200,"playlist":{}}""",
            """{"code":200,"playlist":"invalid"}"""
        )) {
            val raw = clientWithResponse(response).getLikedPlaylistId(7L)
            val parsed = parseNeteaseLikedPlaylistId(raw)

            assertEquals(500, JSONObject(raw).getInt("code"))
            assertFalse(parsed.success)
            assertNull(parsed.playlistId)
        }
    }

    @Test
    fun `liked playlist without a positive id is not a usable write target`() {
        val client = clientWithResponse(
            """{"code":200,"playlist":[
              {"specialType":5,"creator":{"userId":7}},
              {"id":0,"specialType":5,"creator":{"userId":7}},
              {"id":-12,"specialType":5,"creator":{"userId":7}}
            ]}"""
        )

        assertEquals(404, JSONObject(client.getLikedPlaylistId(7L)).getInt("code"))
    }

    @Test
    fun `upstream errors retain their code and message even if a playlist is present`() {
        val responses = listOf(
            """{"code":301,"message":"login required"}""",
            """{"code":301,"message":"session expired","playlist":[
              {"id":88,"specialType":5,"creator":{"userId":7}}
            ]}""",
            """{"code":404,"message":"liked playlist not found"}""",
            """{"code":503,"message":"service unavailable"}""",
            """{"code":500,"message":"service unavailable"}"""
        )

        for (response in responses) {
            assertEquals(response, clientWithResponse(response).getLikedPlaylistId(7L))
        }
    }

    @Test
    fun `zero user id resolves the account before matching the owned liked playlist`() {
        val client = clientWithResponses { url ->
            when (url) {
                "https://music.163.com/weapi/w/nuser/account/get" ->
                    """{"code":200,"profile":{"userId":7}}"""
                "https://music.163.com/weapi/user/playlist" ->
                    """{"code":200,"playlist":[
                      {"id":11,"specialType":5,"creator":{"userId":8}},
                      {"id":88,"specialType":5,"creator":{"userId":7}}
                    ]}"""
                else -> error("Unexpected request: $url")
            }
        }

        val result = JSONObject(client.getLikedPlaylistId(0L))

        assertEquals(88L, result.getLong("playlistId"))
        verify(client).request(
            "https://music.163.com/weapi/user/playlist",
            mapOf("uid" to "7", "offset" to "0", "limit" to "1000", "includeVideo" to "true"),
            CryptoMode.WEAPI,
            "POST",
            true,
            false
        )
    }

    @Test
    fun `song liker created without login cannot send a request`() {
        var requests = 0
        val client = clientWithResponses {
            requests++
            """{"code":200}"""
        }
        interceptSession(sessionStore(client).currentSession()) { requests++ }

        val likeSong = client.createSongLiker()

        assertEquals(301, JSONObject(likeSong(42L)).getInt("code"))
        assertEquals(0, requests)
    }

    @Test
    fun `bound song liker rejects another account before sending a request`() {
        assertBoundSongLikerRejected { client ->
            client.setPersistedCookies(mapOf("MUSIC_U" to "account-b", "__csrf" to "csrf-b"))
        }
    }

    @Test
    fun `bound song liker rejects logout before sending a request`() {
        assertBoundSongLikerRejected { client -> client.logout() }
    }

    @Test
    fun `returning to the same account cannot revive an earlier bound song liker`() {
        assertBoundSongLikerRejected { client ->
            client.setPersistedCookies(mapOf("MUSIC_U" to "account-b", "__csrf" to "csrf-b"))
            client.setPersistedCookies(mapOf("MUSIC_U" to "account-a", "__csrf" to "csrf-a"))
        }
    }

    @Test
    fun `bound song liker accepts cookie rotation within the same account session`() {
        val client = NeteaseClient { error("Comment token is outside this test") }
        client.setPersistedCookies(mapOf("MUSIC_U" to "account-a", "__csrf" to "csrf-a"))
        val capturedSession = sessionStore(client).currentSession()
        val requests = mutableListOf<Request>()
        interceptSession(capturedSession) { requests += it }
        val likeSong = client.createSongLiker()

        client.setPersistedCookies(mapOf("MUSIC_U" to "account-a", "__csrf" to "rotated-csrf"))

        assertSame(capturedSession, sessionStore(client).currentSession())
        assertEquals(200, JSONObject(likeSong(42L)).getInt("code"))
        assertEquals(1, requests.size)
        assertEquals("/weapi/radio/like", requests.single().url.encodedPath)
        assertEquals("rotated-csrf", requests.single().url.queryParameter("csrf_token"))
    }

    @Test
    fun `account switching after the identity check cannot change the request session`() {
        val client = NeteaseClient { error("Comment token is outside this test") }
        client.setPersistedCookies(mapOf("MUSIC_U" to "account-a", "__csrf" to "csrf-a"))
        val store = spy(sessionStore(client))
        val capturedSession = store.currentSession()
        val replacementSession = NeteaseRequestSession(
            mapOf("MUSIC_U" to "account-b", "__csrf" to "csrf-b")
        )
        val accountRequests = mutableListOf<String>()
        interceptSession(capturedSession) { accountRequests += "account-a" }
        interceptSession(replacementSession) { accountRequests += "account-b" }
        doReturn(capturedSession, capturedSession, replacementSession).`when`(store).currentSession()
        NeteaseClient::class.java.getDeclaredField("sessionStore").apply {
            isAccessible = true
            set(client, store)
        }

        val likeSong = client.createSongLiker()

        assertEquals(200, JSONObject(likeSong(42L)).getInt("code"))
        assertEquals(listOf("account-a"), accountRequests)
    }

    private fun assertBoundSongLikerRejected(changeAccount: (NeteaseClient) -> Unit) {
        var requests = 0
        val client = clientWithResponses {
            requests++
            """{"code":200}"""
        }
        client.setPersistedCookies(mapOf("MUSIC_U" to "account-a", "__csrf" to "csrf-a"))
        interceptSession(sessionStore(client).currentSession()) { requests++ }
        val likeSong = client.createSongLiker()

        changeAccount(client)
        interceptSession(sessionStore(client).currentSession()) { requests++ }

        assertEquals(301, JSONObject(likeSong(42L)).getInt("code"))
        assertEquals(0, requests)
    }

    private fun sessionStore(client: NeteaseClient): NeteaseRequestSessionStore {
        return NeteaseClient::class.java.getDeclaredField("sessionStore").run {
            isAccessible = true
            get(client) as NeteaseRequestSessionStore
        }
    }

    private fun interceptSession(session: NeteaseRequestSession, onRequest: (Request) -> Unit) {
        val offlineClient = session.okHttpClient.newBuilder().addInterceptor { chain ->
            onRequest(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"code":200}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        NeteaseRequestSession::class.java.getDeclaredField("okHttpClient").apply {
            isAccessible = true
            set(session, offlineClient)
        }
    }

    private fun clientWithResponse(response: String): NeteaseClient = clientWithResponses { response }

    private fun clientWithResponses(response: (String) -> String): NeteaseClient {
        val client = spy(NeteaseClient { error("Comment token is outside this test") })
        doAnswer { invocation -> response(invocation.getArgument(0)) }.`when`(client).request(
            anyString(),
            anyMap(),
            any(CryptoMode::class.java) ?: CryptoMode.WEAPI,
            anyString(),
            anyBoolean(),
            anyBoolean()
        )
        return client
    }
}
