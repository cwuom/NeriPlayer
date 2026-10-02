package moe.ouom.neriplayer.data.sync.store.state.lyrics

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal object SyncLyricOverrideEvents {
    private val directories = mutableMapOf<String, WeakReference<MutableStateFlow<Long>>>()
    private val preferences = WeakHashMap<SharedPreferences, MutableStateFlow<Long>>()
    private val confirmedMarkers = WeakHashMap<MutableStateFlow<Long>, String?>()

    @Synchronized
    fun forStorage(prefs: SharedPreferences, directory: File?, marker: String?): MutableStateFlow<Long> {
        if (directory == null) return preferences.getOrPut(prefs) { createVersion(marker) }
        val key = directory.canonicalPath
        directories[key]?.get()?.let { return it }
        directories.entries.removeAll { it.value.get() == null }
        return createVersion(marker).also { directories[key] = WeakReference(it) }
    }

    @Synchronized
    fun committed(version: MutableStateFlow<Long>, marker: String?) {
        if (confirmedMarkers[version] == marker) return
        confirmedMarkers[version] = marker
        version.value += 1L
    }

    private fun createVersion(marker: String?): MutableStateFlow<Long> {
        return MutableStateFlow(0L).also { confirmedMarkers[it] = marker }
    }
}

internal class SyncLyricOverrideNotifications(prefs: SharedPreferences, directory: File?, marker: String?) {
    private val version = SyncLyricOverrideEvents.forStorage(prefs, directory, marker)
    val changes: StateFlow<Long> = version.asStateFlow()

    fun committed(marker: String?) {
        // 成功确认同一个 generation 只通知一次，失败后的等值重试也能使队列失效
        SyncLyricOverrideEvents.committed(version, marker)
    }
}
