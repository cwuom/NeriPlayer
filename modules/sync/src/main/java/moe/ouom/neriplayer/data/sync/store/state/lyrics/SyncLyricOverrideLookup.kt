package moe.ouom.neriplayer.data.sync.store.state.lyrics

import moe.ouom.neriplayer.data.sync.store.state.SyncDeletionStateStorage
import moe.ouom.neriplayer.data.sync.store.state.syncMutationLock
import moe.ouom.neriplayer.data.sync.store.state.KEY_LYRIC_OVERRIDES
import moe.ouom.neriplayer.data.sync.store.state.KEY_LEGACY_LYRIC_RECOVERY
import moe.ouom.neriplayer.data.sync.store.state.SyncDeletionGenerationChangedException

import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.identity.identity
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy

internal class SyncLyricOverrideLookup(private val files: SyncDeletionStateStorage) {
    fun read(identityKeys: Set<String>, checkActive: () -> Unit): List<SyncSong> =
        readStable { scan(identityKeys, checkActive) }

    fun readLegacy(identityKey: String?, checkActive: () -> Unit): List<SyncSong> = readStable {
        val candidates = linkedSetOf<SyncSong>()
        visitRecords(checkActive) { song ->
            if (hasUnconfirmedLegacyLyrics(song) && (identityKey == null || song.identity().stableKey() == identityKey)) {
                candidates += song.copy(lyricSyncRevision = 0L)
            }
        }
        candidates.toList()
    }

    private fun <T> readStable(read: () -> T): T {
        repeat(MAX_GENERATION_ATTEMPTS) { attempt ->
            try {
                return read()
            } catch (changed: SyncDeletionGenerationChangedException) {
                if (attempt == MAX_GENERATION_ATTEMPTS - 1) throw changed
            }
        }
        error("Sync state lookup exhausted generation attempts")
    }

    private fun scan(identityKeys: Set<String>, checkActive: () -> Unit): List<SyncSong> {
        val selected = LinkedHashMap<String, SyncSong>()
        visitRecords(checkActive) { song ->
            val key = song.identity().stableKey()
            if (key in identityKeys) {
                val previous = selected[key]
                val normalized = SyncSongLyricMergePolicy.prepareLegacy(song)
                if (normalized.lyricSyncRevision > 0L) {
                    selected[key] = if (previous == null) normalized else
                        SyncSongLyricMergePolicy.merge(previous, listOf(previous, normalized))
                }
            }
        }
        return selected.values.toList()
    }

    private fun visitRecords(checkActive: () -> Unit, visit: (SyncSong) -> Unit) {
        val markers = synchronized(syncMutationLock) {
            check(files.confirm()) { "Failed to confirm durable lyric overrides" }
            keys.map(files::marker)
        }
        for (storageKey in keys) {
            files.visitObjectArray(storageKey, SyncSong::class.java, checkActive, visit)
        }
        synchronized(syncMutationLock) {
            if (keys.map(files::marker) != markers) throw SyncDeletionGenerationChangedException()
        }
    }

    private companion object {
        const val MAX_GENERATION_ATTEMPTS = 3
        val keys = listOf(KEY_LYRIC_OVERRIDES, KEY_LEGACY_LYRIC_RECOVERY)
    }
}
