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
    private val configurationLock: Any,
    private val readCheckpoint: () -> Long
) {
    fun save(timestamp: Long) = synchronized(configurationLock) {
        preferences.edit { putLong(KEY_LAST_COMPLETED_SYNC_TIME, timestamp) }
        notifyChanged()
    }

    fun read(): Long = synchronized(configurationLock) {
        preferences.getLong(KEY_LAST_COMPLETED_SYNC_TIME, readCheckpoint())
    }

    fun captureConfigurationGuard(): (() -> Unit) -> Boolean {
        val expectedGeneration = synchronized(configurationLock) { configurationGeneration() }
        return { write ->
            synchronized(configurationLock) {
                if (configurationGeneration() != expectedGeneration) {
                    false
                } else {
                    write()
                    true
                }
            }
        }
    }

    fun editConfiguration(action: SharedPreferences.Editor.() -> Unit) = synchronized(configurationLock) {
        val nextGeneration = configurationGeneration() + 1L
        preferences.edit {
            action()
            // clear() 也会清除代次，必须在配置编辑后保存新代次
            putLong(KEY_CONFIGURATION_GENERATION, nextGeneration)
        }
    }

    private fun configurationGeneration(): Long = preferences.getLong(KEY_CONFIGURATION_GENERATION, 0L)

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
        const val KEY_CONFIGURATION_GENERATION = "sync_configuration_generation"
    }
}
