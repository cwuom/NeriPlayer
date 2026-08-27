package moe.ouom.neriplayer.core.download.generation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem

internal class DownloadRequestGenerationTracker {
    private val requestGeneration = AtomicLong(0L)
    private val generationsBySongKey = ConcurrentHashMap<String, Long>()
    private val mutationLock = Any()

    fun begin(songs: Collection<SongItem>): DownloadRequestGenerationSnapshot {
        val songKeys = songs
            .mapTo(linkedSetOf()) { song -> song.stableKey() }
            .filter(String::isNotBlank)
        return synchronized(mutationLock) {
            val generation = requestGeneration.incrementAndGet()
            songKeys.forEach { songKey ->
                generationsBySongKey[songKey] = generation
            }
            DownloadRequestGenerationSnapshot(
                generation = generation,
                songCount = songKeys.size
            )
        }
    }

    fun invalidate(songKeys: Collection<String>): Int {
        return snapshotAndInvalidate(songKeys).invalidatedCount
    }

    fun snapshotAndInvalidate(
        songKeys: Collection<String>
    ): DownloadRequestCancellationSnapshot {
        val keys = songKeys
            .asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (keys.isEmpty()) {
            return DownloadRequestCancellationSnapshot(
                generationsBySongKey = emptyMap(),
                invalidatedCount = 0
            )
        }
        return synchronized(mutationLock) {
            val cancellationGenerations = keys.associateWith { songKey ->
                generationsBySongKey[songKey]
            }
            keys.forEach(generationsBySongKey::remove)
            requestGeneration.incrementAndGet()
            DownloadRequestCancellationSnapshot(
                generationsBySongKey = cancellationGenerations,
                invalidatedCount = keys.size
            )
        }
    }

    fun isCurrent(songKey: String, generation: Long): Boolean {
        return generationsBySongKey[songKey] == generation
    }

    fun reuseOrBegin(song: SongItem, reuseCurrent: Boolean): Long {
        return synchronized(mutationLock) {
            if (reuseCurrent) {
                currentGeneration(song.stableKey())?.let { generation ->
                    return@synchronized generation
                }
            }
            begin(listOf(song)).generation
        }
    }

    fun currentGeneration(songKey: String): Long? {
        return generationsBySongKey[songKey]
    }

    fun cancellationGeneration(songKey: String): Long? {
        return currentGeneration(songKey)
    }

    fun cancellationGenerations(songKeys: Collection<String>): Map<String, Long?> {
        return synchronized(mutationLock) {
            songKeys.associateWith(::cancellationGeneration)
        }
    }

    fun shouldKeepCancellationCleanup(
        songKey: String,
        cancellationGeneration: Long?,
        cancelled: Boolean
    ): Boolean {
        if (cancellationGeneration == null && !cancelled) {
            return false
        }
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

internal data class DownloadRequestCancellationSnapshot(
    val generationsBySongKey: Map<String, Long?>,
    val invalidatedCount: Int
)
