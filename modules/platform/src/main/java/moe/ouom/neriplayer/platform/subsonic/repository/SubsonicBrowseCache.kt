package moe.ouom.neriplayer.platform.subsonic.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.server.ServerSongRef

enum class ServerLibraryCategory {
    ALBUMS, SONGS, ARTISTS;

    fun browseKind(query: String): String = when (this) {
        ALBUMS -> if (query.isBlank()) "albums" else "search-albums"
        SONGS -> if (query.isBlank()) "songs" else "search"
        ARTISTS -> if (query.isBlank()) "artists" else "search-artists"
    }
}

/** Include category, page and configuration revision; album/song searches never share a page. */
data class ServerBrowseKey(
    val profileId: String, val revision: Long, val kind: String,
    val value: String = "", val offset: Int = 0, val size: Int = 30
) {
    val cacheKey: String get() = "$profileId:$revision:$kind:${ServerSongRef.encode(value)}:$offset:$size"
    val persistent: Boolean get() = !kind.startsWith("search")
    val isCatalog: Boolean get() = kind in SERVER_CATALOG_KINDS
    val freshnessMs: Long get() = if (isCatalog || kind == "artist") 86_400_000L else 60_000L
}

internal val SERVER_CATALOG_KINDS = setOf("catalog-albums", "catalog-songs", "catalog-artists")

data class ServerBrowsePage(
    val albums: List<ServerAlbum> = emptyList(),
    val songs: List<SongItem> = emptyList(),
    val savedAtMs: Long = 0L,
    val hasMore: Boolean = false,
    val artists: List<ServerArtist> = emptyList(),
    val artistArtwork: Map<String, String>? = null
) {
    fun isFresh(nowMs: Long, freshnessMs: Long = 60_000L): Boolean = nowMs - savedAtMs in 0 until freshnessMs
}

interface ServerBrowseStore {
    suspend fun read(key: ServerBrowseKey): ServerBrowsePage?
    suspend fun write(key: ServerBrowseKey, page: ServerBrowsePage)
    suspend fun retainProfiles(revisions: Map<String, Long>)
    suspend fun clear()
    suspend fun artistHeaders(profileId: String, revision: Long): Map<String, ServerArtist> = emptyMap()
}

/** Shared requests keep running while any subscriber remains. Failed refreshes never replace snapshots. */
class SubsonicBrowseCache(
    private val store: ServerBrowseStore? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis,
    private val capacity: Int = 64,
    private val maxItems: Int = 20_000
) {
    private val requestScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutex = Mutex()
    private val pages = LinkedHashMap<ServerBrowseKey, ServerBrowsePage>(16, .75f, true)
    private class Flight(val result: Deferred<ServerBrowsePage>, var readers: Int = 0)
    private val flights = mutableMapOf<ServerBrowseKey, Flight>()
    private class ArtistHeaders(val values: MutableMap<String, ServerArtist> = mutableMapOf(), var loaded: Boolean = false)
    private val savedArtistHeaders = mutableMapOf<Pair<String, Long>, ArtistHeaders>()
    private var generation = 0L
    private val mutableUpdates = MutableStateFlow(0L)
    val updates: StateFlow<Long> = mutableUpdates.asStateFlow()

    suspend fun snapshot(key: ServerBrowseKey): ServerBrowsePage? = mutex.withLock {
        pages[key] ?: if (key.persistent) {
            try { store?.read(key)?.also { remember(key, it) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } // Cache storage must not prevent an online fetch.
        } else null
    }

    internal suspend fun artistHeaders(profileId: String, revision: Long): Map<String, ServerArtist> = mutex.withLock {
        val saved = savedArtistHeaders.getOrPut(profileId to revision) { ArtistHeaders() }
        if (!saved.loaded) {
            try {
                val persisted = store?.artistHeaders(profileId, revision).orEmpty()
                val changed = saved.values.toMap()
                saved.values.putAll(persisted)
                saved.values.putAll(changed)
                saved.loaded = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Cached artwork remains useful if header storage is unavailable. */ }
        }
        val result = saved.values.toMutableMap()
        pages.entries.asSequence().filter { (key, _) ->
            key.profileId == profileId && key.revision == revision && key.kind == "artist"
        }.mapNotNull { (key, page) ->
            page.artists.singleOrNull { it.profileId == profileId && it.id == key.value }
        }.forEach { result[it.id] = it }
        result
    }

    suspend fun fetch(key: ServerBrowseKey, force: Boolean = false,
                      loader: suspend () -> ServerBrowsePage): ServerBrowsePage {
        if (!force) snapshot(key)?.takeIf { it.isFresh(now(), key.freshnessMs) }?.let { return it }
        val flight = mutex.withLock {
            if (!force) pages[key]?.takeIf { it.isFresh(now(), key.freshnessMs) }?.let { return it }
            (flights[key]?.takeUnless { it.result.isCancelled } ?: run {
                val startedGeneration = generation
                Flight(requestScope.async(start = CoroutineStart.LAZY) {
                    val page = loader().copy(savedAtMs = now())
                    currentCoroutineContext().ensureActive()
                    mutex.withLock {
                        if (generation == startedGeneration) {
                            if (key.persistent) {
                                try { store?.write(key, page) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { /* In-memory snapshot remains useful. */ }
                            }
                            remember(key, page)
                        }
                    }
                    page
                }).also { flights[key] = it }
            }).also { it.readers++ }
        }
        try { return flight.result.await() }
        finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    flight.readers--
                    if (flight.readers == 0) {
                        if (flights[key] === flight) flights.remove(key)
                        flight.result.cancel()
                    }
                }
            }
        }
    }

    suspend fun retainProfiles(revisions: Map<String, Long>) = mutex.withLock {
        val removedPages = pages.keys.removeAll { revisions[it.profileId] != it.revision }
        val removedFlights = flights.keys.filter { revisions[it.profileId] != it.revision }
        removedFlights.forEach {
            flights.remove(it)?.result?.cancel()
        }
        val removedHeaders = savedArtistHeaders.keys.removeAll { (profileId, revision) -> revisions[profileId] != revision }
        store?.retainProfiles(revisions)
        if (removedPages || removedFlights.isNotEmpty() || removedHeaders) mutableUpdates.value++
    }

    suspend fun clear(clearStore: Boolean = true) = mutex.withLock {
        generation++
        flights.values.forEach { it.result.cancel() }
        flights.clear()
        pages.clear()
        savedArtistHeaders.clear()
        if (clearStore) store?.clear()
        mutableUpdates.value++
    }

    private fun remember(key: ServerBrowseKey, page: ServerBrowsePage) {
        var headerChanged = false
        if (key.kind == "artist") {
            page.artists.singleOrNull { it.profileId == key.profileId && it.id == key.value }?.let { artist ->
                val headers = savedArtistHeaders.getOrPut(key.profileId to key.revision) { ArtistHeaders() }.values
                headerChanged = headers.put(artist.id, artist) != artist
            }
        }
        val previousKeys = pages.keys.toSet()
        pages[key] = page
        val ordinary = pages.entries.filter { !it.key.isCatalog }
        var count = ordinary.size
        var items = ordinary.sumOf { it.value.songs.size + it.value.albums.size + it.value.artists.size }
        for ((ordinaryKey, ordinaryPage) in ordinary) {
            if (count <= capacity && items <= maxItems) break
            pages.remove(ordinaryKey)
            count--
            items -= ordinaryPage.songs.size + ordinaryPage.albums.size + ordinaryPage.artists.size
        }
        if (headerChanged || pages.containsKey(key) || previousKeys.any { !pages.containsKey(it) }) mutableUpdates.value++
    }
}
