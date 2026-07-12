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
import moe.ouom.neriplayer.data.local.playlist.model.buildLocalArtistSummaries
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.util.LanguageManager
import moe.ouom.neriplayer.util.writeTextAtomically
import java.io.File

data class UsageEntry(
    val id: Long,
    val name: String,
    val picUrl: String?,
    val trackCount: Int,
    val source: String, // "netease" | "neteaseAlbum" | "bili" | "local" | "localArtist" | "youtubeMusic"
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

internal fun UsageEntry.hasPlayableTracks(): Boolean = trackCount > 0

private val usageEntryComparator = Comparator<UsageEntry> { left, right ->
    when {
        left.lastOpened != right.lastOpened -> right.lastOpened.compareTo(left.lastOpened)
        left.openCount != right.openCount -> right.openCount.compareTo(left.openCount)
        else -> left.id.compareTo(right.id)
    }
}

internal fun normalizeUsageEntries(list: List<UsageEntry>): List<UsageEntry> {
    return list
        .filter(UsageEntry::hasPlayableTracks)
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
        const val SOURCE_LOCAL_ARTIST = "localArtist"
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
        scope.launch { runCatching { file.writeTextAtomically(gson.toJson(list)) } }
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
        if (trackCount <= 0) {
            removeEntryIfPresent(id, source, subtype)
            return
        }

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

    /** 刷新歌单信息；详情加载出有效曲目时可补齐打开记录 */
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
        subtype: String? = null,
        now: Long = System.currentTimeMillis()
    ) {
        if (trackCount <= 0) {
            removeEntryIfPresent(id, source, subtype)
            return
        }

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

    /**
     * 同步本地歌单卡片信息
     * 已删除的歌单会被移除，名称/封面/歌曲数变化会刷新展示
     */
    fun syncLocalEntries(playlists: List<LocalPlaylist>) {
        val current = _flow.value
        if (current.none { it.source == SOURCE_LOCAL }) return

        val localizedContext = LanguageManager.applyLanguage(app)
        val localPlaylistLookup = buildLocalPlaylistUsageLookup(playlists, localizedContext)
        var changed = false
        val updated = current.mapNotNull { entry ->
            if (entry.source != SOURCE_LOCAL) return@mapNotNull entry

            val playlist = localPlaylistLookup[entry.id] ?: run {
                changed = true
                return@mapNotNull null
            }

            val refreshedName = SystemLocalPlaylists.resolve(
                playlistId = playlist.id,
                playlistName = playlist.name,
                context = localizedContext
            )?.currentName ?: playlist.name
            val refreshedPicUrl = playlist.displayCoverUrl(
                context = localizedContext,
                resolveLocalMetadataFallback = true
            )
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

    /** 同步本地歌手虚拟歌单卡片信息 */
    fun syncLocalArtistEntries(playlists: List<LocalPlaylist>) {
        val current = _flow.value
        if (current.none { it.source == SOURCE_LOCAL_ARTIST }) return

        val localizedContext = LanguageManager.applyLanguage(app)
        val artistsById = buildLocalArtistSummaries(playlists, localizedContext)
            .associateBy { artist -> artist.id }
        var changed = false
        val updated = current.mapNotNull { entry ->
            if (entry.source != SOURCE_LOCAL_ARTIST) return@mapNotNull entry

            val artist = artistsById[entry.id] ?: run {
                changed = true
                return@mapNotNull null
            }

            val refreshedPicUrl = artist.displayCoverUrl(
                context = localizedContext,
                resolveLocalMetadataFallback = true
            )
            val refreshedTrackCount = artist.songs.size
            if (
                entry.name == artist.name &&
                entry.picUrl == refreshedPicUrl &&
                entry.trackCount == refreshedTrackCount
            ) {
                entry
            } else {
                changed = true
                entry.copy(
                    name = artist.name,
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
        removeEntryIfPresent(id, source, subtype)
    }

    private fun removeEntryIfPresent(id: Long, source: String, subtype: String? = null) {
        val data = _flow.value.toMutableList()
        val targetKey = playlistUsageKey(source, id, subtype)
        val removed = data.removeAll { it.usageKey() == targetKey }
        if (!removed) return

        val out = normalizeUsageEntries(data)
        _flow.value = out
        saveAsync(out)
    }
}

internal fun buildLocalPlaylistUsageLookup(
    playlists: List<LocalPlaylist>,
    context: Context
): Map<Long, LocalPlaylist> {
    val lookup = playlists.associateBy(LocalPlaylist::id).toMutableMap()
    val systemGroups = playlists.groupBy { playlist ->
        SystemLocalPlaylists.resolve(playlist.id, playlist.name, context)?.id
    }

    systemGroups[FavoritesPlaylist.SYSTEM_ID]
        ?.takeIf { it.isNotEmpty() }
        ?.let { favorites ->
            lookup[FavoritesPlaylist.SYSTEM_ID] = FavoritesPlaylist.merge(favorites, context)
        }
    systemGroups[LocalFilesPlaylist.SYSTEM_ID]
        ?.takeIf { it.isNotEmpty() }
        ?.let { localFiles ->
            lookup[LocalFilesPlaylist.SYSTEM_ID] = LocalFilesPlaylist.merge(localFiles, context)
        }

    return lookup
}
