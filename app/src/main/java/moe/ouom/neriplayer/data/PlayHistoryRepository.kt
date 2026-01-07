package moe.ouom.neriplayer.data

import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.github.SecureTokenStorage
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class PlayedEntry(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: Long = 0L,
    val durationMs: Long,
    val coverUrl: String?,
    val playedAt: Long // epoch millis
)

class PlayHistoryRepository private constructor(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val file: File by lazy { File(app.filesDir, "play_history.json") }
    private val _history = MutableStateFlow<List<PlayedEntry>>(loadFromDisk())
    val historyFlow: StateFlow<List<PlayedEntry>> = _history
    private val storage by lazy { SecureTokenStorage(app) }
    private var lastBatchSyncTime = 0L

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

    /** 触发GitHub同步（根据用户设置的更新模式） */
    private fun triggerSyncIfNeeded() {
        try {
            val mode = storage.getPlayHistoryUpdateMode()
            val now = System.currentTimeMillis()

            when (mode) {
                SecureTokenStorage.PlayHistoryUpdateMode.IMMEDIATE -> {
                    // 立即触发同步
                    triggerAutoSync()
                }
                SecureTokenStorage.PlayHistoryUpdateMode.BATCHED -> {
                    // 批量模式：每10分钟触发一次
                    if (now - lastBatchSyncTime >= 10 * 60 * 1000) {
                        lastBatchSyncTime = now
                        triggerAutoSync()
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略同步触发错误
        }
    }

    /** 触发自动同步 */
    private fun triggerAutoSync() {
        try {
            // 检查是否启用自动同步
            if (!storage.isAutoSyncEnabled()) {
                NPLogger.d("PlayHistoryRepo", "Auto sync is disabled, skipping sync")
                return
            }

            GitHubSyncWorker.scheduleDelayedSync(app, triggerByUserAction = false)
            NPLogger.d("PlayHistoryRepo", "Sync scheduled after play history change")
        } catch (e: Exception) {
            NPLogger.e("PlayHistoryRepo", "Failed to trigger sync", e)
        }
    }

    private val writing = AtomicBoolean(false)

    fun record(song: SongItem, now: Long = System.currentTimeMillis()) {
        NPLogger.d("PlayHistoryRepo", "record() called: songId=${song.id}, name=${song.name}, writing=${writing.get()}")
        if (writing.get()) {
            NPLogger.w("PlayHistoryRepo", "record() blocked by writing lock, skipping")
            return
        }
        writing.set(true)
        try {
            val current = _history.value
            NPLogger.d("PlayHistoryRepo", "Current history size: ${current.size}")

            val idx = current.indexOfFirst { it.id == song.id && it.album == song.album }

            val toHead = if (idx >= 0) {
                NPLogger.d("PlayHistoryRepo", "Updating existing entry at index $idx")
                current[idx].copy(
                    name = song.name,
                    artist = song.artist,
                    album = song.album,
                    albumId = song.albumId,
                    durationMs = song.durationMs,
                    coverUrl = song.coverUrl,
                    playedAt = now
                )
            } else {
                NPLogger.d("PlayHistoryRepo", "Creating new entry")
                PlayedEntry(
                    id = song.id,
                    name = song.name,
                    artist = song.artist,
                    album = song.album,
                    albumId = song.albumId,
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

            NPLogger.d("PlayHistoryRepo", "Updated history size: ${clipped.size}, latest: ${clipped.firstOrNull()?.name}")
            _history.value = clipped
            persistAsync(clipped)

            // 根据用户设置触发同步
            triggerSyncIfNeeded()
        } finally {
            writing.set(false)
        }
    }

    fun clear() {
        _history.value = emptyList()
        persistAsync(emptyList())
    }

    /** 批量更新播放历史（由同步管理器调用，不触发新的同步） */
    fun updateHistory(entries: List<PlayedEntry>) {
        NPLogger.d("PlayHistoryRepo", "updateHistory() called: entries=${entries.size}, writing=${writing.get()}")
        if (writing.get()) {
            NPLogger.w("PlayHistoryRepo", "updateHistory() blocked by writing lock, skipping")
            return
        }
        writing.set(true)
        try {
            val clipped = entries
                .sortedByDescending { it.playedAt }
                .distinctBy { it.id to it.album }
                .take(1000)
            NPLogger.d("PlayHistoryRepo", "updateHistory() setting history to ${clipped.size} entries, latest: ${clipped.firstOrNull()?.name}")
            _history.value = clipped
            persistAsync(clipped)
        } finally {
            writing.set(false)
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: PlayHistoryRepository? = null

        fun getInstance(context: Context): PlayHistoryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PlayHistoryRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
