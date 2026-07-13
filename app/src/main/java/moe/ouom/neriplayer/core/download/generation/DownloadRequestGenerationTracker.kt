package moe.ouom.neriplayer.core.download.generation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal class DownloadRequestGenerationTracker {
    private val requestGeneration = AtomicLong(0L)
    private val generationsBySongKey = ConcurrentHashMap<String, Long>()

    fun begin(songs: Collection<SongItem>): DownloadRequestGenerationSnapshot {
        val songKeys = songs
            .mapTo(linkedSetOf()) { song -> song.stableKey() }
            .filter(String::isNotBlank)
        val generation = requestGeneration.incrementAndGet()
        songKeys.forEach { songKey ->
            generationsBySongKey[songKey] = generation
        }
        return DownloadRequestGenerationSnapshot(
            generation = generation,
            songCount = songKeys.size
        )
    }

    fun invalidate(songKeys: Collection<String>): Int {
        val keys = songKeys.filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) {
            return 0
        }
        keys.forEach(generationsBySongKey::remove)
        requestGeneration.incrementAndGet()
        return keys.size
    }

    fun isCurrent(songKey: String, generation: Long): Boolean {
        return generationsBySongKey[songKey] == generation
    }

    fun cancellationGeneration(songKey: String): Long? {
        return generationsBySongKey[songKey]
    }

    fun cancellationGenerations(songKeys: Collection<String>): Map<String, Long?> {
        return songKeys.associateWith(::cancellationGeneration)
    }

    fun shouldKeepCancellationCleanup(
        songKey: String,
        cancellationGeneration: Long?,
        cancelled: Boolean
    ): Boolean {
        return moe.ouom.neriplayer.core.download.shouldKeepCancellationCleanup(
            currentGeneration = generationsBySongKey[songKey],
            cancellationGeneration = cancellationGeneration,
            cancelled = cancelled
        )
    }
}

internal data class DownloadRequestGenerationSnapshot(
    val generation: Long,
    val songCount: Int
)
