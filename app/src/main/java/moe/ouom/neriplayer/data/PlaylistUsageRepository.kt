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
import moe.ouom.neriplayer.util.LanguageManager
import java.io.File

data class UsageEntry(
    val id: Long,
    val name: String,
    val picUrl: String?,
    val trackCount: Int,
    val source: String, // "netease" | "neteaseAlbum" | "bili" | "local" | "youtubeMusic"
    val lastOpened: Long,
    val openCount: Int,
    val fid: Long? = null,
    val mid: Long? = null,
    val browseId: String? = null,
    val playlistId: String? = null,
)

class PlaylistUsageRepository(private val app: Context) {
    companion object {
        const val SOURCE_LOCAL = "local"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val file: File by lazy { File(app.filesDir, "playlist_usage.json") }
    private val usageEntryComparator = Comparator<UsageEntry> { left, right ->
        when {
            left.lastOpened != right.lastOpened -> right.lastOpened.compareTo(left.lastOpened)
            left.openCount != right.openCount -> right.openCount.compareTo(left.openCount)
            else -> left.id.compareTo(right.id)
        }
    }
    private val _flow = MutableStateFlow(load())
    val frequentPlaylistsFlow: StateFlow<List<UsageEntry>> = _flow

    private fun load(): List<UsageEntry> {
        val list: List<UsageEntry> = try {
            if (!file.exists()) {
                emptyList()
            } else {
                gson.fromJson<List<UsageEntry>>(
                    file.readText(),
                    object : TypeToken<List<UsageEntry>>() {}.type
                ) ?: emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }

        return normalizeEntries(list)
    }

    private fun saveAsync(list: List<UsageEntry>) {
        scope.launch { runCatching { file.writeText(gson.toJson(list)) } }
    }

    private fun normalizeEntries(list: List<UsageEntry>): List<UsageEntry> {
        return list
            .sortedWith(usageEntryComparator)
            .take(100)
    }

    fun recordOpen(
        id: Long,
        name: String,
        picUrl: String?,
        trackCount: Int,
        fid: Long = 0,
        mid: Long = 0,
        source: String,
        browseId: String? = null,
        playlistId: String? = null,
        now: Long = System.currentTimeMillis()
    ) {
        val data = _flow.value.toMutableList()
        val idx = data.indexOfFirst { it.id == id && it.source == source }
        if (idx >= 0) {
            val old = data[idx]
            old.copy(
                name = name,
                picUrl = picUrl,
                trackCount = trackCount,
                fid = fid,
                mid = mid,
                browseId = browseId,
                playlistId = playlistId,
                lastOpened = now,
                openCount = old.openCount + 1
            ).also { data[idx] = it }
        } else {
            data.add(
                UsageEntry(
                    id = id,
                    name = name,
                    picUrl = picUrl,
                    trackCount = trackCount,
                    source = source,
                    lastOpened = now,
                    openCount = 1,
                    fid = fid,
                    mid = mid,
                    browseId = browseId,
                    playlistId = playlistId
                )
            )
        }
        val out = normalizeEntries(data)
        _flow.value = out
        saveAsync(out)
    }

    /** 仅刷新歌单信息，不改动最近打开时间与排序 */
    fun updateInfo(
        id: Long,
        name: String,
        picUrl: String?,
        trackCount: Int,
        fid: Long = 0,
        mid: Long = 0,
        source: String,
        browseId: String? = null,
        playlistId: String? = null
    ) {
        val data = _flow.value.toMutableList()
        val idx = data.indexOfFirst { it.id == id && it.source == source }
        if (idx >= 0) {
            val old = data[idx]
            data[idx] = old.copy(
                name = name,
                picUrl = picUrl,
                trackCount = trackCount,
                fid = fid,
                mid = mid,
                browseId = browseId ?: old.browseId,
                playlistId = playlistId ?: old.playlistId
            )
            _flow.value = data
            saveAsync(data)
        }
    }

    /**
     * 同步本地歌单卡片信息
     * 已删除的歌单会被移除，名称/封面/歌曲数变化会刷新展示
     */
    fun syncLocalEntries(playlists: List<LocalPlaylist>) {
        val current = _flow.value
        if (current.none { it.source == SOURCE_LOCAL }) return

        val localizedContext = LanguageManager.applyLanguage(app)
        val playlistsById = playlists.associateBy(LocalPlaylist::id)
        var changed = false
        val updated = current.mapNotNull { entry ->
            if (entry.source != SOURCE_LOCAL) return@mapNotNull entry

            val playlist = playlistsById[entry.id] ?: run {
                changed = true
                return@mapNotNull null
            }

            val refreshedName = SystemLocalPlaylists.resolve(
                playlistId = playlist.id,
                playlistName = playlist.name,
                context = localizedContext
            )?.currentName ?: playlist.name
            val refreshedPicUrl = playlist.displayCoverUrl()
            val refreshedTrackCount = playlist.songs.size
            if (
                entry.name == refreshedName &&
                entry.picUrl == refreshedPicUrl &&
                entry.trackCount == refreshedTrackCount
            ) {
                entry
            } else {
                changed = true
                entry.copy(
                    name = refreshedName,
                    picUrl = refreshedPicUrl,
                    trackCount = refreshedTrackCount
                )
            }
        }

        if (!changed) return

        val out = normalizeEntries(updated)
        _flow.value = out
        saveAsync(out)
    }

    /** 从继续播放列表中移除指定项 */
    fun removeEntry(id: Long, source: String) {
        val data = _flow.value.toMutableList()
        data.removeAll { it.id == id && it.source == source }
        _flow.value = data
        saveAsync(data)
    }
}
