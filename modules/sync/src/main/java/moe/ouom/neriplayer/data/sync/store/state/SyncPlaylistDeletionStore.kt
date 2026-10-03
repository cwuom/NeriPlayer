package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import com.google.gson.reflect.TypeToken
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist

internal class SyncPlaylistDeletionStore(private val encryptedPrefs: SharedPreferences, private val files: SyncDeletionStateStorage) {
    private val mutation = SyncMutationVersionStore(encryptedPrefs)

    fun addDeletedPlaylistId(playlistId: Long) {
        synchronized(syncMutationLock) {
            val current = readDeletedPlaylistIdsLocked().toMutableSet()
            val timestamps = readDeletedPlaylistTimestampsLocked().toMutableMap()
            current.add(playlistId)
            timestamps.putIfAbsent(playlistId, System.currentTimeMillis().coerceAtLeast(1L))
            check(
                files.commitEdit {
                    writeDeletionState(this, current, timestamps)
                    mutation.bump(this)
                }
            ) { "Failed to persist deleted playlist state" }
        }
    }

    fun getDeletedPlaylistIds(): Set<Long> {
        return synchronized(syncMutationLock) {
            readDeletedPlaylistIdsLocked()
        }
    }

    fun getDeletedPlaylistTimestamps(): Map<Long, Long> {
        return synchronized(syncMutationLock) {
            val ids = readDeletedPlaylistIdsLocked()
            val timestamps = readDeletedPlaylistTimestampsLocked().toMutableMap()
            if (ids.isEmpty()) {
                return@synchronized emptyMap()
            }
            val missingIds = ids.filterNot { timestamps.containsKey(it) }
            if (missingIds.isNotEmpty()) {
                val fallbackTimestamp = System.currentTimeMillis().coerceAtLeast(1L)
                missingIds.forEach { id -> timestamps[id] = fallbackTimestamp }
                check(
                    files.commitEdit {
                        files.write(this, KEY_DELETED_PLAYLIST_TIMESTAMPS, timestamps)
                        mutation.bump(this)
                    }
                ) { "Failed to migrate deleted playlist timestamps" }
            }
            timestamps.filterKeys(ids::contains)
        }
    }

    fun clearDeletedPlaylistIds() {
        synchronized(syncMutationLock) {
            val changed = readDeletedPlaylistIdsLocked().isNotEmpty()
            check(
                files.commitEdit {
                    if (changed) {
                        writeDeletionState(this, emptySet(), emptyMap())
                        mutation.bump(this)
                    }
                }
            ) { "Failed to clear deleted playlist state" }
        }
    }

    fun removeDeletedPlaylistIds(playlistIds: Set<Long>) {
        if (playlistIds.isEmpty()) {
            return
        }
        synchronized(syncMutationLock) {
            val current = readDeletedPlaylistIdsLocked()
            val remaining = current - playlistIds
            check(
                files.commitEdit {
                    if (remaining != current) {
                        val timestamps = readDeletedPlaylistTimestampsLocked().filterKeys(remaining::contains)
                        writeDeletionState(this, remaining, timestamps)
                        mutation.bump(this)
                    }
                }
            ) { "Failed to remove deleted playlist state" }
        }
    }

    fun setPlaylistDeletionStateIfMutationVersion(expected: Long, playlists: List<SyncPlaylist>, clearRestored: Boolean): Boolean {
        return synchronized(syncMutationLock) {
            if (mutation.getSyncMutationVersion() != expected) return@synchronized false
            val current = getDeletedPlaylistTimestamps()
            // 旧 IDs 补齐时间戳会推进本地版本，本轮应用必须重新取快照
            if (mutation.getSyncMutationVersion() != expected) return@synchronized false
            val merged = mergePlaylistDeletions(current, playlists, clearRestored)
            check(files.commitEdit {
                if (merged != current) writeDeletionState(this, merged.keys, merged)
            }) { "Failed to persist synchronized playlist deletion state" }
            true
        }
    }

    private fun mergePlaylistDeletions(current: Map<Long, Long>, playlists: List<SyncPlaylist>, clearRestored: Boolean): Map<Long, Long> {
        val deleted = current.toMutableMap()
        playlists.forEach { playlist ->
            if (playlist.isDeleted) {
                deleted[playlist.id] = maxOf(deleted[playlist.id] ?: Long.MIN_VALUE, playlist.modifiedAt)
            }
        }
        if (clearRestored) clearRestoredDeletions(deleted, playlists)
        return deleted
    }

    private fun clearRestoredDeletions(deleted: MutableMap<Long, Long>, playlists: List<SyncPlaylist>) {
        playlists.forEach { playlist ->
            if (!playlist.isDeleted) {
                val deletedAt = deleted[playlist.id]
                if (deletedAt != null && playlist.modifiedAt > deletedAt) deleted.remove(playlist.id)
            }
        }
    }

    internal fun writeDeletionState(editor: SharedPreferences.Editor, ids: Set<Long>, timestamps: Map<Long, Long>) {
        files.write(editor, KEY_DELETED_PLAYLIST_IDS, ids.takeIf { it.isNotEmpty() })
        files.write(editor, KEY_DELETED_PLAYLIST_TIMESTAMPS, timestamps.filterKeys(ids::contains).takeIf { it.isNotEmpty() })
    }

    internal fun readDeletedPlaylistIdsLocked(): Set<Long> {
        return files.readPlaylistIds()
    }

    internal fun readDeletedPlaylistTimestampsLocked(): Map<Long, Long> {
        val type = object : TypeToken<Map<Long, Long>>() {}.type
        return files.read<Map<Long, Long>>(KEY_DELETED_PLAYLIST_TIMESTAMPS, type).orEmpty()
    }
}
