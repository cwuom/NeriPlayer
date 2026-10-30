package moe.ouom.neriplayer.platform.subsonic.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord
import moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicClient
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicCoverReference
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicFailureKind
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicAccounts
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicCredentials
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SubsonicArtistArtworkTest {
    private val profile = SubsonicProfile("9e8a8fc4-1e35-4b5a-884d-8bb2423cc791", "Server", "https://example.test/", "user")
    private val artistId = "artist / 中文"
    private val artist = JSONObject().put("id", artistId).put("name", "Artist").put("albumCount", 2)

    private class Store : ServerBrowseStore {
        val pages = mutableMapOf<ServerBrowseKey, ServerBrowsePage>()
        val reads = mutableListOf<ServerBrowseKey>()
        override suspend fun read(key: ServerBrowseKey): ServerBrowsePage? { reads += key; return pages[key] }
        override suspend fun write(key: ServerBrowseKey, page: ServerBrowsePage) { pages[key] = page }
        override suspend fun retainProfiles(revisions: Map<String, Long>) {
            pages.keys.removeAll { revisions[it.profileId] != it.revision }
        }
        override suspend fun clear() { pages.clear() }
    }

    private fun repository(cache: SubsonicBrowseCache, current: () -> SubsonicProfile? = { profile },
                           coverUrls: Map<String, String> = emptyMap(),
                           handler: (HttpUrl) -> JSONObject): SubsonicRepository {
        val accounts = mock(SubsonicAccounts::class.java)
        `when`(accounts.profiles).thenReturn(MutableStateFlow(listOf(profile)))
        `when`(accounts.profile(profile.id)).thenAnswer { current() }
        `when`(accounts.credentials(profile.id)).thenReturn(SubsonicCredentials(profile, "test-password"))
        coverUrls.forEach { (raw, portable) -> doReturn(portable).`when`(accounts).portableCoverUrl(raw) }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val json = JSONObject().put("subsonic-response", handler(chain.request().url).put("status", "ok"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        return SubsonicRepository(accounts, SubsonicClient(client), cache)
    }

    private fun artistResponse(value: JSONObject) = JSONObject().put("artists", JSONObject().put("index",
        JSONArray().put(JSONObject().put("artist", JSONArray().put(value)))))

    @Test fun `server artist image URL is used when coverArt is absent`() = runTest {
        val imageUrl = "https://images.example.test/artist.jpg?size=300"
        val repo = repository(SubsonicBrowseCache(scope = backgroundScope)) {
            artistResponse(artist.put("artistImageUrl", imageUrl))
        }
        assertEquals(imageUrl, repo.catalog(profile.id, ServerLibraryCategory.ARTISTS).artists.single().coverUrl)
    }

    @Test fun `real server coverArt stays ahead of an external artist image URL`() = runTest {
        val repo = repository(SubsonicBrowseCache(scope = backgroundScope)) {
            artistResponse(artist.put("coverArt", "cover / 艺人").put("artistImageUrl", "https://images.example.test/avatar.jpg"))
        }
        assertEquals(SubsonicCoverReference.create(profile, "cover / 艺人"),
            repo.catalog(profile.id, ServerLibraryCategory.ARTISTS).artists.single().coverUrl)
    }

    @Test fun `artist image URLs never retain credentials userinfo or unsupported schemes`() = runTest {
        for (url in listOf("https://user:secret@example.test/avatar.jpg", "file:///private/avatar.jpg",
            "ftp://example.test/avatar.jpg", "https://example.test/avatar.jpg?u=user&t=secret&s=salt",
            "https://example.test/avatar.jpg?apiKey=secret", "https://example.test/avatar.jpg?password=secret",
            "https://neri-server.invalid/v1/other-profile/cover/image")) {
            val repo = repository(SubsonicBrowseCache(scope = backgroundScope)) {
                artistResponse(artist.put("artistImageUrl", url))
            }
            assertNull(repo.catalog(profile.id, ServerLibraryCategory.ARTISTS).artists.single().coverUrl)
        }
    }

    @Test fun `artist detail retains its original server header beside its albums`() = runTest {
        val repo = repository(SubsonicBrowseCache(scope = backgroundScope)) { url ->
            assertEquals("/rest/getArtist.view", url.encodedPath)
            assertEquals(artistId, url.queryParameter("id"))
            JSONObject().put("artist", artist.put("coverArt", "detail-cover").put("album", JSONArray()))
        }
        val page = repo.artistAlbums(profile.id, artistId)
        assertEquals(1, page.artists.size)
        assertEquals(ServerArtist(profile.id, artistId, "Artist",
            SubsonicCoverReference.create(profile, "detail-cover"), 2), page.artists.single())
    }

    @Test fun `artist detail server header survives a Room round trip`() = runTest {
        var stored: PlatformPlaylistCacheRecord? = null
        val room = mock(PlatformPlaylistCacheRoomStore::class.java) { call ->
            when (call.method.name) {
                "replaceBounded" -> { stored = call.arguments[0] as PlatformPlaylistCacheRecord; Unit }
                "read" -> stored
                else -> null
            }
        }
        val adapter = SubsonicBrowseRoomStore(room)
        val key = ServerBrowseKey(profile.id, profile.revision, "artist", artistId, size = 500)
        val page = ServerBrowsePage(albums = listOf(ServerAlbum(profile.id, "album", "Album", "Artist", null, 3)),
            artists = listOf(ServerArtist(profile.id, artistId, "Artist", "https://images.example.test/avatar.jpg", 2)),
            savedAtMs = 100L)
        adapter.write(key, page)
        assertEquals(page, adapter.read(key))
    }

    @Test fun `complete songs build raw multi artist last available covers without additional requests`() = runTest {
        var requests = 0
        val cache = SubsonicBrowseCache(scope = backgroundScope)
        val repo = repository(cache) {
            requests++
            val rows = JSONArray().put(JSONObject().put("id", "first").put("artistId", artistId)
                .put("coverArt", "first-cover").put("artists", JSONArray()
                    .put(JSONObject().put("id", "collaborator")).put(JSONObject().put("id", artistId)))
                .put("albumArtistId", "album-legacy").put("albumArtists", JSONArray().put(JSONObject().put("id", "album-multi"))))
                .put(JSONObject().put("id", "last").put("artistId", artistId).put("coverArt", "last-cover")
                    .put("artists", JSONArray().put(JSONObject().put("id", "collaborator"))))
                .put(JSONObject().put("id", "no-cover").put("artistId", artistId))
            JSONObject().put("searchResult3", JSONObject().put("song", rows))
        }
        val page = repo.catalog(profile.id, ServerLibraryCategory.SONGS)
        val first = SubsonicCoverReference.create(profile, "first-cover")
        val last = SubsonicCoverReference.create(profile, "last-cover")
        assertEquals(mapOf(artistId to last, "collaborator" to last, "album-legacy" to first, "album-multi" to first),
            page.artistArtwork)
        val before = cache.updates.value
        for (id in listOf(artistId, "collaborator", "album-legacy", "album-multi", "same-name-other-id")) {
            val projected = repo.cachedArtist(ServerArtist(profile.id, id, "Same name", null, 2))
            assertEquals(page.artistArtwork?.get(id), projected.imageUrl)
        }
        assertEquals(before, cache.updates.value)
        assertEquals(1, requests)
    }

    @Test fun `single artist uses detail then root then route server image while keeping fallback independent`() = runTest {
        val cache = SubsonicBrowseCache(scope = backgroundScope)
        val repo = repository(cache) { error("Artwork projection must not request metadata") }
        val input = ServerArtist(profile.id, artistId, "Route name", "https://images.example.test/route.jpg", 2,
            fallbackCoverUrl = "https://images.example.test/old-fallback.jpg")
        val root = input.copy(name = "Root name", coverUrl = "https://images.example.test/root.jpg", fallbackCoverUrl = null)
        val detail = root.copy(name = "Detail name", coverUrl = "https://images.example.test/detail.jpg")
        val fallback = ServerSongRef(profile.id, "song").resourceUrl("cover", "song-cover")
        cache.fetch(repo.catalogKey(profile.id, ServerLibraryCategory.ARTISTS)) { ServerBrowsePage(artists = listOf(root)) }
        cache.fetch(repo.artistAlbumsKey(profile.id, artistId)) { ServerBrowsePage(artists = listOf(detail)) }
        cache.fetch(repo.catalogKey(profile.id, ServerLibraryCategory.SONGS)) {
            ServerBrowsePage(artistArtwork = mapOf(artistId to fallback))
        }
        var projected = repo.cachedArtist(input)
        assertEquals(detail.coverUrl, projected.coverUrl)
        assertEquals(detail.coverUrl, projected.imageUrl)
        assertEquals(fallback, projected.fallbackCoverUrl)
        cache.fetch(repo.artistAlbumsKey(profile.id, artistId), force = true) {
            ServerBrowsePage(artists = listOf(detail.copy(coverUrl = null)))
        }
        projected = repo.cachedArtist(input)
        assertEquals(root.coverUrl, projected.coverUrl)
        cache.fetch(repo.catalogKey(profile.id, ServerLibraryCategory.ARTISTS), force = true) {
            ServerBrowsePage(artists = listOf(root.copy(coverUrl = null)))
        }
        assertNull(repo.cachedArtist(input).coverUrl)
        assertEquals(fallback, repo.cachedArtist(input.copy(coverUrl = null)).imageUrl)
        val unknown = input.copy(id = "uncached-artist")
        assertEquals(unknown.coverUrl, repo.cachedArtist(unknown).coverUrl)
    }

    @Test fun `batch artist projection reads two complete snapshots and never queries individual detail caches`() = runTest {
        val store = Store()
        val cache = SubsonicBrowseCache(store, backgroundScope)
        val repo = repository(cache) { error("Batch artwork projection must not request metadata") }
        val artists = List(100) { ServerArtist(profile.id, "artist-$it", "Same name", null, 1) }
        val covers = artists.associate { it.id to ServerSongRef(profile.id, it.id).resourceUrl("cover", "cover-${it.id}") }
        store.pages[repo.catalogKey(profile.id, ServerLibraryCategory.ARTISTS)] = ServerBrowsePage(artists = artists)
        store.pages[repo.catalogKey(profile.id, ServerLibraryCategory.SONGS)] = ServerBrowsePage(artistArtwork = covers)
        val result = repo.cachedArtists(profile.id, artists)
        assertEquals(covers.values.toList(), result.map { it.imageUrl })
        assertEquals(listOf("catalog-artists", "catalog-songs"), store.reads.map { it.kind })
        val before = cache.updates.value
        assertEquals(result, repo.cachedArtists(profile.id, artists))
        assertEquals(before, cache.updates.value)
        assertEquals(2, store.reads.size)
    }

    @Test fun `batch projection includes already materialized detail images without Room detail reads`() = runTest {
        val store = Store()
        val cache = SubsonicBrowseCache(store, backgroundScope)
        val repo = repository(cache) { error("Projection must not request metadata") }
        val input = ServerArtist(profile.id, artistId, "Artist", null, 1)
        val detail = input.copy(coverUrl = "https://images.example.test/detail.jpg")
        val fallback = ServerSongRef(profile.id, "song").resourceUrl("cover", "fallback")
        store.pages[repo.catalogKey(profile.id, ServerLibraryCategory.ARTISTS)] = ServerBrowsePage(artists = listOf(input))
        store.pages[repo.catalogKey(profile.id, ServerLibraryCategory.SONGS)] = ServerBrowsePage(artistArtwork = mapOf(artistId to fallback))
        store.pages[repo.artistAlbumsKey(profile.id, artistId)] = ServerBrowsePage(artists = listOf(detail))
        cache.snapshot(repo.artistAlbumsKey(profile.id, artistId))
        store.reads.clear()
        val result = repo.cachedArtists(profile.id, listOf(input)).single()
        assertEquals(detail.coverUrl, result.coverUrl)
        assertEquals(fallback, result.fallbackCoverUrl)
        assertEquals(listOf("catalog-artists", "catalog-songs"), store.reads.map { it.kind })
    }

    @Test fun `reopened batch restores persisted server headers with one metadata read and no individual detail reads`() = runTest {
        val records = mutableMapOf<String, PlatformPlaylistCacheRecord>()
        val reads = mutableListOf<String>()
        var headerReads = 0
        val room = mock(PlatformPlaylistCacheRoomStore::class.java) { call ->
            when (call.method.name) {
                "replace", "replaceBounded" -> {
                    val record = call.arguments[0] as PlatformPlaylistCacheRecord
                    records[record.cacheKey] = record
                    Unit
                }
                "read" -> {
                    val key = call.arguments[1] as String
                    reads += key
                    records[key]
                }
                "readHeaders" -> {
                    headerReads++
                    records.values.map { it.copy(tracks = emptyList()) }
                }
                else -> null
            }
        }
        val adapter = SubsonicBrowseRoomStore(room)
        val firstCache = SubsonicBrowseCache(adapter, backgroundScope)
        val firstRepo = repository(firstCache) { error("Cached artwork must not request metadata") }
        val input = ServerArtist(profile.id, artistId, "Artist", null, 2)
        val detail = input.copy(coverUrl = "https://images.example.test/server.jpg")
        val fallback = ServerSongRef(profile.id, "song").resourceUrl("cover", "fallback")
        val rootKey = firstRepo.catalogKey(profile.id, ServerLibraryCategory.ARTISTS)
        val songKey = firstRepo.catalogKey(profile.id, ServerLibraryCategory.SONGS)
        val detailKey = firstRepo.artistAlbumsKey(profile.id, artistId)
        firstCache.fetch(rootKey) { ServerBrowsePage(artists = listOf(input)) }
        firstCache.fetch(songKey) { ServerBrowsePage(artistArtwork = mapOf(artistId to fallback)) }
        firstCache.fetch(detailKey) { ServerBrowsePage(artists = listOf(detail)) }
        val legacy = requireNotNull(records[detailKey.cacheKey]).copy(alternateKey = null)
        records[detailKey.cacheKey] = legacy
        val otherProfile = "9e8a8fc4-1e35-4b5a-884d-8bb2423cc792"
        val otherKey = detailKey.copy(profileId = otherProfile)
        records[otherKey.cacheKey] = legacy.copy(cacheKey = otherKey.cacheKey,
            signaturePrimary = otherProfile, coverUrl = "https://images.example.test/other-account.jpg")
        val otherRevision = detailKey.copy(revision = profile.revision + 1)
        records[otherRevision.cacheKey] = legacy.copy(cacheKey = otherRevision.cacheKey,
            signatureSecondary = otherRevision.revision.toString(), coverUrl = "https://images.example.test/other-revision.jpg")
        firstCache.clear(clearStore = false)

        val reopenedCache = SubsonicBrowseCache(adapter, backgroundScope)
        val reopened = repository(reopenedCache) { error("Reopened artwork must not request metadata") }
        reads.clear()
        val result = reopened.cachedArtists(profile.id, listOf(input)).single()
        assertEquals(detail.coverUrl, result.coverUrl)
        assertEquals(fallback, result.fallbackCoverUrl)
        assertEquals(listOf(rootKey.cacheKey, songKey.cacheKey), reads)
        assertEquals(1, headerReads)
        val before = reopenedCache.updates.value
        assertEquals(result, reopened.cachedArtists(profile.id, listOf(input)).single())
        assertEquals(before, reopenedCache.updates.value)
        assertEquals(1, headerReads)
        assertEquals(2, reads.size)

        val changed = detail.copy(coverUrl = "https://images.example.test/changed.jpg")
        reopenedCache.fetch(detailKey, force = true) { ServerBrowsePage(artists = listOf(changed)) }
        assertEquals(changed.coverUrl, reopened.cachedArtists(profile.id, listOf(input)).single().coverUrl)
        assertEquals(1, headerReads)
        reopenedCache.clear(clearStore = false)
        assertEquals(changed.coverUrl, reopened.cachedArtists(profile.id, listOf(input)).single().coverUrl)
        assertEquals(2, headerReads)
        reopenedCache.retainProfiles(emptyMap())
        reopenedCache.artistHeaders(profile.id, profile.revision)
        assertEquals(3, headerReads)
    }

    @Test fun `legacy null artwork refreshes old complete and staging pages once then stays warm after reopen`() = runTest {
        val store = Store()
        val cache = SubsonicBrowseCache(store, backgroundScope, now = { 200L })
        var requests = 0
        val repo = repository(cache) {
            requests++
            JSONObject().put("searchResult3", JSONObject().put("song", JSONArray().put(JSONObject()
                .put("id", "song").put("artistId", artistId).put("coverArt", "new-cover"))))
        }
        val ref = ServerSongRef(profile.id, "song")
        val old = ServerBrowsePage(songs = listOf(SongItem(ref.numericId, "Song", "Artist", "Album", 0L, 0L, null,
            mediaUri = ref.mediaUri, channelId = ServerSongRef.CHANNEL, audioId = ref.audioId)), savedAtMs = 100L)
        store.pages[repo.catalogKey(profile.id, ServerLibraryCategory.SONGS)] = old
        store.pages[repo.browseKey(profile.id, size = 500, category = ServerLibraryCategory.SONGS)] = old
        val page = repo.catalog(profile.id, ServerLibraryCategory.SONGS)
        assertEquals(mapOf(artistId to SubsonicCoverReference.create(profile, "new-cover")), page.artistArtwork)
        assertEquals(1, requests)
        val reopened = repository(SubsonicBrowseCache(store, backgroundScope, now = { 300L })) {
            error("A new complete artwork index must survive a restart")
        }
        assertEquals(page.artistArtwork, reopened.catalog(profile.id, ServerLibraryCategory.SONGS).artistArtwork)
        assertEquals(SubsonicCoverReference.create(profile, "new-cover"), reopened.cachedArtist(ServerArtist(profile.id, artistId, "Artist", null, 2)).imageUrl)
    }

    @Test fun `reopened complete legacy covers project without network writes or source identity changes`() = runTest {
        val store = Store()
        val ref = ServerSongRef(profile.id, "song / 中文")
        val legacy = listOf("cover", "custom", "original").associateWith { ref.resourceUrl("cover", it) }
        val portable = legacy.mapValues { SubsonicCoverReference.create(profile, it.key) }
        val song = SongItem(ref.numericId, "Song", "Artist", "Album", 7, 8, legacy.getValue("cover"),
            mediaUri = ref.mediaUri, channelId = ServerSongRef.CHANNEL, audioId = ref.audioId,
            customCoverUrl = legacy.getValue("custom"), originalCoverUrl = legacy.getValue("original"))
        val artist = ServerArtist(profile.id, artistId, "Artist", legacy.getValue("cover"), 1,
            fallbackCoverUrl = legacy.getValue("custom"))
        val old = ServerBrowsePage(albums = listOf(ServerAlbum(profile.id, "album", "Album", "Artist", legacy.getValue("cover"), 1)),
            songs = listOf(song), artists = listOf(artist), artistArtwork = mapOf(artistId to legacy.getValue("custom")), savedAtMs = 100L)
        val expectedSong = song.copy(coverUrl = portable.getValue("cover"), customCoverUrl = portable.getValue("custom"),
            originalCoverUrl = portable.getValue("original"))
        val expectedArtist = artist.copy(coverUrl = portable.getValue("cover"), fallbackCoverUrl = portable.getValue("custom"))
        val expected = old.copy(albums = old.albums.map { it.copy(coverUrl = portable.getValue("cover")) },
            songs = listOf(expectedSong), artists = listOf(expectedArtist), artistArtwork = mapOf(artistId to portable.getValue("custom")))
        val first = repository(SubsonicBrowseCache(store, backgroundScope, now = { 200L })) {
            error("Preparing complete cache keys must not request metadata")
        }
        val keys = ServerLibraryCategory.entries.map { first.catalogKey(profile.id, it) } +
            first.browseKey(profile.id, albumId = "album") + first.artistAlbumsKey(profile.id, artistId)
        keys.forEach { store.pages[it] = old }
        var requests = 0
        val reopenedCache = SubsonicBrowseCache(store, backgroundScope, now = { 201L })
        val reopened = repository(reopenedCache,
            coverUrls = legacy.entries.associate { it.value to portable.getValue(it.key) }) {
            requests++; error("Fresh complete legacy covers must not force a refresh")
        }
        for (category in ServerLibraryCategory.entries) assertEquals(expected, reopened.catalog(profile.id, category))
        assertEquals(expected, reopened.browse(first.browseKey(profile.id, albumId = "album")))
        assertEquals(expected, reopened.artistAlbums(profile.id, artistId))
        keys.forEach { assertEquals(expected, reopened.cached(it)) }
        assertEquals(expectedArtist, reopened.cachedArtist(artist))
        assertEquals(listOf(expectedArtist), reopened.cachedArtists(profile.id, listOf(artist)))
        val projected = requireNotNull(reopened.cached(first.catalogKey(profile.id, ServerLibraryCategory.SONGS))).songs.single()
        assertEquals(ref, ServerSongRef.from(projected))
        assertEquals(song.id, projected.id)
        assertEquals(song.audioId, projected.audioId)
        assertEquals(song.mediaUri, projected.mediaUri)
        assertEquals(0, requests)
        keys.forEach {
            assertEquals(old, store.pages[it])
            assertEquals(old, reopenedCache.snapshot(it))
        }
    }

    @Test fun `Room distinguishes legacy null artwork from empty and populated complete indexes`() = runTest {
        var stored: PlatformPlaylistCacheRecord? = null
        val room = mock(PlatformPlaylistCacheRoomStore::class.java) { call ->
            when (call.method.name) {
                "replace" -> { stored = call.arguments[0] as PlatformPlaylistCacheRecord; Unit }
                "read" -> stored
                else -> null
            }
        }
        val adapter = SubsonicBrowseRoomStore(room)
        val key = ServerBrowseKey(profile.id, profile.revision, "catalog-songs", size = 500)
        for (index in listOf(null, emptyMap(), mapOf(artistId to ServerSongRef(profile.id, "song").resourceUrl("cover", "cover")))) {
            val page = ServerBrowsePage(artistArtwork = index, savedAtMs = 100L)
            adapter.write(key, page)
            assertEquals(page, adapter.read(key))
        }
    }

    @Test fun `artwork projection cannot reuse an earlier revision or a removed profile`() = runTest {
        var current: SubsonicProfile? = profile
        val cache = SubsonicBrowseCache(scope = backgroundScope)
        val repo = repository(cache, current = { current }) { error("Projection must not request metadata") }
        val input = ServerArtist(profile.id, artistId, "Artist", null, 2, fallbackCoverUrl = "old-fallback")
        cache.fetch(repo.catalogKey(profile.id, ServerLibraryCategory.SONGS)) {
            ServerBrowsePage(artistArtwork = mapOf(artistId to "old-fallback"))
        }
        assertEquals("old-fallback", repo.cachedArtist(input).imageUrl)
        current = profile.copy(revision = profile.revision + 1)
        assertNull(repo.cachedArtist(input).fallbackCoverUrl)
        current = null
        try { repo.cachedArtist(input); fail() }
        catch (error: SubsonicException) { assertEquals(SubsonicFailureKind.ACCOUNT_UNAVAILABLE, error.kind) }
    }

    @Test fun `cache updates report new materialized snapshots and invalidation without snapshot feedback loops`() = runTest {
        val store = Store()
        val cache = SubsonicBrowseCache(store, backgroundScope)
        val key = ServerBrowseKey(profile.id, profile.revision, "catalog-songs", size = 500)
        store.pages[key] = ServerBrowsePage(artistArtwork = emptyMap(), savedAtMs = 100L)
        val initial = cache.updates.value
        cache.snapshot(key)
        val hydrated = cache.updates.value
        assertTrue(hydrated > initial)
        repeat(5) { cache.snapshot(key) }
        assertEquals(hydrated, cache.updates.value)
        cache.retainProfiles(mapOf(profile.id to profile.revision))
        assertEquals(hydrated, cache.updates.value)
        cache.retainProfiles(emptyMap())
        assertTrue(cache.updates.value > hydrated)
        val invalidated = cache.updates.value
        cache.clear(clearStore = false)
        assertTrue(cache.updates.value > invalidated)
    }

    @Test fun `a self evicted oversized detail snapshot cannot trigger an updates feedback loop`() = runTest {
        val store = Store()
        val cache = SubsonicBrowseCache(store, backgroundScope, maxItems = 1)
        val key = ServerBrowseKey(profile.id, profile.revision, "artist", artistId, size = 500)
        store.pages[key] = ServerBrowsePage(albums = List(2) { ServerAlbum(profile.id, "album-$it", "Album", "Artist", null, 1) },
            artists = listOf(ServerArtist(profile.id, artistId, "Artist", null, 2)))
        assertNotNull(cache.snapshot(key))
        val before = cache.updates.value
        repeat(5) { assertNotNull(cache.snapshot(key)) }
        assertEquals(before, cache.updates.value)
    }
}
