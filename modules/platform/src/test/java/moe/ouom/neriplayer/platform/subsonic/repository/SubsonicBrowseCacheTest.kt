package moe.ouom.neriplayer.platform.subsonic.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubsonicBrowseCacheTest {
    private val key = ServerBrowseKey("profile-one", 1L, "albums")
    private val page = ServerBrowsePage(albums = listOf(ServerAlbum("profile-one", "album/中文", "Album", "Artist", null, 3)))
    private class Store : ServerBrowseStore {
        val pages = mutableMapOf<ServerBrowseKey, ServerBrowsePage>()
        var writes = 0
        override suspend fun read(key: ServerBrowseKey) = pages[key]
        override suspend fun write(key: ServerBrowseKey, page: ServerBrowsePage) { writes++; pages[key] = page }
        override suspend fun retainProfiles(revisions: Map<String, Long>) { pages.keys.removeAll { revisions[it.profileId] != it.revision } }
        override suspend fun clear() { pages.clear() }
    }

    @Test fun `catalog snapshots stay fresh for 24 hours and manual refresh bypasses freshness`() = runTest {
        var now = 100L
        var calls = 0
        val catalog = key.copy(kind = "catalog-albums", size = 500)
        val cache = SubsonicBrowseCache(scope = backgroundScope, now = { now })
        cache.fetch(catalog) { calls++; page }
        now += 86_399_999L
        cache.fetch(catalog) { calls++; page }
        assertEquals(1, calls)
        cache.fetch(catalog, force = true) { calls++; page }
        assertEquals(2, calls)
        now += 86_400_000L
        cache.fetch(catalog) { calls++; page }
        assertEquals(3, calls)
    }

    @Test fun `catalog snapshots survive ordinary page capacity and item eviction`() = runTest {
        val catalog = key.copy(kind = "catalog-albums", size = 500)
        val complete = page.copy(albums = List(3) { page.albums.single().copy(id = "album-$it") })
        val cache = SubsonicBrowseCache(scope = backgroundScope, capacity = 1, maxItems = 1)
        cache.fetch(catalog) { complete }
        assertEquals(complete.albums, cache.snapshot(catalog)?.albums)
        cache.fetch(key) { page }
        cache.fetch(key.copy(offset = 30)) { page }
        assertEquals(complete.albums, cache.snapshot(catalog)?.albums)
        assertNull(cache.snapshot(key))
        assertNotNull(cache.snapshot(key.copy(offset = 30)))
    }

    @Test fun `catalog Room snapshot round trips the complete album list`() = runTest {
        var saved: moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord? = null
        val room = org.mockito.Mockito.mock(moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore::class.java) { call ->
            when (call.method.name) {
                "replaceBounded", "replace" -> { saved = call.arguments[0] as moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord; Unit }
                "read" -> saved
                else -> null
            }
        }
        val adapter = SubsonicBrowseRoomStore(room)
        val catalog = key.copy(kind = "catalog-albums", size = 500)
        val complete = page.copy(savedAtMs = 42L)
        adapter.write(catalog, complete)
        assertEquals(complete, adapter.read(catalog))
    }

    @Test fun `catalog Room snapshots preserve artist and song identities and artist album details`() = runTest {
        val records = mutableMapOf<String, moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord>()
        val room = org.mockito.Mockito.mock(moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore::class.java) { call ->
            when (call.method.name) {
                "replaceBounded", "replace" -> {
                    val record = call.arguments[0] as moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord
                    records[record.cacheKey] = record
                    Unit
                }
                "read" -> records[call.arguments[1] as String]
                else -> null
            }
        }
        val adapter = SubsonicBrowseRoomStore(room)
        val artistKey = key.copy(kind = "catalog-artists", size = 500)
        val artists = ServerBrowsePage(artists = listOf(ServerArtist(key.profileId, "artist / 中文", "Artist", "opaque-cover", 42)),
            savedAtMs = 42L)
        adapter.write(artistKey, artists)
        assertEquals(artists, adapter.read(artistKey))
        val detailKey = key.copy(kind = "artist", value = "artist / 中文", size = 500)
        val albums = page.copy(savedAtMs = 42L)
        adapter.write(detailKey, albums)
        assertEquals(albums, adapter.read(detailKey))
        val songKey = key.copy(profileId = "9e8a8fc4-1e35-4b5a-884d-8bb2423cc791", kind = "catalog-songs", size = 500)
        val ref = moe.ouom.neriplayer.data.model.server.ServerSongRef(songKey.profileId, "song / 中文")
        val song = moe.ouom.neriplayer.data.model.SongItem(ref.numericId, "Song", "Artist", "Album", 7L, 3000L, null,
            mediaUri = ref.mediaUri, channelId = moe.ouom.neriplayer.data.model.server.ServerSongRef.CHANNEL,
            audioId = ref.audioId)
        val songs = ServerBrowsePage(songs = listOf(song), savedAtMs = 42L)
        adapter.write(songKey, songs)
        assertEquals(songs, adapter.read(songKey))
    }

    @Test fun `fresh snapshots reuse requests and stale refresh failure retains content`() = runTest {
        var now = 100L
        var calls = 0
        val cache = SubsonicBrowseCache(scope = backgroundScope, now = { now })
        cache.fetch(key) { calls++; page }
        now += 59_999L
        cache.fetch(key) { calls++; page }
        assertEquals(1, calls)
        now++
        try { cache.fetch(key) { calls++; error("offline") }; fail() } catch (_: IllegalStateException) {}
        assertEquals(2, calls)
        assertEquals(page.albums, cache.snapshot(key)?.albums)
        cache.fetch(key, force = true) { calls++; page }
        assertEquals(3, calls)
    }

    @Test fun `concurrent readers share one request and one cancellation does not cancel the other`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        var cancelled = false
        val cache = SubsonicBrowseCache(scope = backgroundScope)
        val loader: suspend () -> ServerBrowsePage = {
            calls++
            try { gate.await(); page } finally { cancelled = !currentCoroutineContext().isActive }
        }
        val first = async { cache.fetch(key, loader = loader) }
        val second = async { cache.fetch(key, loader = loader) }
        runCurrent()
        first.cancelAndJoin()
        assertFalse(cancelled)
        gate.complete(Unit)
        assertEquals(page.albums, second.await().albums)
        assertEquals(1, calls)
    }

    @Test fun `retaining the same profile revision does not discard an in flight catalog`() = runTest {
        val store = Store()
        val cache = SubsonicBrowseCache(store, backgroundScope)
        val catalog = key.copy(kind = "catalog-albums", size = 500)
        val gate = CompletableDeferred<Unit>()
        val request = async { cache.fetch(catalog) { gate.await(); page } }
        runCurrent()
        cache.retainProfiles(mapOf(key.profileId to key.revision))
        gate.complete(Unit)
        assertEquals(page.albums, request.await().albums)
        assertEquals(page.albums, cache.snapshot(catalog)?.albums)
        assertEquals(1, store.writes)
    }

    @Test fun `last subscriber cancellation stops request and cannot populate cache`() = runTest {
        var cancelled = false
        val cache = SubsonicBrowseCache(scope = backgroundScope)
        val consumer = launch {
            cache.fetch(key) { try { awaitCancellation() } finally { cancelled = true } }
        }
        runCurrent()
        consumer.cancelAndJoin()
        runCurrent()
        assertTrue(cancelled)
        assertNull(cache.snapshot(key))
    }

    @Test fun `loader returning after cancellation cannot write a late snapshot`() = runTest {
        val store = Store()
        val cache = SubsonicBrowseCache(store, backgroundScope)
        val consumer = launch {
            cache.fetch(key) {
                try { awaitCancellation() }
                catch (_: CancellationException) { page }
            }
        }
        runCurrent()
        consumer.cancelAndJoin()
        runCurrent()
        assertNull(cache.snapshot(key))
        assertEquals(0, store.writes)
    }

    @Test fun `disk snapshot survives a new cache instance and keeps server identity`() = runTest {
        val store = Store()
        val first = SubsonicBrowseCache(store, backgroundScope, now = { 200L })
        first.fetch(key) { page }
        val second = SubsonicBrowseCache(store, backgroundScope, now = { 201L })
        val restored = second.fetch(key) { error("must reuse disk") }
        assertEquals("album/中文", restored.albums.single().id)
        assertNull(second.snapshot(key.copy(profileId = "profile-two")))
        assertNull(second.snapshot(key.copy(revision = 2)))
    }

    @Test fun `revision change discards memory disk and active old requests`() = runTest {
        val store = Store()
        val cache = SubsonicBrowseCache(store, backgroundScope)
        cache.fetch(key) { page }
        val gate = CompletableDeferred<Unit>()
        val old = async { cache.fetch(key.copy(offset = 30)) { gate.await(); page } }
        runCurrent()
        cache.retainProfiles(mapOf(key.profileId to 2L))
        runCurrent()
        assertNull(cache.snapshot(key))
        assertTrue(store.pages.isEmpty())
        assertTrue(old.isCancelled)
        assertEquals(1, store.writes)
    }

    @Test fun `query pagination and server keys are isolated and search is memory only`() = runTest {
        val store = Store()
        val cache = SubsonicBrowseCache(store, backgroundScope, capacity = 2)
        val search = key.copy(kind = "search", value = "a:/中文")
        cache.fetch(search) { page }
        assertEquals(0, store.writes)
        assertNull(cache.snapshot(search.copy(value = "a")))
        assertNull(cache.snapshot(search.copy(offset = 30)))
        cache.fetch(search.copy(offset = 30)) { page }
        cache.fetch(search.copy(profileId = "profile-two")) { page }
        assertNull(cache.snapshot(search))
    }

    @Test fun `clear cancels pending writers and removes both cache tiers`() = runTest {
        val store = Store()
        val cache = SubsonicBrowseCache(store, backgroundScope)
        cache.fetch(key) { page }
        val pending = async { cache.fetch(key, force = true) { awaitCancellation() } }
        runCurrent()
        cache.clear()
        runCurrent()
        assertTrue(pending.isCancelled)
        assertTrue(store.pages.isEmpty())
        assertNull(cache.snapshot(key))
    }

    @Test fun `album and song searches are isolated and neither is persisted`() = runTest {
        val store = Store()
        val cache = SubsonicBrowseCache(store, backgroundScope)
        val albums = key.copy(kind = "search-albums", value = "Artist")
        val songs = key.copy(kind = "search", value = "Artist")
        cache.fetch(albums) { page }
        assertNull(cache.snapshot(songs))
        cache.fetch(songs) { ServerBrowsePage() }
        assertEquals(page.albums, cache.snapshot(albums)?.albums)
        assertTrue(cache.snapshot(songs)?.albums?.isEmpty() == true)
        assertEquals(0, store.writes)
    }

    @Test fun `song directory persists without colliding with the album directory`() = runTest {
        val store = Store()
        val cache = SubsonicBrowseCache(store, backgroundScope)
        val songs = key.copy(kind = "songs")
        cache.fetch(songs) { ServerBrowsePage() }
        assertEquals(1, store.writes)
        val restored = SubsonicBrowseCache(store, backgroundScope)
        assertNotNull(restored.snapshot(songs))
        assertNull(restored.snapshot(key))
    }
    @Test fun `Room adapter preserves arbitrary server album and song ids across persistence`() = runTest {
        var saved: moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord? = null
        val room = org.mockito.Mockito.mock(moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRoomStore::class.java) { call ->
            when (call.method.name) {
                "replaceBounded" -> { saved = call.arguments[0] as moe.ouom.neriplayer.data.local.database.store.PlatformPlaylistCacheRecord; Unit }
                "read" -> saved
                else -> null
            }
        }
        val adapter = SubsonicBrowseRoomStore(room)
        val profile = "9e8a8fc4-1e35-4b5a-884d-8bb2423cc791"
        val albumKey = key.copy(profileId = profile)
        val album = page.albums.single().copy(profileId = profile)
        adapter.write(albumKey, ServerBrowsePage(albums = listOf(album), savedAtMs = 42, hasMore = true))
        assertEquals(album, adapter.read(albumKey)?.albums?.single())
        assertTrue(adapter.read(albumKey)?.hasMore == true)
        val ref = moe.ouom.neriplayer.data.model.server.ServerSongRef(profile, "track:/中文:with-slash")
        val song = moe.ouom.neriplayer.data.model.SongItem(id = ref.numericId, name = "Song", artist = "Artist", album = "Album",
            albumId = 123L, durationMs = 300000, coverUrl = ref.resourceUrl("cover"),
            mediaUri = ref.mediaUri, channelId = moe.ouom.neriplayer.data.model.server.ServerSongRef.CHANNEL, audioId = ref.audioId)
        val songKey = albumKey.copy(kind = "album", value = album.id)
        adapter.write(songKey, ServerBrowsePage(songs = listOf(song), savedAtMs = 43))
        val restored = requireNotNull(adapter.read(songKey)).songs.single()
        assertEquals(ref, moe.ouom.neriplayer.data.model.server.ServerSongRef.from(restored))
        assertEquals(song, restored)
        assertEquals("subsonic", saved?.platform)
        assertNull(adapter.read(songKey.copy(revision = 2)))
    }

}
