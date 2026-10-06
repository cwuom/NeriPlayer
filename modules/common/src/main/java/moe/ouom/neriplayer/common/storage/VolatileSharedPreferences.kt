package moe.ouom.neriplayer.common.storage

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * 只存在于当前进程的 SharedPreferences，用于加密存储暂时无法打开时的降级
 *
 * 同名实例在进程内共享，避免不同组件各自看到一份空状态；内容随进程结束丢弃，
 * 不会覆盖仍保留在磁盘上的原始加密文件
 */
class VolatileSharedPreferences private constructor() : SharedPreferences {
    private val lock = Any()
    private val values = HashMap<String?, Any>()
    private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String?, *> = synchronized(lock) { HashMap(values) }

    override fun getString(key: String?, defValue: String?): String? = read(key) ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        read<Set<String>>(key)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = read(key) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = read(key) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = read(key) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = read(key) ?: defValue

    override fun contains(key: String?): Boolean = synchronized(lock) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        synchronized(lock) { listeners += listener }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        synchronized(lock) { listeners -= listener }
    }

    private inline fun <reified T> read(key: String?): T? = synchronized(lock) { values[key] as? T }

    private fun applyChanges(clear: Boolean, changes: Map<String?, Any?>) {
        val (changedKeys, notified) = synchronized(lock) {
            val cleared = if (clear) values.keys.toList().also { values.clear() } else emptyList()
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            (cleared + changes.keys).distinct() to listeners.toList()
        }
        changedKeys.forEach { key -> notified.forEach { it.onSharedPreferenceChanged(this, key) } }
    }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String?, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?) = stage(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?) = stage(key, values?.toSet())

        override fun putInt(key: String?, value: Int) = stage(key, value)

        override fun putLong(key: String?, value: Long) = stage(key, value)

        override fun putFloat(key: String?, value: Float) = stage(key, value)

        override fun putBoolean(key: String?, value: Boolean) = stage(key, value)

        override fun remove(key: String?) = stage(key, null)

        override fun clear(): SharedPreferences.Editor = apply { clear = true }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            applyChanges(clear, LinkedHashMap(changes))
            changes.clear()
            clear = false
        }

        private fun stage(key: String?, value: Any?): SharedPreferences.Editor = apply { changes[key] = value }
    }

    companion object {
        private val sharedByName = ConcurrentHashMap<String, VolatileSharedPreferences>()

        /** 同一进程内同名存储只降级一次，之后的打开都返回同一份内存数据 */
        fun shared(name: String): VolatileSharedPreferences =
            sharedByName.getOrPut(name) { VolatileSharedPreferences() }

        fun existing(name: String): VolatileSharedPreferences? = sharedByName[name]
    }
}
