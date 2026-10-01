package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences

internal class SyncMutationVersionStore(private val encryptedPrefs: SharedPreferences) {

    fun getSyncMutationVersion(): Long {
        return synchronized(syncMutationLock) {
            encryptedPrefs.getLong(KEY_SYNC_MUTATION_VERSION, 0L)
        }
    }

    fun markSyncMutation(): Long {
        return synchronized(syncMutationLock) {
            var nextVersion = 0L
            check(
                encryptedPrefs.commitEdit {
                    nextVersion = bump(this)
                }
            ) { "Failed to persist sync mutation version" }
            nextVersion
        }
    }

    fun bump(editor: SharedPreferences.Editor): Long {
        val nextVersion = encryptedPrefs.getLong(KEY_SYNC_MUTATION_VERSION, 0L) + 1L
        editor.putLong(KEY_SYNC_MUTATION_VERSION, nextVersion)
        return nextVersion
    }
}
