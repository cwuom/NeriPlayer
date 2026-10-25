package moe.ouom.neriplayer.platform.netease.playlist

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient
import moe.ouom.neriplayer.platform.netease.api.client.NeteaseRequestSessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.io.IOException

class NeteasePlaylistLikeFallbackTest {
    private val noPause: (Long) -> Unit = { error("unexpected backoff") }
    private val rejected = """{"code":524,"message":"当前环境异常，已取消喜欢"}"""

    @Test
    fun `liked playlist single-song 524 uses like without losing healthy batch members`() = runTest {
        for (likedEntry in listOf(false, true)) {
            val client = mixedBatchClient()
            `when`(client.likeSong(2L)).thenReturn("""{"code":200}""")
            val sync = NeteasePlaylistSync(noPause) { it.name }

            val result = if (likedEntry) {
                sync.syncSongsToNeteaseLiked(client, songs())
            } else {
                sync.syncSongsToNeteasePlaylist(client, 91L, songs())
            }

            assertEquals(2, result.added)
            assertEquals(0, result.failed)
            assertEquals(emptyList<Long>(), result.rejectedSongIds)
            assertNull(result.rejectionMessage)
            verify(client).likeSong(2L)
            verify(client, never()).likeSong(1L)
        }
    }

    @Test
    fun `liked identity is resolved once when multiple single-song rejections need fallback`() = runTest {
        val client = mixedBatchClient()
        `when`(client.addSongsToPlaylist(eq(91L), anyList())).thenReturn(rejected)
        `when`(client.likeSong(1L)).thenReturn("""{"code":200}""")
        `when`(client.likeSong(2L)).thenReturn("""{"code":200}""")

        val result = NeteasePlaylistSync(noPause) { it.name }
            .syncSongsToNeteasePlaylist(client, 91L, songs())

        assertEquals(2, result.added)
        assertEquals(0, result.failed)
        verify(client).getLikedPlaylistId(0L)
        verify(client).likeSong(1L)
        verify(client).likeSong(2L)
    }

    @Test
    fun `a temporary identity failure does not block fallback for a later song`() = runTest {
        val client = mixedBatchClient()
        `when`(client.addSongsToPlaylist(eq(91L), anyList())).thenReturn(rejected)
        `when`(client.getLikedPlaylistId(0L)).thenReturn(
            """{"code":503}""",
            """{"code":200,"playlistId":91}"""
        )
        `when`(client.likeSong(2L)).thenReturn("""{"code":200}""")

        val result = NeteasePlaylistSync(noPause) { it.name }
            .syncSongsToNeteasePlaylist(client, 91L, songs())

        assertEquals(1, result.added)
        assertEquals(1, result.failed)
        assertEquals(listOf(1L), result.rejectedSongIds)
        verify(client, times(2)).getLikedPlaylistId(0L)
        verify(client, never()).likeSong(1L)
        verify(client).likeSong(2L)
    }

    @Test
    fun `failed identity session refresh can recover for a later song`() = runTest {
        val client = mixedBatchClient()
        `when`(client.addSongsToPlaylist(eq(91L), anyList())).thenReturn(rejected)
        `when`(client.getLikedPlaylistId(0L)).thenReturn(
            """{"code":301}""",
            """{"code":301}""",
            """{"code":200,"playlistId":91}"""
        )
        `when`(client.likeSong(2L)).thenReturn("""{"code":200}""")

        val result = NeteasePlaylistSync(noPause) { it.name }
            .syncSongsToNeteasePlaylist(client, 91L, songs())

        assertEquals(1, result.added)
        assertEquals(1, result.failed)
        assertEquals(listOf(1L), result.rejectedSongIds)
        verify(client, times(3)).getLikedPlaylistId(0L)
        verify(client, times(2)).ensureWeapiSession()
        verify(client, never()).likeSong(1L)
        verify(client).likeSong(2L)
    }

