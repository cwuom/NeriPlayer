@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.util.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.GeneralSecurityException
import java.security.UnrecoverableKeyException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import javax.crypto.BadPaddingException
import moe.ouom.neriplayer.core.logging.NPLogger

/**
 * keeps encrypted preference corruption isolated to the affected credential store
 */
internal class RecoveringEncryptedSharedPreferences internal constructor(
    private val storageName: String,
    private val logTag: String,
    private val opener: () -> SharedPreferences,
    private val resetter: () -> Unit
) : SharedPreferences {
    constructor(
        context: Context,
        storageName: String,
        logTag: String,
        masterKeyAlias: String? = null
    ) : this(
        storageName = storageName,
        logTag = logTag,
        opener = {
            createEncryptedPreferences(
                context = context.applicationContext,
                storageName = storageName,
                masterKeyAlias = masterKeyAlias
            )
        },
        resetter = {
            context.applicationContext.deleteSharedPreferences(storageName)
        }
    )

    private data class ActiveStore(
        val preferences: SharedPreferences,
        val durable: Boolean
    )

    private val recoveryLock = Any()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    @Volatile
    private var activeStore: ActiveStore = openInitialStore()

    val isDurable: Boolean
        get() = activeStore.durable

    override fun getAll(): MutableMap<String, *> {
        return execute { preferences -> LinkedHashMap(preferences.all) }
    }

    override fun getString(key: String, defValue: String?): String? {
        return execute { preferences -> preferences.getString(key, defValue) }
    }

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?
    ): MutableSet<String>? {
        return execute { preferences -> preferences.getStringSet(key, defValues) }
    }

    override fun getInt(key: String, defValue: Int): Int {
        return execute { preferences -> preferences.getInt(key, defValue) }
    }

    override fun getLong(key: String, defValue: Long): Long {
        return execute { preferences -> preferences.getLong(key, defValue) }
    }

    override fun getFloat(key: String, defValue: Float): Float {
        return execute { preferences -> preferences.getFloat(key, defValue) }
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        return execute { preferences -> preferences.getBoolean(key, defValue) }
    }

    override fun contains(key: String): Boolean {
        return execute { preferences -> preferences.contains(key) }
    }

    override fun edit(): SharedPreferences.Editor = RecoveringEditor(this)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners += listener
        execute { preferences ->
            preferences.registerOnSharedPreferenceChangeListener(listener)
        }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners -= listener
        execute { preferences ->
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    private fun openInitialStore(): ActiveStore {
        return runCatching(opener)
            .fold(
                onSuccess = { preferences -> ActiveStore(preferences, durable = true) },
                onFailure = { error -> recover(error, "open") }
            )
    }

    private fun recover(error: Throwable, phase: String): ActiveStore {
        if (!isIrrecoverableCryptoFailure(error)) {
            NPLogger.w(
                logTag,
                "Encrypted preferences are temporarily unavailable during $phase; " +
                    "keeping $storageName and using memory-only credentials.",
                error
            )
            return ActiveStore(MemoryOnlySharedPreferences(), durable = false)
        }
        return resetAndOpen(error, phase)
    }

    private fun resetAndOpen(error: Throwable, phase: String): ActiveStore {
        NPLogger.w(
            logTag,
            "Encrypted preferences are unavailable during $phase; resetting only $storageName.",
            error
        )
        runCatching(resetter).onFailure { resetError ->
            NPLogger.e(
                logTag,
                "Failed to reset encrypted preferences: $storageName.",
                resetError
            )
        }
        return runCatching(opener)
            .fold(
                onSuccess = { preferences -> ActiveStore(preferences, durable = true) },
                onFailure = { reopenError ->
                    NPLogger.e(
                        logTag,
                        "Encrypted preferences remain unavailable: $storageName. " +
                            "Using memory-only empty credentials.",
                        reopenError
                    )
                    ActiveStore(MemoryOnlySharedPreferences(), durable = false)
                }
            )
    }

    private fun recoverAfterFailure(
        expectedStore: ActiveStore,
        error: Throwable
    ): ActiveStore {
        synchronized(recoveryLock) {
            if (activeStore !== expectedStore) {
                return activeStore
            }
            return install(recover(error, "access"))
        }
    }

    private fun isIrrecoverableCryptoFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is BadPaddingException || current is UnrecoverableKeyException) {
                return true
            }
            if (isReportedDecryptionFailure(current)) return true
            current = current.cause
        }
        return false
    }

    private fun isReportedDecryptionFailure(error: Throwable): Boolean {
        if (
            error !is GeneralSecurityException &&
            error !is SecurityException &&
            error.javaClass.name != "android.security.KeyStoreException"
        ) {
            return false
        }
        val message = error.message.orEmpty().lowercase(Locale.ROOT)
        return message.contains("decrypt") ||
            message.contains("authentication tag") ||
            message.contains("mac verification") ||
            message.contains("verification failed") ||
            message.contains("keyset") ||
            message.contains("permanently invalidated")
    }

    private fun forceMemoryOnly(
        expectedStore: ActiveStore,
        error: Throwable
    ): ActiveStore {
        synchronized(recoveryLock) {
            if (activeStore !== expectedStore && !activeStore.durable) {
                return activeStore
            }
            NPLogger.e(
                logTag,
                "Encrypted preferences failed after recovery: $storageName. " +
                    "Using memory-only empty credentials.",
                error
            )
            return install(ActiveStore(MemoryOnlySharedPreferences(), durable = false))
        }
    }

    private fun install(store: ActiveStore): ActiveStore {
        activeStore = store
        listeners.forEach { listener ->
            runCatching {
                store.preferences.registerOnSharedPreferenceChangeListener(listener)
            }.onFailure { error ->
                NPLogger.w(
                    logTag,
                    "Failed to restore encrypted preference listener: $storageName.",
                    error
                )
            }
        }
        return store
    }

    private fun <T> execute(action: (SharedPreferences) -> T): T {
        val initialStore = activeStore
        return try {
            action(initialStore.preferences)
        } catch (initialError: Exception) {
            val recoveredStore = recoverAfterFailure(initialStore, initialError)
            try {
                action(recoveredStore.preferences)
            } catch (recoveryError: Exception) {
                val memoryStore = forceMemoryOnly(recoveredStore, recoveryError)
                action(memoryStore.preferences)
            }
        }
    }

    private fun write(actions: List<EditorAction>, commit: Boolean): Boolean {
        return execute { preferences ->
            val editor = preferences.edit()
            actions.forEach { action -> action.apply(editor) }
            if (commit) {
                editor.commit()
            } else {
                editor.apply()
                true
            }
        }
    }

    private sealed interface EditorAction {
        fun apply(editor: SharedPreferences.Editor)

        data class PutString(
            val key: String,
            val value: String?
        ) : EditorAction {
            override fun apply(editor: SharedPreferences.Editor) {
                editor.putString(key, value)
            }
        }

        data class PutStringSet(
            val key: String,
            val value: Set<String>?
        ) : EditorAction {
            override fun apply(editor: SharedPreferences.Editor) {
                editor.putStringSet(key, value?.toMutableSet())
            }
        }

        data class PutInt(
            val key: String,
            val value: Int
        ) : EditorAction {
            override fun apply(editor: SharedPreferences.Editor) {
                editor.putInt(key, value)
            }
        }

        data class PutLong(
            val key: String,
            val value: Long
        ) : EditorAction {
            override fun apply(editor: SharedPreferences.Editor) {
                editor.putLong(key, value)
            }
        }

        data class PutFloat(
            val key: String,
            val value: Float
        ) : EditorAction {
            override fun apply(editor: SharedPreferences.Editor) {
                editor.putFloat(key, value)
            }
        }

        data class PutBoolean(
            val key: String,
            val value: Boolean
        ) : EditorAction {
            override fun apply(editor: SharedPreferences.Editor) {
                editor.putBoolean(key, value)
            }
        }

        data class Remove(val key: String) : EditorAction {
            override fun apply(editor: SharedPreferences.Editor) {
                editor.remove(key)
            }
        }

        data object Clear : EditorAction {
            override fun apply(editor: SharedPreferences.Editor) {
                editor.clear()
            }
        }
    }

    private class RecoveringEditor(
        private val preferences: RecoveringEncryptedSharedPreferences
    ) : SharedPreferences.Editor {
        private val actions = mutableListOf<EditorAction>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            actions += EditorAction.PutString(key, value)
            return this
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?
        ): SharedPreferences.Editor {
            actions += EditorAction.PutStringSet(key, values?.toSet())
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            actions += EditorAction.PutInt(key, value)
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            actions += EditorAction.PutLong(key, value)
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            actions += EditorAction.PutFloat(key, value)
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            actions += EditorAction.PutBoolean(key, value)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            actions += EditorAction.Remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            actions += EditorAction.Clear
            return this
        }

        override fun commit(): Boolean {
            val pending = actions.toList()
            actions.clear()
            return preferences.write(pending, commit = true)
        }

        override fun apply() {
            val pending = actions.toList()
            actions.clear()
            preferences.write(pending, commit = false)
        }
    }
}

