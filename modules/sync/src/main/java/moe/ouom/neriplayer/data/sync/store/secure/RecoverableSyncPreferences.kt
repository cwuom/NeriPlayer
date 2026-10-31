package moe.ouom.neriplayer.data.sync.store.secure

import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.common.storage.SecurePreferencesOpener
import moe.ouom.neriplayer.data.sync.store.state.KEY_CONFIGURATION_GENERATION
import moe.ouom.neriplayer.data.sync.store.state.KEY_SYNC_MUTATION_VERSION

class SyncStorageUnavailableException(cause: Throwable? = null) :
    IOException("Secure sync storage is unavailable", cause)

/** 同名存储共享可重开的句柄，密钥不可用时拒绝读写，避免把空状态写回同步元数据 */
internal class RecoverableSyncPreferences(private val open: () -> SharedPreferences) : SharedPreferences {
    private val lock = Any()
    private var current: SharedPreferences? = null
    private var openedBefore = false
    private var session = 0L
    private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    val isPersistent: Boolean
        get() = try {
            access { true }
        } catch (_: SyncStorageUnavailableException) {
            false
        }

    private fun persistent(): SharedPreferences {
        current?.let { return it }
        val preferences = open()
        if (!SecurePreferencesOpener.isPersistent(preferences)) throw SyncStorageUnavailableException()
        if (openedBefore) invalidateSessions(preferences)
        listeners.forEach(preferences::registerOnSharedPreferenceChangeListener)
        current = preferences
        openedBefore = true
        return preferences
    }

    private fun invalidateSessions(preferences: SharedPreferences) {
        // 故障期间本地数据可能继续保存，旧快照和配置确认都必须在恢复后失效
        val mutation = Math.addExact(preferences.getLong(KEY_SYNC_MUTATION_VERSION, 0L), 1L)
        val configuration = Math.addExact(preferences.getLong(KEY_CONFIGURATION_GENERATION, 0L), 1L)
        if (!preferences.edit().putLong(KEY_SYNC_MUTATION_VERSION, mutation)
                .putLong(KEY_CONFIGURATION_GENERATION, configuration).commit()) {
            throw SyncStorageUnavailableException()
        }
    }

    private fun <T> access(action: (SharedPreferences) -> T): T = synchronized(lock) {
        try {
            action(persistent())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            current?.let { previous ->
                session++
                listeners.forEach { listener -> runCatching { previous.unregisterOnSharedPreferenceChangeListener(listener) } }
            }
            current = null
            throw if (error is SyncStorageUnavailableException) error else SyncStorageUnavailableException(error)
        }
    }

    override fun getAll(): Map<String, *> = access { it.all }
    override fun getString(key: String?, defValue: String?): String? = access { it.getString(key, defValue) }
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        access { it.getStringSet(key, defValues) }
    override fun getInt(key: String?, defValue: Int): Int = access { it.getInt(key, defValue) }
    override fun getLong(key: String?, defValue: Long): Long = access { it.getLong(key, defValue) }
    override fun getFloat(key: String?, defValue: Float): Float = access { it.getFloat(key, defValue) }
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = access { it.getBoolean(key, defValue) }
    override fun contains(key: String?): Boolean = access { it.contains(key) }
    override fun edit(): SharedPreferences.Editor = access { Editor(it, it.edit(), session) }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(lock) {
            if (listeners.add(listener)) current?.registerOnSharedPreferenceChangeListener(listener)
        }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(lock) {
            listeners.remove(listener)
            current?.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    private inner class Editor(
        private val owner: SharedPreferences,
        private val editor: SharedPreferences.Editor,
        private val capturedSession: Long
    ) : SharedPreferences.Editor {
        private fun <T> use(action: () -> T): T = synchronized(lock) {
            // 重开前创建的编辑器不能在新会话中提交旧快照
            if (current !== owner || capturedSession != session) throw SyncStorageUnavailableException()
            access { action() }
        }

        private fun stage(action: SharedPreferences.Editor.() -> Unit): SharedPreferences.Editor = apply {
            use { editor.action() }
        }

        override fun putString(key: String?, value: String?) = stage { putString(key, value) }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = stage { putStringSet(key, values) }
        override fun putInt(key: String?, value: Int) = stage { putInt(key, value) }
        override fun putLong(key: String?, value: Long) = stage { putLong(key, value) }
        override fun putFloat(key: String?, value: Float) = stage { putFloat(key, value) }
        override fun putBoolean(key: String?, value: Boolean) = stage { putBoolean(key, value) }
        override fun remove(key: String?) = stage { remove(key) }
        override fun clear() = stage { clear() }
        override fun commit(): Boolean = use { editor.commit() }
        override fun apply() = use { editor.apply() }
    }
}
