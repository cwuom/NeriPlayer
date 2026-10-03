package moe.ouom.neriplayer.data.sync.store.testing

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap
import org.mockito.Mockito.mock

internal class MemorySyncPreferences(initial: Map<String, Any> = emptyMap()) {
    val values = ConcurrentHashMap(initial)
    val durableValues = ConcurrentHashMap(initial)
    val commits = mutableListOf<Set<String>>()
    var failNextCommit = false
    var failCommitNumber: Int? = null
    var delayMissingDeviceRead = false

    fun restart(): MemorySyncPreferences = synchronized(values) {
        MemorySyncPreferences(durableValues.toMap())
    }

    val preferences: SharedPreferences = mock(SharedPreferences::class.java) { call ->
        val key = call.arguments.firstOrNull() as? String
        when (call.method.name) {
            "getString", "getLong", "getBoolean" -> {
                val value = values[key]
                if (delayMissingDeviceRead && key == "device_id" && value == null) Thread.sleep(10)
                value ?: call.arguments[1]
            }
            "contains" -> values.containsKey(key)
            "getAll" -> values.toMap()
            "edit" -> editor()
            else -> null
        }
    }

    private fun editor(): SharedPreferences.Editor {
        val changes = linkedMapOf<String, Any?>()
        var clear = false
        return mock(SharedPreferences.Editor::class.java) { call ->
            when (call.method.name) {
                "putString", "putLong", "putBoolean" -> {
                    changes[call.arguments[0] as String] = call.arguments[1]
                    call.mock
                }
                "remove" -> { changes[call.arguments[0] as String] = null; call.mock }
                "clear" -> { clear = true; call.mock }
                "commit", "apply" -> synchronized(values) {
                    commits += changes.keys.toSet()
                    if (clear) values.clear()
                    changes.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                    changes.clear()
                    clear = false
                    val committed = persistMemory()
                    if (call.method.name == "commit") committed else null
                }
                else -> call.mock
            }
        }
    }

    private fun persistMemory(): Boolean {
        if (failNextCommit || commits.size == failCommitNumber) {
            failNextCommit = false
            return false
        }
        durableValues.clear()
        durableValues.putAll(values)
        return true
    }
}
