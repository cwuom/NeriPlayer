package moe.ouom.neriplayer.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class PlayedEntry(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
    val playedAt: Long // epoch millis
)

class PlayHistoryRepository(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val file: File by lazy { File(app.filesDir, "play_history.json") }
    private val _history = MutableStateFlow<List<PlayedEntry>>(loadFromDisk())
    val historyFlow: StateFlow<List<PlayedEntry>> = _history

    private fun loadFromDisk(): List<PlayedEntry> {
        return try {
            if (!file.exists()) return emptyList()
            val raw = file.readText()
            val type = object : TypeToken<List<PlayedEntry>>() {}.type
            gson.fromJson<List<PlayedEntry>>(raw, type).orEmpty()
                .sortedByDescending { it.playedAt }
                .distinctBy { it.id to it.album }
                .take(1000)
        } catch (_: Throwable) { emptyList() }
    }


    private fun persistAsync(list: List<PlayedEntry>) {
        scope.launch {
            runCatching {
                file.writeText(gson.toJson(list))
            }
        }
    }

    private val writing = AtomicBoolean(false)

    fun record(song: SongItem, now: Long = System.currentTimeMillis()) {
        if (writing.get()) return
        writing.set(true)
        try {
            val current = _history.value

            val idx = current.indexOfFirst { it.id == song.id && it.album == song.album }

            val toHead = if (idx >= 0) {
                current[idx].copy(
                    name = song.name,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                    coverUrl = song.coverUrl,
                    playedAt = now
                )
            } else {
                PlayedEntry(
                    id = song.id,
                    name = song.name,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                    coverUrl = song.coverUrl,
                    playedAt = now
                )
            }

            val withoutOld = if (idx >= 0) {
                buildList {
                    addAll(current.subList(0, idx))
                    addAll(current.subList(idx + 1, current.size))
                }
            } else current

            val updated = listOf(toHead) + withoutOld

            val clipped = updated
                .sortedByDescending { it.playedAt }
                .distinctBy { it.id to it.album }
                .take(1000)

            _history.value = clipped
            persistAsync(clipped)
        } finally {
            writing.set(false)
        }
    }

    fun clear() {
        _history.value = emptyList()
        persistAsync(emptyList())
    }
}
