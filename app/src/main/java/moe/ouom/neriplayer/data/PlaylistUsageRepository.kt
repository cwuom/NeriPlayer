package moe.ouom.neriplayer.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class UsageEntry(
    val id: Long,
    val name: String,
    val picUrl: String?,
    val trackCount: Int,
    val source: String,       // "netease" | "bilibili" | "local"
    val lastOpened: Long,
    val openCount: Int,
    val fid: Long? = null,
    val mid: Long? = null,
    )

class PlaylistUsageRepository(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val file: File by lazy { File(app.filesDir, "playlist_usage.json") }
    private val _flow = MutableStateFlow(load())
    val frequentPlaylistsFlow: StateFlow<List<UsageEntry>> = _flow

    private fun load(): List<UsageEntry> {
        val list: List<UsageEntry> = try {
            if (!file.exists()) emptyList() else gson.fromJson(
                file.readText(),
                object : TypeToken<List<UsageEntry>>() {}.type
            )
        } catch (_: Throwable) {
            emptyList()
        }

        val comparator = compareByDescending<UsageEntry> { it.lastOpened }
            .thenByDescending { it.openCount }

        return list.sortedWith(comparator).take(100)
    }
    private fun saveAsync(list: List<UsageEntry>) {
        scope.launch { runCatching { file.writeText(gson.toJson(list)) } }
    }

    fun recordOpen(
        id: Long, name: String, picUrl: String?, trackCount: Int, fid: Long = 0, mid: Long = 0, source: String,
        now: Long = System.currentTimeMillis()
    ) {
        val data = _flow.value.toMutableList()
        val idx = data.indexOfFirst { it.id == id && it.source == source }
        val updated = if (idx >= 0) {
            val old = data[idx]
            old.copy(
                name = name,
                picUrl = picUrl,
                trackCount = trackCount,
                fid = fid,
                mid = mid,
                lastOpened = now,
                openCount = old.openCount + 1
            ).also { data[idx] = it }
        } else {
            data.add(
                UsageEntry(
                    id, name, picUrl, trackCount, source, lastOpened = now, openCount = 1, fid, mid
                )
            )
        }
        val out = data.sortedWith(compareByDescending<UsageEntry> { it.lastOpened }.thenByDescending { it.openCount })
            .take(100)
        _flow.value = out
        saveAsync(out)
    }
}