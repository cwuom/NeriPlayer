package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion

internal class SyncRecentPlayDeletionStore(private val encryptedPrefs: SharedPreferences) {
    private val gson = Gson()
    private val mutation = SyncMutationVersionStore(encryptedPrefs)

    fun getRecentPlayDeletions(): List<SyncRecentPlayDeletion> {
        return synchronized(syncMutationLock) {
            val raw = encryptedPrefs.getString(KEY_RECENT_PLAY_DELETIONS, null).orEmpty()
            if (raw.isBlank()) {
                return@synchronized emptyList()
            }
            val parsed = runCatching {
                val type = object : TypeToken<List<SyncRecentPlayDeletion>>() {}.type
                gson.fromJson<List<SyncRecentPlayDeletion>>(raw, type).orEmpty()
            }.getOrElse { emptyList() }
            normalizeRecentPlayDeletions(parsed)
        }
    }

    fun setRecentPlayDeletions(deletions: List<SyncRecentPlayDeletion>) {
        synchronized(syncMutationLock) {
            persistRecentPlayDeletionsLocked(
                deletions = normalizeRecentPlayDeletions(deletions),
                bumpVersion = false
            )
        }
    }

    fun addRecentPlayDeletions(deletions: List<SyncRecentPlayDeletion>) {
        if (deletions.isEmpty()) {
            return
        }
        synchronized(syncMutationLock) {
            persistRecentPlayDeletionsLocked(
                deletions = normalizeRecentPlayDeletions(getRecentPlayDeletions() + deletions),
                bumpVersion = true
            )
        }
    }

    fun removeRecentPlayDeletion(identity: SongIdentity) {
        synchronized(syncMutationLock) {
            val current = getRecentPlayDeletions()
            val remaining = current
                .filterNot { it.identity() == identity }
            if (remaining != current) {
                persistRecentPlayDeletionsLocked(remaining, bumpVersion = true)
            }
        }
    }

    private fun persistRecentPlayDeletionsLocked(
        deletions: List<SyncRecentPlayDeletion>,
        bumpVersion: Boolean
    ) {
        check(
            encryptedPrefs.commitEdit {
                if (deletions.isEmpty()) {
                    remove(KEY_RECENT_PLAY_DELETIONS)
                } else {
                    putString(KEY_RECENT_PLAY_DELETIONS, gson.toJson(deletions))
                }
                if (bumpVersion) {
                    mutation.bump(this)
                }
            }
        ) { "Failed to persist recent play deletion state" }
    }

    private fun normalizeRecentPlayDeletions(
        deletions: List<SyncRecentPlayDeletion>
    ): List<SyncRecentPlayDeletion> {
        return deletions
            .groupBy { it.stableKey() }
            .map { (_, snapshots) ->
                snapshots.maxWithOrNull(
                    compareBy<SyncRecentPlayDeletion> { it.deletedAt }
                        .thenBy { it.deviceId }
                ) ?: return@map null
            }
            .filterNotNull()
            .sortedByDescending { it.deletedAt }
            .take(MAX_RECENT_PLAY_DELETIONS)
    }
}
