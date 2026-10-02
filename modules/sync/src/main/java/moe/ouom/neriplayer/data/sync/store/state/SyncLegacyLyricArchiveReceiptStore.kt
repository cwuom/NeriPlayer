package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences

internal class SyncLegacyLyricArchiveReceiptStore(
    private val preferences: SharedPreferences,
    private val files: SyncDeletionStateStorage
) {
    fun isCompleted(namespace: String, sourceHash: String): Boolean = synchronized(syncMutationLock) {
        val key = key(namespace, sourceHash)
        check(files.confirm()) { "Failed to confirm legacy lyric archive recovery" }
        preferences.getBoolean(key, false)
    }

    fun markCompleted(namespace: String, sourceHash: String) = synchronized(syncMutationLock) {
        val key = key(namespace, sourceHash)
        check(files.commitEdit { putBoolean(key, true) }) { "Failed to persist legacy lyric archive recovery" }
    }

    private fun key(namespace: String, sourceHash: String): String {
        require(namespace.matches(Sha256) && sourceHash.matches(Sha256)) { "Invalid legacy lyric archive receipt" }
        return "legacy_lyric_archive_${namespace}_$sourceHash"
    }

    private companion object { val Sha256 = Regex("[0-9a-f]{64}") }
}
