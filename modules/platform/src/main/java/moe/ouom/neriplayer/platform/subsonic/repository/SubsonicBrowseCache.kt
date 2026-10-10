package moe.ouom.neriplayer.platform.subsonic.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.server.ServerSongRef

enum class ServerLibraryCategory {
    ALBUMS, SONGS;

    fun browseKind(query: String): String = when (this) {
        ALBUMS -> if (query.isBlank()) "albums" else "search-albums"
        SONGS -> if (query.isBlank()) "songs" else "search"
    }
}

/** Include category, page and configuration revision; album/song searches never share a page. */
data class ServerBrowseKey(
    val profileId: String, val revision: Long, val kind: String,
    val value: String = "", val offset: Int = 0, val size: Int = 30
) {
    val cacheKey: String get() = "$profileId:$revision:$kind:${ServerSongRef.encode(value)}:$offset:$size"
    val persistent: Boolean get() = !kind.startsWith("search")
}

data class ServerBrowsePage(
    val albums: List<ServerAlbum> = emptyList(),
    val songs: List<SongItem> = emptyList(),
    val savedAtMs: Long = 0L,
    val hasMore: Boolean = false
) {
    fun isFresh(nowMs: Long): Boolean = nowMs - savedAtMs in 0 until 60_000L
}

interface ServerBrowseStore {
    suspend fun read(key: ServerBrowseKey): ServerBrowsePage?
    suspend fun write(key: ServerBrowseKey, page: ServerBrowsePage)
    suspend fun retainProfiles(revisions: Map<String, Long>)
    suspend fun clear()
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
    private var generation = 0L

    suspend fun snapshot(key: ServerBrowseKey): ServerBrowsePage? = mutex.withLock {
        pages[key] ?: if (key.persistent) {
            try { store?.read(key)?.also { remember(key, it) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } // Cache storage must not prevent an online fetch.
        } else null
    }

    suspend fun fetch(key: ServerBrowseKey, force: Boolean = false,
                      loader: suspend () -> ServerBrowsePage): ServerBrowsePage {
        if (!force) snapshot(key)?.takeIf { it.isFresh(now()) }?.let { return it }
        val flight = mutex.withLock {
            if (!force) pages[key]?.takeIf { it.isFresh(now()) }?.let { return it }
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
        generation++
        pages.keys.removeAll { revisions[it.profileId] != it.revision }
        flights.keys.filter { revisions[it.profileId] != it.revision }.forEach {
            flights.remove(it)?.result?.cancel()
        }
        store?.retainProfiles(revisions)
    }

    suspend fun clear(clearStore: Boolean = true) = mutex.withLock {
        generation++
        flights.values.forEach { it.result.cancel() }
        flights.clear()
        pages.clear()
        if (clearStore) store?.clear()
    }

    private fun remember(key: ServerBrowseKey, page: ServerBrowsePage) {
        pages[key] = page
        while (pages.size > capacity || pages.values.sumOf { it.songs.size + it.albums.size } > maxItems) {
            pages.remove(pages.keys.first())
        }
    }
}
