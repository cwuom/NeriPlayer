package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences

internal class SyncLegacyLyricOptimizationStore(
    private val preferences: SharedPreferences,
    private val files: SyncDeletionStateStorage
) {
    fun isEnabled(): Boolean = synchronized(syncMutationLock) {
        check(files.confirm()) { "Failed to confirm legacy lyric optimization preference" }
        preferences.getBoolean(KEY, false)
    }

    fun setEnabled(enabled: Boolean) = synchronized(syncMutationLock) {
        check(files.confirm()) { "Failed to confirm legacy lyric optimization preference" }
        val previous = preferences.getBoolean(KEY, false)
        if (previous == enabled) return@synchronized
        val committed = files.commitEdit {
            putBoolean(KEY, enabled)
            SyncMutationVersionStore(preferences).bump(this)
        }
        if (!committed) {
            // commit 失败也可能修改内存，回退选择后才能让其它持有方继续读取
            preferences.commitEdit { putBoolean(KEY, previous) }
            error("Failed to persist legacy lyric optimization preference")
        }
    }

    private companion object { const val KEY = "optimize_legacy_lyrics" }
}
