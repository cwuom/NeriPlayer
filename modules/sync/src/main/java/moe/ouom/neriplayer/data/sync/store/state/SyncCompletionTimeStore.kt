package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

internal class SyncCompletionTimeStore(
    private val preferences: SharedPreferences,
    private val changes: MutableStateFlow<Long>,
    private val readCheckpoint: () -> Long
) {
    fun save(timestamp: Long) {
        preferences.edit { putLong(KEY_LAST_COMPLETED_SYNC_TIME, timestamp) }
        notifyChanged()
    }

    fun read(): Long = preferences.getLong(KEY_LAST_COMPLETED_SYNC_TIME, readCheckpoint())

    fun observe(): Flow<Long> = changes.map { read() }.distinctUntilChanged()

    fun clear(editor: SharedPreferences.Editor) {
        editor.remove(KEY_LAST_COMPLETED_SYNC_TIME)
    }

    fun notifyChanged() {
        // 加密偏好可能由不同包装实例访问，共享通知只触发各观察者重读自己的存储
        changes.update { it + 1L }
    }

    private companion object {
        const val KEY_LAST_COMPLETED_SYNC_TIME = "last_completed_sync_time"
    }
}