internal open class MemoryOnlySharedPreferences : SharedPreferences {
    private val lock = Any()
    private val values = linkedMapOf<String, Any>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = synchronized(lock) {
        LinkedHashMap(values)
    }

    override fun getString(key: String, defValue: String?): String? = synchronized(lock) {
        values[key] as? String ?: defValue
    }

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?
    ): MutableSet<String>? = synchronized(lock) {
        @Suppress("UNCHECKED_CAST")
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues?.toMutableSet()
    }

    override fun getInt(key: String, defValue: Int): Int = synchronized(lock) {
        values[key] as? Int ?: defValue
    }

    override fun getLong(key: String, defValue: Long): Long = synchronized(lock) {
        values[key] as? Long ?: defValue
    }

    override fun getFloat(key: String, defValue: Float): Float = synchronized(lock) {
        values[key] as? Float ?: defValue
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(lock) {
        values[key] as? Boolean ?: defValue
    }

    override fun contains(key: String): Boolean = synchronized(lock) { key in values }

    override fun edit(): SharedPreferences.Editor = MemoryEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners -= listener
    }

    private inner class MemoryEditor : SharedPreferences.Editor {
        private val updates = linkedMapOf<String, Any?>()
        private val removals = linkedSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            updates[key] = value
            removals -= key
            return this
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?
        ): SharedPreferences.Editor {
            updates[key] = values?.toSet()
            removals -= key
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            updates[key] = value
            removals -= key
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            updates[key] = value
            removals -= key
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            updates[key] = value
            removals -= key
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            updates[key] = value
            removals -= key
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            updates.remove(key)
            removals += key
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            val changedKeys = linkedSetOf<String>()
            synchronized(lock) {
                if (clearRequested) {
                    changedKeys += values.keys
                    values.clear()
                }
                removals.forEach { key ->
                    if (values.remove(key) != null) {
                        changedKeys += key
                    }
                }
                updates.forEach { (key, value) ->
                    if (value == null) {
                        if (values.remove(key) != null) {
                            changedKeys += key
                        }
                    } else if (values[key] != value) {
                        values[key] = value
                        changedKeys += key
                    }
                }
            }
            changedKeys.forEach { key ->
                listeners.forEach { listener -> listener.onSharedPreferenceChanged(this@MemoryOnlySharedPreferences, key) }
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}

private fun createEncryptedPreferences(
    context: Context,
    storageName: String,
    masterKeyAlias: String?
): SharedPreferences {
    val masterKeyBuilder = if (masterKeyAlias.isNullOrBlank()) {
        MasterKey.Builder(context)
    } else {
        MasterKey.Builder(context, masterKeyAlias)
    }
    val masterKey = masterKeyBuilder
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        storageName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
