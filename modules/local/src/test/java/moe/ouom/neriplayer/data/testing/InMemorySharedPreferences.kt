package moe.ouom.neriplayer.data.testing

import android.content.Context
import android.content.SharedPreferences
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer

/**
 * 进程内的 SharedPreferences 实现, 语义贴近 Android: commit/apply 先 clear 再落地改动
 */
class InMemorySharedPreferences(initial: Map<String, Any?> = emptyMap()) : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>(initial)
    private val listeners = LinkedHashSet<SharedPreferences.OnSharedPreferenceChangeListener>()

    var commitCount: Int = 0
        private set

    fun snapshot(): Map<String, Any?> = synchronized(values) { values.toMap() }

    override fun getAll(): MutableMap<String, *> = synchronized(values) { LinkedHashMap(values) }

    override fun getString(key: String, defValue: String?): String? = read(key, defValue)

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        return synchronized(values) {
            if (key in values) (values[key] as Set<String>?)?.toMutableSet() else defValues
        }
    }

    override fun getInt(key: String, defValue: Int): Int = read(key, defValue)

    override fun getLong(key: String, defValue: Long): Long = read(key, defValue)

    override fun getFloat(key: String, defValue: Float): Float = read(key, defValue)

    override fun getBoolean(key: String, defValue: Boolean): Boolean = read(key, defValue)

    override fun contains(key: String): Boolean = synchronized(values) { key in values }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        synchronized(listeners) { listeners += listener }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        synchronized(listeners) { listeners -= listener }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(key: String, defValue: T): T = synchronized(values) {
        if (key in values) values[key] as T else defValue
    }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String, value: String?) = put(key, value)

        override fun putStringSet(key: String, values: MutableSet<String>?) =
            put(key, values?.toSet())

        override fun putInt(key: String, value: Int) = put(key, value)

        override fun putLong(key: String, value: Long) = put(key, value)

        override fun putFloat(key: String, value: Float) = put(key, value)

        override fun putBoolean(key: String, value: Boolean) = put(key, value)

        override fun remove(key: String) = put(key, REMOVED)

        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }

        override fun commit(): Boolean {
            val changedKeys = synchronized(values) {
                if (clearRequested) values.clear()
                changes.forEach { (key, value) ->
                    if (value === REMOVED || value == null) values.remove(key) else values[key] = value
                }
                commitCount += 1
                changes.keys.toList()
            }
            changes.clear()
            clearRequested = false
            val observers = synchronized(listeners) { listeners.toList() }
            changedKeys.forEach { key ->
                observers.forEach { it.onSharedPreferenceChanged(this@InMemorySharedPreferences, key) }
            }
            return true
        }

        override fun apply() {
            commit()
        }

        private fun put(key: String, value: Any?): SharedPreferences.Editor = apply {
            changes[key] = value
        }
    }

    private companion object {
        val REMOVED = Any()
    }
}

/**
 * 按名称为 mock Context 提供独立的 [InMemorySharedPreferences]
 */
class InMemorySharedPreferencesRegistry {
    private val stores = LinkedHashMap<String, InMemorySharedPreferences>()

    operator fun get(name: String): InMemorySharedPreferences = synchronized(stores) {
        stores.getOrPut(name) { InMemorySharedPreferences() }
    }

    fun names(): Set<String> = synchronized(stores) { stores.keys.toSet() }

    fun install(context: Context): Context {
        doAnswer { invocation -> get(invocation.getArgument(0)) }
            .`when`(context)
            .getSharedPreferences(anyString(), anyInt())
        return context
    }
}
