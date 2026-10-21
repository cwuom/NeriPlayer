package moe.ouom.neriplayer.common.storage

import android.content.SharedPreferences

/** 仅在加密数据同步写入成功后发布内存状态，让账户保存与退出能识别真实写入失败 */
internal class SoftwareEncryptedSharedPreferences(
    private val storage: CompatibilityPreferencesFile,
    initialValues: Map<String?, Any>
) : SharedPreferences {
    private val lock = Any()
    private var values = initialValues
    private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): Map<String?, *> = synchronized(lock) {
        values.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
    }

    override fun getString(key: String?, defValue: String?): String? = read(key) ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        read<Set<String>>(key)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = read(key) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = read(key) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = read(key) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = read(key) ?: defValue

    override fun contains(key: String?): Boolean = synchronized(lock) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(lock) { listeners += listener }
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        synchronized(lock) { listeners -= listener }
    }

    private inline fun <reified T> read(key: String?): T? = synchronized(lock) { values[key] as? T }

    private fun commit(clear: Boolean, changes: Map<String?, Any?>): Boolean {
        val changedKeys: List<String?>
        val notified: List<SharedPreferences.OnSharedPreferenceChangeListener>
        synchronized(lock) {
            val updated = if (clear) LinkedHashMap() else LinkedHashMap(values)
            changes.forEach { (key, value) -> if (value == null) updated.remove(key) else updated[key] = value }
            try {
                storage.write(updated)
            } catch (_: Exception) {
                return false
            }
            changedKeys = (values.keys + updated.keys).filter { values[it] != updated[it] }
            values = updated
            notified = listeners.toList()
        }
        changedKeys.forEach { key -> notified.forEach { it.onSharedPreferenceChanged(this, key) } }
        return true
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
            if (!commit(clear, LinkedHashMap(changes))) return false
            changes.clear()
            clear = false
            return true
        }

        override fun apply() {
            check(commit()) { "Cannot persist encrypted account preferences" }
        }

        private fun stage(key: String?, value: Any?): SharedPreferences.Editor = apply { changes[key] = value }
    }
}
