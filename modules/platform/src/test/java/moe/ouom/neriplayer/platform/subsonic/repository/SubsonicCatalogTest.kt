package moe.ouom.neriplayer.platform.subsonic.repository

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicClient
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicFailureKind
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicCredentials
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SubsonicCatalogTest {
    private val profile = SubsonicProfile("9e8a8fc4-1e35-4b5a-884d-8bb2423cc791", "Server", "https://example.test/", "user")

    private class Store : ServerBrowseStore {
        val pages = mutableMapOf<ServerBrowseKey, ServerBrowsePage>()
        override suspend fun read(key: ServerBrowseKey) = pages[key]
        override suspend fun write(key: ServerBrowseKey, page: ServerBrowsePage) { pages[key] = page }
        override suspend fun retainProfiles(revisions: Map<String, Long>) {
            pages.keys.removeAll { revisions[it.profileId] != it.revision }
        }
        override suspend fun clear() { pages.clear() }
    }

    private fun repository(cache: SubsonicBrowseCache, current: () -> SubsonicProfile? = { profile },
                           handler: (HttpUrl) -> JSONObject): SubsonicRepository {
        val accounts = mock(SubsonicAccounts::class.java)
        `when`(accounts.profiles).thenReturn(MutableStateFlow(listOf(profile)))
        `when`(accounts.profile(profile.id)).thenAnswer { current() }
        `when`(accounts.credentials(profile.id)).thenReturn(SubsonicCredentials(profile, "test-password"))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val result = JSONObject().put("subsonic-response", handler(chain.request().url).put("status", "ok"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(result.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        return SubsonicRepository(accounts, SubsonicClient(client), cache)
    }

    private fun albums(start: Int, count: Int) = JSONArray(List(count) { index ->
        JSONObject().put("id", "album / 中文 ${start + index}").put("name", "Album ${start + index}")
            .put("artist", "Artist").put("songCount", 3)
    })

    @Test fun `album catalog scans every page and reopens a complete fresh snapshot without network`() = runTest {
        val store = Store()
        val offsets = mutableListOf<Int>()
        val cache = SubsonicBrowseCache(store, backgroundScope, now = { 100L }, capacity = 1, maxItems = 1)
        val repo = repository(cache) { url ->
            assertEquals("/rest/getAlbumList2.view", url.encodedPath)
            assertEquals("500", url.queryParameter("size"))
            assertEquals("alphabeticalByName", url.queryParameter("type"))
            val offset = requireNotNull(url.queryParameter("offset")).toInt().also(offsets::add)
            JSONObject().put("albumList2", JSONObject().put("album", albums(offset, minOf(500, 1101 - offset))))
        }
        val key = repo.catalogKey(profile.id, ServerLibraryCategory.ALBUMS)
        assertEquals(ServerBrowseKey(profile.id, profile.revision, "catalog-albums", size = 500), key)
        val page = repo.catalog(profile.id, ServerLibraryCategory.ALBUMS)
        assertEquals(listOf(0, 500, 1000), offsets)
        assertEquals(1101, page.albums.size)
        assertEquals("album / 中文 1100", page.albums.last().id)
        assertFalse(page.hasMore)
        val reopened = repository(SubsonicBrowseCache(store, backgroundScope, now = { 86_399_999L })) {
            error("A fresh complete snapshot must not request the server")
        }
        assertEquals(page, reopened.catalog(profile.id, ServerLibraryCategory.ALBUMS))
    }

    @Test fun `song catalog uses empty query and handles an exact multiple with a final empty page`() = runTest {
        val offsets = mutableListOf<Int>()
        val repo = repository(SubsonicBrowseCache(scope = backgroundScope)) { url ->
            assertEquals("/rest/search3.view", url.encodedPath)
            assertEquals("", url.queryParameter("query"))
            assertEquals("500", url.queryParameter("songCount"))
            assertEquals("0", url.queryParameter("artistCount"))
            assertEquals("0", url.queryParameter("albumCount"))
            val offset = requireNotNull(url.queryParameter("songOffset")).toInt().also(offsets::add)
            val songs = JSONArray(List(minOf(500, 1000 - offset)) { index ->
                JSONObject().put("id", "track / 中文 ${offset + index}").put("title", "Track")
            })
            JSONObject().put("searchResult3", JSONObject().put("song", songs))
        }
        val page = repo.catalog(profile.id, ServerLibraryCategory.SONGS)
        assertEquals(listOf(0, 500, 1000), offsets)
        assertEquals(1000, page.songs.size)
        assertEquals("track / 中文 999", ServerSongRef.from(page.songs.last())?.songId)
        assertFalse(page.hasMore)
    }

    @Test fun `song catalog stores a real credential free cover endpoint for synchronization`() = runTest {
        val repo = repository(SubsonicBrowseCache(scope = backgroundScope)) {
            JSONObject().put("searchResult3", JSONObject().put("song", JSONArray().put(
                JSONObject().put("id", "song").put("coverArt", "cover / 中文 + ? &"))))
        }
        val song = repo.catalog(profile.id, ServerLibraryCategory.SONGS).songs.single()
        val cover = requireNotNull(song.coverUrl).toHttpUrl()
        assertEquals("example.test", cover.host)
        assertEquals("/rest/getCoverArt.view", cover.encodedPath)
        assertEquals("cover / 中文 + ? &", cover.queryParameter("id"))
        assertEquals(setOf("id", "size"), cover.queryParameterNames)
        assertEquals("600", cover.queryParameter("size"))
        assertTrue(requireNotNull(cover.fragment).matches(Regex("neri-cover=v1:[0-9a-f]{64}")))
        assertEquals(ServerSongRef(profile.id, "song"), ServerSongRef.from(song))
    }

    @Test fun `artist catalog flattens the ID3 index and artist albums reuse a 24 hour cache`() = runTest {
        var now = 100L
        val paths = mutableListOf<String>()
        val repo = repository(SubsonicBrowseCache(scope = backgroundScope, now = { now })) { url ->
            paths += url.encodedPath
            if (url.encodedPath.endsWith("getArtists.view")) {
                val artist = JSONObject().put("id", "artist / 中文").put("name", "Artist")
                    .put("albumCount", 2).put("coverArt", "cover / artist")
                JSONObject().put("artists", JSONObject().put("index", JSONArray().put(
                    JSONObject().put("name", "A").put("artist", JSONArray().put(artist)))))
            } else {
                assertEquals("/rest/getArtist.view", url.encodedPath)
                assertEquals("artist / 中文", url.queryParameter("id"))
                JSONObject().put("artist", JSONObject().put("id", "artist / 中文").put("album", albums(0, 2)))
            }
        }
        val artists = repo.catalog(profile.id, ServerLibraryCategory.ARTISTS).artists
        assertEquals(1, artists.size)
        assertEquals(profile.id, artists.single().profileId)
        assertEquals("artist / 中文", artists.single().id)
        assertEquals(2, artists.single().albumCount)
        val cover = requireNotNull(artists.single().coverUrl).toHttpUrl()
        assertEquals("example.test", cover.host)
        assertEquals("/rest/getCoverArt.view", cover.encodedPath)
        assertEquals("cover / artist", cover.queryParameter("id"))
        assertEquals(ServerBrowseKey(profile.id, profile.revision, "artist", "artist / 中文", size = 500),
            repo.artistAlbumsKey(profile.id, "artist / 中文"))
        assertEquals(2, repo.artistAlbums(profile.id, "artist / 中文").albums.size)
        now += 86_399_999L
        repo.artistAlbums(profile.id, "artist / 中文")
        assertEquals(2, paths.size)
        repo.artistAlbums(profile.id, "artist / 中文", force = true)
        assertEquals(3, paths.size)
        now += 86_400_000L
        repo.artistAlbums(profile.id, "artist / 中文")
        assertEquals(4, paths.size)
    }

    @Test fun `a failed later page never publishes a partial catalog and normal retry reuses earlier pages`() = runTest {
        val cache = SubsonicBrowseCache(scope = backgroundScope)
        val offsets = mutableListOf<Int>()
        var failSecond = true
        val repo = repository(cache) { url ->
            val offset = requireNotNull(url.queryParameter("offset")).toInt().also(offsets::add)
            if (offset == 500 && failSecond) throw IOException("temporary")
            JSONObject().put("albumList2", JSONObject().put("album", albums(offset, if (offset == 0) 500 else 1)))
        }
        val key = repo.catalogKey(profile.id, ServerLibraryCategory.ALBUMS)
        try { repo.catalog(profile.id, ServerLibraryCategory.ALBUMS); fail() } catch (_: SubsonicException) { }
        assertNull(cache.snapshot(key))
        failSecond = false
        assertEquals(501, repo.catalog(profile.id, ServerLibraryCategory.ALBUMS).albums.size)
        assertEquals(listOf(0, 500, 500), offsets)
        failSecond = true
        try { repo.catalog(profile.id, ServerLibraryCategory.ALBUMS, force = true); fail() } catch (_: SubsonicException) { }
        assertEquals(501, cache.snapshot(key)?.albums?.size)
        assertEquals(listOf(0, 500, 500, 0, 500), offsets)
    }

    @Test fun `missing collection wrapper and malformed rows cannot become a successful empty catalog`() = runTest {
        for (response in listOf(JSONObject(), JSONObject().put("albumList2", JSONObject()
            .put("album", JSONArray().put(JSONObject().put("name", "Missing id")))))) {
            val cache = SubsonicBrowseCache(scope = backgroundScope)
            val repo = repository(cache) { response }
            try { repo.catalog(profile.id, ServerLibraryCategory.ALBUMS); fail() }
            catch (error: SubsonicException) { assertEquals(SubsonicFailureKind.INVALID_RESPONSE, error.kind) }
            assertNull(cache.snapshot(repo.catalogKey(profile.id, ServerLibraryCategory.ALBUMS)))
        }
    }

    @Test fun `an ignored offset is rejected instead of looping or publishing a partial catalog`() = runTest {
        var calls = 0
        val cache = SubsonicBrowseCache(scope = backgroundScope)
        val repo = repository(cache) {
            calls++
            JSONObject().put("albumList2", JSONObject().put("album", albums(0, 500)))
        }
        try { repo.catalog(profile.id, ServerLibraryCategory.ALBUMS); fail() }
        catch (error: SubsonicException) { assertEquals(SubsonicFailureKind.INVALID_RESPONSE, error.kind) }
        assertEquals(2, calls)
        assertNull(cache.snapshot(repo.catalogKey(profile.id, ServerLibraryCategory.ALBUMS)))
    }

    @Test fun `a revision changed during a response cannot publish catalog or artist albums`() = runTest {
        for (artistDetail in listOf(false, true)) {
            var current: SubsonicProfile? = profile
            val cache = SubsonicBrowseCache(scope = backgroundScope)
            val repo = repository(cache, current = { current }) {
                current = profile.copy(revision = profile.revision + 1)
                if (artistDetail) JSONObject().put("artist", JSONObject().put("id", "artist").put("album", albums(0, 1)))
                else JSONObject().put("albumList2", JSONObject().put("album", albums(0, 1)))
            }
            val key = if (artistDetail) repo.artistAlbumsKey(profile.id, "artist")
                else repo.catalogKey(profile.id, ServerLibraryCategory.ALBUMS)
            try {
                if (artistDetail) repo.artistAlbums(profile.id, "artist")
                else repo.catalog(profile.id, ServerLibraryCategory.ALBUMS)
                fail()
            } catch (error: SubsonicException) { assertEquals(SubsonicFailureKind.ACCOUNT_UNAVAILABLE, error.kind) }
            assertNull(cache.snapshot(key))
        }
    }
}