    @Test
    fun `confirmed absent or different liked identity is cached without liking songs`() = runTest {
        for (identity in listOf(
            """{"code":200}""",
            """{"code":200,"playlistId":92}""",
            """{"code":404,"neriplayerLikedPlaylistNotFound":true}"""
        )) {
            val client = mixedBatchClient()
            `when`(client.addSongsToPlaylist(eq(91L), anyList())).thenReturn(rejected)
            `when`(client.getLikedPlaylistId(0L)).thenReturn(identity)

            val result = NeteasePlaylistSync(noPause) { it.name }
                .syncSongsToNeteasePlaylist(client, 91L, songs())

            assertEquals(0, result.added)
            assertEquals(2, result.failed)
            assertEquals(listOf(1L, 2L), result.rejectedSongIds)
            verify(client).getLikedPlaylistId(0L)
            verify(client, never()).likeSong(1L)
            verify(client, never()).likeSong(2L)
        }
    }

    @Test
    fun `account switch while resolving liked identity cannot like a song in the new account`() = runTest {
        val client = mixedBatchClient()
        val account = NeteaseClient { error("Comment token is outside this test") }
        account.setPersistedCookies(mapOf("MUSIC_U" to "test-account-a"))
        blockRequests(account)
        `when`(client.createSongLiker()).thenAnswer { account.createSongLiker() }
        `when`(client.getLikedPlaylistId(0L)).thenAnswer {
            account.setPersistedCookies(mapOf("MUSIC_U" to "test-account-b"))
            blockRequests(account)
            """{"code":200,"playlistId":91}"""
        }
        `when`(client.likeSong(2L)).thenReturn("""{"code":200}""")

        val result = NeteasePlaylistSync(noPause) { it.name }
            .syncSongsToNeteasePlaylist(client, 91L, songs())

        assertEquals(1, result.added)
        assertEquals(1, result.failed)
        assertEquals(listOf(2L), result.rejectedSongIds)
        assertEquals("code 301", result.rejectionMessage)
        verify(client, never()).likeSong(2L)
        verify(client).createSongLiker()
    }

    @Test
    fun `ordinary or unconfirmed playlist never falls back to liking songs`() = runTest {
        for (identity in listOf(
            """{"code":200,"playlistId":92}""",
            """{"code":404}""",
            """{"code":301}"""
        )) {
            val client = mixedBatchClient()
            `when`(client.getLikedPlaylistId(0L)).thenReturn(identity)

            val result = NeteasePlaylistSync(noPause) { it.name }
                .syncSongsToNeteasePlaylist(client, 91L, songs())

            assertEquals(1, result.added)
            assertEquals(1, result.failed)
            assertEquals(listOf(2L), result.rejectedSongIds)
            assertEquals("当前环境异常，已取消喜欢", result.rejectionMessage)
            verify(client, never()).likeSong(2L)
        }
    }

    @Test
    fun `failed like fallback keeps the song rejected with the actual server reason`() = runTest {
        val client = mixedBatchClient()
        `when`(client.likeSong(2L)).thenReturn(rejected)

        val result = NeteasePlaylistSync(noPause) { it.name }
            .syncSongsToNeteasePlaylist(client, 91L, songs())

        assertEquals(1, result.added)
        assertEquals(1, result.failed)
        assertEquals(listOf(2L), result.rejectedSongIds)
        assertEquals("当前环境异常，已取消喜欢", result.rejectionMessage)
        verify(client).likeSong(2L)
    }

    @Test
    fun `a temporary like outage backs off and recovers without rejecting the song`() = runTest {
        val client = mixedBatchClient()
        `when`(client.likeSong(2L)).thenThrow(IOException("temporary outage")).thenReturn("""{"code":200}""")
        val pauses = mutableListOf<Long>()

        val result = NeteasePlaylistSync(pauses::add) { it.name }
            .syncSongsToNeteasePlaylist(client, 91L, songs())

        assertEquals(2, result.added)
        assertEquals(0, result.failed)
        assertEquals(emptyList<Long>(), result.rejectedSongIds)
        assertEquals(listOf(1_000L), pauses)
        verify(client, times(2)).likeSong(2L)
        verify(client).getLikedPlaylistId(0L)
    }

