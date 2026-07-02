package moe.ouom.neriplayer.data.playlist.usage

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.playlist.usage/PlaylistUsageRepository
 * Updated: 2026/3/23
 */


import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.displayCoverUrl
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
    val subtype: String? = null,
)

internal fun playlistUsageKey(source: String, id: Long, subtype: String?): String = buildString {
    append(source)
    append(':')
    append(id)
    subtype?.trim()?.takeIf { it.isNotEmpty() }?.let {
        append(':')
        append(it)
    }
}

internal fun UsageEntry.usageKey(): String = playlistUsageKey(source, id, subtype)

private val usageEntryComparator = Comparator<UsageEntry> { left, right ->
    when {
        left.lastOpened != right.lastOpened -> right.lastOpened.compareTo(left.lastOpened)
        left.openCount != right.openCount -> right.openCount.compareTo(left.openCount)
        else -> left.id.compareTo(right.id)
    }
}

internal fun normalizeUsageEntries(list: List<UsageEntry>): List<UsageEntry> {
    return list
        .groupBy(UsageEntry::usageKey)
        .map { (_, duplicates) -> mergeDuplicateUsageEntries(duplicates) }
        .sortedWith(usageEntryComparator)
        .take(100)
}

private fun mergeDuplicateUsageEntries(entries: List<UsageEntry>): UsageEntry {
    val latest = entries.sortedWith(usageEntryComparator).first()
    val mergedOpenCount = entries.sumOf(UsageEntry::openCount)
        .coerceAtLeast(latest.openCount)
    return latest.takeIf { it.openCount == mergedOpenCount }
        ?: latest.copy(openCount = mergedOpenCount)
}

class PlaylistUsageRepository(private val app: Context) {
    companion object {
        const val SOURCE_LOCAL = "local"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val file: File by lazy { File(app.filesDir, "playlist_usage.json") }
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

        return normalizeUsageEntries(list)
    }

    private fun saveAsync(list: List<UsageEntry>) {
        scope.launch { runCatching { file.writeText(gson.toJson(list)) } }
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
        subtype: String? = null,
        now: Long = System.currentTimeMillis()
    ) {
        val data = _flow.value.toMutableList()
        val targetKey = playlistUsageKey(source, id, subtype)
        val idx = data.indexOfFirst { it.usageKey() == targetKey }
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
                subtype = subtype,
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
                    playlistId = playlistId,
                    subtype = subtype
                )
            )
        }
        val out = normalizeUsageEntries(data)
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
        playlistId: String? = null,
        subtype: String? = null
    ) {
        val data = _flow.value.toMutableList()
        val targetKey = playlistUsageKey(source, id, subtype)
        val idx = data.indexOfFirst { it.usageKey() == targetKey }
        if (idx >= 0) {
            val old = data[idx]
            data[idx] = old.copy(
                name = name,
                picUrl = picUrl,
                trackCount = trackCount,
                fid = fid,
                mid = mid,
                browseId = browseId ?: old.browseId,
                playlistId = playlistId ?: old.playlistId,
                subtype = subtype ?: old.subtype
            )
            val out = normalizeUsageEntries(data)
            _flow.value = out
            saveAsync(out)
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

        val out = normalizeUsageEntries(updated)
        _flow.value = out
        saveAsync(out)
    }

    /** 从继续播放列表中移除指定项 */
    fun removeEntry(id: Long, source: String, subtype: String? = null) {
        val data = _flow.value.toMutableList()
        val targetKey = playlistUsageKey(source, id, subtype)
        data.removeAll { it.usageKey() == targetKey }
        val out = normalizeUsageEntries(data)
        _flow.value = out
        saveAsync(out)
    }
}
