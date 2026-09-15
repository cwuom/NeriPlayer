package moe.ouom.neriplayer.data.local.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * 让本地资产缓存按根目录和歌曲身份失效，避免一首下载触发全库重探测
 */
object LocalAssetInvalidationBus {
    private val rootGeneration = MutableStateFlow(LocalStorageRootGeneration.current())
    private val songRevisions = ConcurrentHashMap<String, MutableStateFlow<Long>>()

    val rootGenerationFlow: StateFlow<Long> = rootGeneration.asStateFlow()

    fun revisionFlow(songKey: String): StateFlow<Long> {
        return songRevisions.getOrPut(songKey) { MutableStateFlow(0L) }.asStateFlow()
    }

    fun currentSongRevision(songKey: String): Long {
        return songRevisions[songKey]?.value ?: 0L
    }

    fun bumpSong(songKey: String) {
        if (songKey.isBlank()) return
        songRevisions.getOrPut(songKey) { MutableStateFlow(0L) }.update { revision ->
            if (revision == Long.MAX_VALUE) 0L else revision + 1L
        }
    }

    fun bumpSongs(songKeys: Iterable<String>) {
        songKeys.forEach(::bumpSong)
    }

    fun publishRootChanged(generation: Long = LocalStorageRootGeneration.current()) {
        rootGeneration.value = generation
    }

    internal fun resetForTest() {
        songRevisions.clear()
        rootGeneration.value = LocalStorageRootGeneration.current()
    }
}