    @Test
    fun `persistent like outages leave healthy sibling ranges and later batches available`() = runTest {
        for (response in listOf(null, "invalid", """{"code":503}""")) {
            val client = mixedBatchClient()
            `when`(client.addSongsToPlaylist(eq(91L), anyList())).thenAnswer { invocation ->
                if (1L in invocation.getArgument<List<Long>>(1)) rejected else """{"code":200}"""
            }
            if (response == null) {
                `when`(client.likeSong(1L)).thenThrow(IOException("persistent like outage"))
            } else {
                `when`(client.likeSong(1L)).thenReturn(response)
            }
            val pauses = mutableListOf<Long>()
            val songs = (1L..100L).map { id ->
                SongItem(id, "song-$id", "artist", "NeteaseAlbum", 7L, 1_000L, null,
                    channelId = "netease", audioId = id.toString())
            }

            val result = NeteasePlaylistSync(pauses::add) { it.name }
                .syncSongsToNeteasePlaylist(client, 91L, songs)

            assertEquals(99, result.added)
            assertEquals(1, result.failed)
            assertEquals(listOf(1L), result.rejectedSongIds)
            assertEquals("当前环境异常，已取消喜欢", result.rejectionMessage)
            assertEquals(listOf(1_000L, 2_000L, 4_000L), pauses)
            verify(client, times(4)).likeSong(1L)
            verify(client).addSongsToPlaylist(91L, (51L..100L).toList())
        }
    }

