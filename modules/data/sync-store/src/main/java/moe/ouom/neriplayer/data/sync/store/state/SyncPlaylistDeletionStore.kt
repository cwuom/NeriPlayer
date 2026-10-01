package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal class SyncPlaylistDeletionStore(private val encryptedPrefs: SharedPreferences) {
    private val gson = Gson()
    private val mutation = SyncMutationVersionStore(encryptedPrefs)

    fun addDeletedPlaylistId(playlistId: Long) {
        synchronized(syncMutationLock) {
            val current = readDeletedPlaylistIdsLocked().toMutableSet()
            val timestamps = readDeletedPlaylistTimestampsLocked().toMutableMap()
            current.add(playlistId)
            timestamps.putIfAbsent(playlistId, System.currentTimeMillis().coerceAtLeast(1L))
            check(
                encryptedPrefs.commitEdit {
                    putString(KEY_DELETED_PLAYLIST_IDS, current.joinToString(","))
                    putString(KEY_DELETED_PLAYLIST_TIMESTAMPS, gson.toJson(timestamps))
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
            if (ids.isEmpty()) {
                return@synchronized emptyMap()
            }
            val timestamps = readDeletedPlaylistTimestampsLocked().toMutableMap()
            val missingIds = ids.filterNot { timestamps.containsKey(it) }
            if (missingIds.isNotEmpty()) {
                val fallbackTimestamp = System.currentTimeMillis().coerceAtLeast(1L)
                missingIds.forEach { id -> timestamps[id] = fallbackTimestamp }
                check(
                    encryptedPrefs.commitEdit {
                        putString(KEY_DELETED_PLAYLIST_TIMESTAMPS, gson.toJson(timestamps))
                        mutation.bump(this)
                    }
                ) { "Failed to migrate deleted playlist timestamps" }
            }
            timestamps
                .filterKeys(ids::contains)
                .mapValues { (_, timestamp) -> timestamp.coerceAtLeast(1L) }
        }
    }

    fun clearDeletedPlaylistIds() {
        synchronized(syncMutationLock) {
            if (readDeletedPlaylistIdsLocked().isEmpty()) {
                return
            }
            check(
                encryptedPrefs.commitEdit {
                    remove(KEY_DELETED_PLAYLIST_IDS)
                    remove(KEY_DELETED_PLAYLIST_TIMESTAMPS)
                    mutation.bump(this)
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
            if (remaining == current) {
                return
            }
            val timestamps = readDeletedPlaylistTimestampsLocked()
                .filterKeys(remaining::contains)
            check(
                encryptedPrefs.commitEdit {
                    if (remaining.isEmpty()) {
                        remove(KEY_DELETED_PLAYLIST_IDS)
                        remove(KEY_DELETED_PLAYLIST_TIMESTAMPS)
                    } else {
                        putString(KEY_DELETED_PLAYLIST_IDS, remaining.joinToString(","))
                        putString(KEY_DELETED_PLAYLIST_TIMESTAMPS, gson.toJson(timestamps))
                    }
                    mutation.bump(this)
                }
            ) { "Failed to remove deleted playlist state" }
        }
    }

    internal fun readDeletedPlaylistIdsLocked(): Set<Long> {
        val idsString = encryptedPrefs.getString(KEY_DELETED_PLAYLIST_IDS, "") ?: ""
        return if (idsString.isEmpty()) {
            emptySet()
        } else {
            idsString.split(",").mapNotNull { it.toLongOrNull() }.toSet()
        }
    }

    internal fun readDeletedPlaylistTimestampsLocked(): Map<Long, Long> {
        val raw = encryptedPrefs.getString(KEY_DELETED_PLAYLIST_TIMESTAMPS, null).orEmpty()
        if (raw.isBlank()) {
            return emptyMap()
        }
        val type = object : TypeToken<Map<Long, Long>>() {}.type
        return runCatching { gson.fromJson<Map<Long, Long>>(raw, type).orEmpty() }
            .getOrDefault(emptyMap())
    }
}
