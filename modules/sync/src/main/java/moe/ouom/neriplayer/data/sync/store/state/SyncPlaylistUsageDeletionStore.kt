package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal class SyncPlaylistUsageDeletionStore(private val encryptedPrefs: SharedPreferences) {
    private val gson = Gson()
    private val mutation = SyncMutationVersionStore(encryptedPrefs)

    fun getPlaylistUsageDeletions(): Map<String, Long> {
        return synchronized(syncMutationLock) {
            val raw = encryptedPrefs.getString(KEY_PLAYLIST_USAGE_DELETIONS, null).orEmpty()
            if (raw.isBlank()) {
                return@synchronized emptyMap()
            }
            runCatching { normalizePlaylistUsageDeletions(parseUsageDeletions(raw)) }
                .getOrDefault(emptyMap())
        }
    }

    private fun parseUsageDeletions(raw: String): Map<String?, Long?> {
        val type = object : TypeToken<Map<String?, Long?>>() {}.type
        return gson.fromJson<Map<String?, Long?>>(raw, type).orEmpty()
    }

    fun addPlaylistUsageDeletion(playlistKey: String, deletedAt: Long = System.currentTimeMillis()) {
        val normalizedKey = playlistKey.trim()
        if (normalizedKey.isEmpty()) {
            return
        }
        synchronized(syncMutationLock) {
            val current = getPlaylistUsageDeletions().toMutableMap()
            val normalizedTimestamp = deletedAt.coerceAtLeast(1L)
            current[normalizedKey] = maxOf(current[normalizedKey] ?: 0L, normalizedTimestamp)
            persistPlaylistUsageDeletionsLocked(current, bumpVersion = true)
        }
    }

    fun removePlaylistUsageDeletion(playlistKey: String, bumpVersion: Boolean = true) {
        val normalizedKey = playlistKey.trim()
        if (normalizedKey.isEmpty()) {
            return
        }
        synchronized(syncMutationLock) {
            val current = getPlaylistUsageDeletions().toMutableMap()
            if (current.remove(normalizedKey) == null) {
                return
            }
            persistPlaylistUsageDeletionsLocked(current, bumpVersion)
        }
    }

    private fun persistPlaylistUsageDeletionsLocked(
        deletions: Map<String, Long>,
        bumpVersion: Boolean
    ) {
        val normalized = normalizePlaylistUsageDeletions(deletions)
        check(
            encryptedPrefs.commitEdit {
                if (normalized.isEmpty()) {
                    remove(KEY_PLAYLIST_USAGE_DELETIONS)
                } else {
                    putString(KEY_PLAYLIST_USAGE_DELETIONS, gson.toJson(normalized))
                }
                if (bumpVersion) {
                    mutation.bump(this)
                }
            }
        ) { "Failed to persist playlist usage deletion state" }
    }

    private fun normalizePlaylistUsageDeletions(
        deletions: Map<out String?, Long?>
    ): Map<String, Long> {
        val normalized = mutableMapOf<String, Long>()
        deletions.forEach { (key, timestamp) ->
            val normalizedKey = key?.trim()
            if (normalizedKey.isNullOrEmpty() || timestamp == null) {
                return@forEach
            }
            val normalizedTimestamp = timestamp.coerceAtLeast(1L)
            normalized[normalizedKey] = maxOf(
                normalized[normalizedKey] ?: 0L,
                normalizedTimestamp
            )
        }
        return normalized.entries
            .sortedByDescending { it.value }
            .take(MAX_PLAYLIST_USAGE_DELETIONS)
            .associate { it.key to it.value }
    }
}