    @Test
    fun `like recovery shares a retry budget without stopping healthy songs`() = runTest {
        val client = mixedBatchClient()
        `when`(client.addSongsToPlaylist(eq(91L), anyList())).thenAnswer { invocation ->
            if (invocation.getArgument<List<Long>>(1).any { it <= 2L }) rejected else """{"code":200}"""
        }
        `when`(client.likeSong(1L)).thenReturn("""{"code":503}""")
        `when`(client.likeSong(2L)).thenReturn("""{"code":503}""")
        val pauses = mutableListOf<Long>()
        val thirdSong = SongItem(3L, "song-3", "artist", "NeteaseAlbum", 7L, 1_000L, null,
            channelId = "netease", audioId = "3")

        val result = NeteasePlaylistSync(pauses::add) { it.name }
            .syncSongsToNeteasePlaylist(client, 91L, songs() + thirdSong)

        assertEquals(1, result.added)
        assertEquals(2, result.failed)
        assertEquals(listOf(1L, 2L), result.rejectedSongIds)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), pauses)
        verify(client, times(4)).likeSong(1L)
        verify(client).likeSong(2L)
        verify(client).addSongsToPlaylist(91L, listOf(3L))
    }

    @Test
    fun `switching accounts during like backoff keeps the original bound callback`() = runTest {
        val client = mixedBatchClient()
        val account = NeteaseClient { error("Comment token is outside this test") }
        account.setPersistedCookies(mapOf("MUSIC_U" to "test-account-a"))
        blockRequests(account)
        `when`(client.createSongLiker()).thenAnswer {
            val bound = account.createSongLiker()
            var firstAttempt = true
            { id: Long ->
                if (firstAttempt) {
                    firstAttempt = false
                    """{"code":503}"""
                } else {
                    bound(id)
                }
            }
        }
        val pauses = mutableListOf<Long>()

        val result = NeteasePlaylistSync({ delay ->
            pauses += delay
            account.setPersistedCookies(mapOf("MUSIC_U" to "test-account-b"))
            blockRequests(account)
        }) { it.name }.syncSongsToNeteasePlaylist(client, 91L, songs())

        assertEquals(1, result.added)
        assertEquals(1, result.failed)
        assertEquals(listOf(2L), result.rejectedSongIds)
        assertEquals("code 301", result.rejectionMessage)
        assertEquals(listOf(1_000L), pauses)
        verify(client).createSongLiker()
        verify(client, never()).likeSong(2L)
    }

    @Test
    fun `explicit like authorization rejection preserves the server reason without backoff`() = runTest {
        val client = mixedBatchClient()
        `when`(client.likeSong(2L)).thenReturn("""{"code":401,"message":"not authorized"}""")

        val result = NeteasePlaylistSync(noPause) { it.name }
            .syncSongsToNeteasePlaylist(client, 91L, songs())

        assertEquals(1, result.added)
        assertEquals(1, result.failed)
        assertEquals(listOf(2L), result.rejectedSongIds)
        assertEquals("not authorized", result.rejectionMessage)
        verify(client).likeSong(2L)
    }

    @Test
    fun `like fallback retries an expired session once`() = runTest {
        val client = mixedBatchClient()
        `when`(client.likeSong(2L)).thenReturn("""{"code":301}""", """{"code":200}""")

        val result = NeteasePlaylistSync(noPause) { it.name }
            .syncSongsToNeteasePlaylist(client, 91L, songs())

        assertEquals(2, result.added)
        assertEquals(0, result.failed)
        verify(client, times(2)).likeSong(2L)
        verify(client, times(2)).ensureWeapiSession()
    }

    @Test
    fun `persistent session failure never turns a failed like into success`() = runTest {
        val client = mixedBatchClient()
        `when`(client.likeSong(2L)).thenReturn("""{"code":301}""")

        val result = NeteasePlaylistSync(noPause) { it.name }
            .syncSongsToNeteasePlaylist(client, 91L, songs())

        assertEquals(1, result.added)
        assertEquals(1, result.failed)
        assertEquals(listOf(2L), result.rejectedSongIds)
        assertEquals("code 301", result.rejectionMessage)
        verify(client, times(2)).likeSong(2L)
    }

    @Test
    fun `other single-song errors never trigger like fallback`() = runTest {
        val client = mixedBatchClient()
        `when`(client.addSongsToPlaylist(91L, listOf(2L))).thenReturn("""{"code":500,"message":"rejected"}""")

        val result = NeteasePlaylistSync(noPause) { it.name }
            .syncSongsToNeteasePlaylist(client, 91L, songs())

        assertEquals(1, result.added)
        assertEquals(1, result.failed)
        assertEquals("rejected", result.rejectionMessage)
        verify(client, never()).getLikedPlaylistId(0L)
        verify(client, never()).likeSong(2L)
    }

    private fun blockRequests(client: NeteaseClient) {
        val store = NeteaseClient::class.java.getDeclaredField("sessionStore").run {
            isAccessible = true
            get(client) as NeteaseRequestSessionStore
        }
        val session = store.currentSession()
        val offlineClient = session.okHttpClient.newBuilder().addInterceptor {
            error("Unexpected request after account switch")
        }.build()
        session.javaClass.getDeclaredField("okHttpClient").apply {
            isAccessible = true
            set(session, offlineClient)
        }
    }

    private fun mixedBatchClient(): NeteaseClient {
        val client = mock(NeteaseClient::class.java)
        `when`(client.hasLogin()).thenReturn(true)
        `when`(client.createSongLiker()).thenReturn { id -> client.likeSong(id) }
        `when`(client.getLikedPlaylistId(0L)).thenReturn("""{"code":200,"playlistId":91}""")
        `when`(client.getPlaylistDetail(91L))
            .thenReturn("""{"code":200,"playlist":{"trackIds":[],"trackCount":0}}""")
        `when`(client.getSongDetail(anyList())).thenAnswer { invocation ->
            val ids = invocation.getArgument<List<Long>>(0)
            """{"code":200,"songs":[${ids.joinToString(",") { id ->
                """{"id":$id,"name":"song-$id","ar":[{"name":"artist"}],"dt":1000}"""
            }}]}"""
        }
        `when`(client.addSongsToPlaylist(eq(91L), anyList())).thenAnswer { invocation ->
            if (2L in invocation.getArgument<List<Long>>(1)) rejected else """{"code":200}"""
        }
        return client
    }

    private fun songs(): List<SongItem> = (1L..2L).map { id ->
        SongItem(id, "song-$id", "artist", "NeteaseAlbum", 7L, 1_000L, null,
            channelId = "netease", audioId = id.toString())
    }
}
