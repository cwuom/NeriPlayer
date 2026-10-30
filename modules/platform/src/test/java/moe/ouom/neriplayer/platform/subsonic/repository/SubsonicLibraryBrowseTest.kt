package moe.ouom.neriplayer.platform.subsonic.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicClient
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicCredentials
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SubsonicLibraryBrowseTest {
    private val profile = SubsonicProfile("9e8a8fc4-1e35-4b5a-884d-8bb2423cc791", "Server", "https://example.test/", "user")

    private fun repository(cache: SubsonicBrowseCache, handler: (okhttp3.HttpUrl) -> String): SubsonicRepository {
        val accounts = mock(SubsonicAccounts::class.java)
        `when`(accounts.profiles).thenReturn(MutableStateFlow(listOf(profile)))
        `when`(accounts.profile(profile.id)).thenReturn(profile)
        `when`(accounts.credentials(profile.id)).thenReturn(SubsonicCredentials(profile, "test-password"))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val result = handler(chain.request().url)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"subsonic-response":{"status":"ok",$result}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return SubsonicRepository(accounts, SubsonicClient(client), cache)
    }

    @Test fun `album artist query requests only albums with its own pagination`() = runTest {
        val repo = repository(SubsonicBrowseCache(scope = backgroundScope)) { url ->
            assertEquals("/rest/search3.view", url.encodedPath)
            assertEquals("歌手 / Artist", url.queryParameter("query"))
            assertEquals("30", url.queryParameter("albumOffset"))
            assertEquals("1", url.queryParameter("albumCount"))
            assertEquals("0", url.queryParameter("songCount"))
            assertEquals("0", url.queryParameter("artistCount"))
            """"searchResult3":{"album":[{"id":"album / 中文","name":"Album","artist":"歌手 / Artist","songCount":12}],"song":[{"id":"ignored","title":"Song"}]}"""
        }
        val key = repo.browseKey(profile.id, query = " 歌手 / Artist ", offset = 30, size = 1)
        val page = repo.browse(key)
        assertEquals("search-albums", key.kind)
        assertEquals("album / 中文", page.albums.single().id)
        assertEquals("歌手 / Artist", page.albums.single().artist)
        assertTrue(page.songs.isEmpty())
        assertTrue(page.hasMore)
    }

    @Test fun `blank song category enumerates all songs instead of falling back to albums`() = runTest {
        val repo = repository(SubsonicBrowseCache(scope = backgroundScope)) { url ->
            assertEquals("/rest/search3.view", url.encodedPath)
            assertTrue("query" in url.queryParameterNames)
            assertEquals("", url.queryParameter("query"))
            assertEquals("30", url.queryParameter("songOffset"))
            assertEquals("2", url.queryParameter("songCount"))
            assertEquals("0", url.queryParameter("albumCount"))
            """"searchResult3":{"song":[{"id":"track / 中文","title":"Track","artist":"Artist","album":"Album"}]}"""
        }
        val key = repo.browseKey(profile.id, offset = 30, size = 2, category = ServerLibraryCategory.SONGS)
        val page = repo.browse(key)
        assertEquals("songs", key.kind)
        assertTrue(key.persistent)
        assertTrue(page.albums.isEmpty())
        assertEquals("track / 中文", ServerSongRef.from(page.songs.single())?.songId)
        assertFalse(page.hasMore)
    }

    @Test fun `song artist query remains song search and empty final page ends pagination`() = runTest {
        val repo = repository(SubsonicBrowseCache(scope = backgroundScope)) { url ->
            assertEquals("Artist", url.queryParameter("query"))
            assertEquals("60", url.queryParameter("songOffset"))
            assertEquals("0", url.queryParameter("albumCount"))
            """"searchResult3":{}"""
        }
        val page = repo.browse(repo.browseKey(profile.id, query = "Artist", offset = 60, category = ServerLibraryCategory.SONGS))
        assertTrue(page.songs.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test fun `song directory round trips through Room adapter with identity and pagination intact`() = runTest {
        var stored: PlatformPlaylistCacheRecord? = null
        val storage = mock(PlatformPlaylistCacheRoomStore::class.java) { invocation ->
            when (invocation.method.name) {
                "replaceBounded" -> { stored = invocation.arguments[0] as PlatformPlaylistCacheRecord; Unit }
                "read" -> stored
                else -> null
            }
        }
        val store = SubsonicBrowseRoomStore(storage)
        val key = ServerBrowseKey(profile.id, profile.revision, "songs", offset = 30)
        val ref = ServerSongRef(profile.id, "track / 中文")
        val song = SongItem(ref.numericId, "Track", "Artist", "Album", 0, 123_000, null,
            mediaUri = ref.mediaUri, channelId = ServerSongRef.CHANNEL, audioId = ref.audioId)
        val page = ServerBrowsePage(songs = listOf(song), savedAtMs = 100, hasMore = true)
        store.write(key, page)
        assertEquals(page, store.read(key))
        assertEquals("songs", stored?.kind)
    }
}
