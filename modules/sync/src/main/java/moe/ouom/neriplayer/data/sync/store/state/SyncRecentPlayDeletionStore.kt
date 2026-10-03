package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import com.google.gson.reflect.TypeToken
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.merge.history.SyncRecentPlayMerger

internal class SyncRecentPlayDeletionStore(encryptedPrefs: SharedPreferences, private val files: SyncDeletionStateStorage) {
    private val mutation = SyncMutationVersionStore(encryptedPrefs)

    fun getRecentPlayDeletions(): List<SyncRecentPlayDeletion> {
        return synchronized(syncMutationLock) {
            val type = object : TypeToken<List<SyncRecentPlayDeletion>>() {}.type
            val parsed = files.read<List<SyncRecentPlayDeletion>>(KEY_RECENT_PLAY_DELETIONS, type).orEmpty()
            SyncRecentPlayMerger.mergeRecentPlayDeletions(parsed, emptyList())
        }
    }

    fun setRecentPlayDeletions(deletions: List<SyncRecentPlayDeletion>) {
        synchronized(syncMutationLock) {
            persistRecentPlayDeletionsLocked(
                deletions = SyncRecentPlayMerger.mergeRecentPlayDeletions(deletions, emptyList()),
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
                deletions = SyncRecentPlayMerger.mergeRecentPlayDeletions(getRecentPlayDeletions(), deletions),
                bumpVersion = true
            )
        }
    }

    fun removeRecentPlayDeletion(identity: SongIdentity) {
        synchronized(syncMutationLock) {
            val current = getRecentPlayDeletions()
            val normalizedIdentity = SyncRecentPlayMerger.normalizeDeletion(
                SyncRecentPlayDeletion(songId = identity.id, album = identity.album, mediaUri = identity.mediaUri)
            ).identity()
            val remaining = current
                .filterNot { it.identity() == normalizedIdentity }
            persistRecentPlayDeletionsLocked(remaining, bumpVersion = true, changed = remaining != current)
        }
    }

    private fun persistRecentPlayDeletionsLocked(
        deletions: List<SyncRecentPlayDeletion>,
        bumpVersion: Boolean,
        changed: Boolean = true
    ) {
        check(
            files.commitEdit {
                if (changed) {
                    files.write(this, KEY_RECENT_PLAY_DELETIONS, deletions.takeIf { it.isNotEmpty() })
                    if (bumpVersion) mutation.bump(this)
                }
            }
        ) { "Failed to persist recent play deletion state" }
    }

}
