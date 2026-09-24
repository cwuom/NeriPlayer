package moe.ouom.neriplayer.data.local.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.LinkedHashMap

/**
 * 让本地资产缓存按根目录和歌曲身份失效，避免一首下载触发全库重探测
 */
object LocalAssetInvalidationBus {
    private val rootGeneration = MutableStateFlow(LocalStorageRootGeneration.current())
    private val songRevisionSignal = MutableStateFlow(0L)
    private val songRevisionLock = Any()
    private val songRevisions = LinkedHashMap<String, Long>(16, 0.75f, true)
    private var nextSongRevision = 0L
    private var evictedRevisionFloor = 0L

    val rootGenerationFlow: StateFlow<Long> = rootGeneration.asStateFlow()

    fun revisionFlow(songKey: String): Flow<Long> {
        return songRevisionSignal
            .map { currentSongRevision(songKey) }
            .distinctUntilChanged()
    }

    fun currentSongRevision(songKey: String): Long {
        return synchronized(songRevisionLock) {
            songRevisions[songKey] ?: evictedRevisionFloor
        }
    }

    fun bumpSong(songKey: String) {
        if (songKey.isBlank()) return
        synchronized(songRevisionLock) {
            if (nextSongRevision == Long.MAX_VALUE) {
                songRevisions.clear()
                evictedRevisionFloor = 0L
                nextSongRevision = 1L
            } else {
                nextSongRevision++
            }
            songRevisions.remove(songKey)
            songRevisions[songKey] = nextSongRevision
            while (songRevisions.size > MAX_TRACKED_SONG_REVISIONS) {
                val iterator = songRevisions.entries.iterator()
                if (!iterator.hasNext()) break
                val evicted = iterator.next()
                evictedRevisionFloor = maxOf(evictedRevisionFloor, evicted.value)
                iterator.remove()
            }
            songRevisionSignal.value = nextSongRevision
        }
    }

    fun bumpSongs(songKeys: Iterable<String>) {
        songKeys.forEach(::bumpSong)
    }

    fun publishRootChanged(generation: Long = LocalStorageRootGeneration.current()) {
        rootGeneration.value = generation
    }

    internal fun resetForTest() {
        synchronized(songRevisionLock) {
            songRevisions.clear()
            nextSongRevision = 0L
            evictedRevisionFloor = 0L
            songRevisionSignal.value = 0L
        }
        rootGeneration.value = LocalStorageRootGeneration.current()
    }

    internal fun songRevisionEntryCountForTest(): Int = synchronized(songRevisionLock) {
        songRevisions.size
    }

    private const val MAX_TRACKED_SONG_REVISIONS = 4_096
}
