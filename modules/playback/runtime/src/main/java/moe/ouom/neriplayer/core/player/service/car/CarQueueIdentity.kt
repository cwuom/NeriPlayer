package moe.ouom.neriplayer.core.player.service.car

import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import java.nio.ByteBuffer
import java.security.MessageDigest

internal data class CarQueueEntry(val id: Long, val index: Int, val song: SongItem)

private val queueIdentityCache = CarQueueIdentityCache()

internal class CarQueueIdentityCache {
    private var source: List<SongItem>? = null
    private var entries: List<CarQueueEntry> = emptyList()

    @Synchronized
    fun entries(songs: List<SongItem>): List<CarQueueEntry> {
        if (source === songs) return entries
        entries = buildCarQueueEntries(songs)
        source = songs
        return entries
    }

    @Synchronized
    fun clear() {
        source = null
        entries = emptyList()
    }
}

internal fun carQueueEntries(songs: List<SongItem>): List<CarQueueEntry> = queueIdentityCache.entries(songs)

internal fun clearCarQueueIdentityCache() = queueIdentityCache.clear()

private fun buildCarQueueEntries(songs: List<SongItem>): List<CarQueueEntry> {
    val occurrences = mutableMapOf<String, Int>()
    val allocated = mutableSetOf<Long>()
    val digest = MessageDigest.getInstance("SHA-256")
    return songs.mapIndexed { index, song ->
        val key = song.stableKey()
        val occurrence = occurrences.getOrDefault(key, 0)
        occurrences[key] = occurrence + 1
        val bytes = digest.digest("$key\u0000$occurrence".toByteArray(Charsets.UTF_8))
        var id = ByteBuffer.wrap(bytes).long and Long.MAX_VALUE
        while (!allocated.add(id)) id = (id + 1L) and Long.MAX_VALUE
        CarQueueEntry(id, index, song)
    }
}

internal fun carQueueItemId(songs: List<SongItem>, selectedIndex: Int): Long =
    carQueueEntries(songs).getOrNull(selectedIndex)?.id ?: -1L

internal fun carQueueIndex(songs: List<SongItem>, queueItemId: Long): Int? =
    carQueueEntries(songs).firstOrNull { it.id == queueItemId }?.index

internal fun carQueueWindow(entries: List<CarQueueEntry>, selectedIndex: Int): List<CarQueueEntry> {
    val limit = CAR_MEDIA_SESSION_QUEUE_LIMIT
    if (entries.size <= limit) return entries
    val start = (selectedIndex - limit / 2).coerceIn(0, entries.size - limit)
    return entries.subList(start, start + limit)
}

private const val CAR_MEDIA_SESSION_QUEUE_LIMIT = 100
