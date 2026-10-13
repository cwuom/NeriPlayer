package moe.ouom.neriplayer.platform.subsonic.repository

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.server.ServerSongRef

/** Small process-local cache. Empty responses and failures never replace successful lyrics. */
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
        if (entry.revision != revision || now() - entry.loadedAt >= ttlNanos) {
            entries.remove(ref)
            return null
        }
        return entry.lyrics
    }

    @Synchronized
    fun put(ref: ServerSongRef, lyrics: List<LyricEntry>, revision: Long = 0L) {
        if (lyrics.none { it.text.isNotBlank() }) return
        entries[ref] = Entry(now(), lyrics.toList(), revision)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun removeProfile(profileId: String) { entries.keys.removeAll { it.profileId == profileId } }
}
