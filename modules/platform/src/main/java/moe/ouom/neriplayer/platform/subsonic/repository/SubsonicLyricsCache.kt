package moe.ouom.neriplayer.platform.subsonic.repository

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.server.ServerSongRef

/** short-lived empty results avoid repeated requests without replacing available lyrics */
internal class SubsonicLyricsCache(
    private val capacity: Int = 32,
    private val ttlNanos: Long = 10L * 60 * 1_000_000_000,
    private val now: () -> Long = System::nanoTime
) {
    private data class Entry(val loadedAt: Long, val lyrics: List<LyricEntry>, val revision: Long)
    private val entries = LinkedHashMap<ServerSongRef, Entry>(capacity, 0.75f, true)

    @Synchronized
    fun get(ref: ServerSongRef, revision: Long = 0L): List<LyricEntry>? {
        val entry = entries[ref] ?: return null
        val lifetime = if (entry.lyrics.isEmpty()) minOf(ttlNanos, EMPTY_TTL_NANOS) else ttlNanos
        if (entry.revision != revision || now() - entry.loadedAt >= lifetime) {
            entries.remove(ref)
            return null
        }
        return entry.lyrics
    }

    @Synchronized
    fun put(ref: ServerSongRef, lyrics: List<LyricEntry>, revision: Long = 0L) {
        val snapshot = lyrics.takeIf { lines -> lines.any { it.text.isNotBlank() } }?.toList().orEmpty()
        if (snapshot.isEmpty() && get(ref, revision)?.isNotEmpty() == true) return
        entries[ref] = Entry(now(), snapshot, revision)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun remove(ref: ServerSongRef) { entries.remove(ref) }

    @Synchronized
    fun removeProfile(profileId: String) { entries.keys.removeAll { it.profileId == profileId } }

    private companion object {
        const val EMPTY_TTL_NANOS = 60L * 1_000_000_000L
    }
}
