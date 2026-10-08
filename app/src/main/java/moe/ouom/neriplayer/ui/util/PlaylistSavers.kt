package moe.ouom.neriplayer.ui.util

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
 * File: moe.ouom.neriplayer.ui.util/PlaylistSavers
 * Created: 2025/9/30
 */

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import moe.ouom.neriplayer.ui.viewmodel.tab.AlbumSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylistKind
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist

private const val KEY_ID = "id"
private const val KEY_NAME = "name"
private const val KEY_PIC_URL = "picUrl"
private const val KEY_PLAY_COUNT = "playCount"
private const val KEY_TRACK_COUNT = "trackCount"

private const val KEY_MEDIA_ID = "mediaId"
private const val KEY_FID = "fid"
private const val KEY_MID = "mid"
private const val KEY_TITLE = "title"
private const val KEY_COUNT = "count"
private const val KEY_COVER_URL = "coverUrl"
private const val KEY_KIND = "kind"
private const val KEY_PLAYLIST_ID = "playlistId"
private const val KEY_BROWSE_ID = "browseId"
private const val KEY_SUBTITLE = "subtitle"
private const val KEY_CREATOR_NAME = "creatorName"

val playlistSummarySaver: Saver<PlaylistSummary?, Any> = mapSaver(
    save = { playlist ->
        playlist?.toSaveMap() ?: emptyMap()
    },
    restore = { saved ->
        if (saved.isEmpty()) {
            null
        } else {
            restorePlaylistSummary(saved)
        }
    }
)

val biliPlaylistSaver: Saver<BiliPlaylist?, Any> = mapSaver(
    save = { playlist ->
        playlist?.toSaveMap() ?: emptyMap()
    },
    restore = { saved ->
        if (saved.isEmpty()) {
            null
        } else {
            restoreBiliPlaylist(saved)
        }
    }
)

fun restoreAlbumSummary(map: Map<*, *>?): AlbumSummary? {
    if (map.isNullOrEmpty()) return null
    val id = map.number(KEY_ID)?.toLong() ?: return null
    val name = map.string(KEY_NAME) ?: return null
    return AlbumSummary(
        id = id,
        name = name,
        picUrl = map.text(KEY_PIC_URL),
        size = map.int(KEY_TRACK_COUNT)
    )
}

fun restorePlaylistSummary(map: Map<*, *>?): PlaylistSummary? {
    if (map.isNullOrEmpty()) return null
    val id = map.number(KEY_ID)?.toLong() ?: return null
    val name = map.string(KEY_NAME) ?: return null
    return PlaylistSummary(
        id = id,
        name = name,
        picUrl = map.text(KEY_PIC_URL),
        playCount = map.long(KEY_PLAY_COUNT),
        trackCount = map.int(KEY_TRACK_COUNT)
    )
}

fun restoreBiliPlaylist(map: Map<*, *>?): BiliPlaylist? {
    if (map.isNullOrEmpty()) return null
    val mediaId = map.number(KEY_MEDIA_ID)?.toLong() ?: return null
    val title = map.string(KEY_TITLE) ?: return null
    return BiliPlaylist(
        mediaId = mediaId,
        fid = map.long(KEY_FID),
        mid = map.long(KEY_MID),
        title = title,
        count = map.int(KEY_COUNT),
        coverUrl = map.text(KEY_COVER_URL),
        kind = map.biliPlaylistKind(),
        subtitle = map.text(KEY_SUBTITLE)
    )
}

fun restoreYouTubeMusicPlaylist(map: Map<*, *>?): YouTubeMusicPlaylist? {
    if (map.isNullOrEmpty()) return null
    val browseId = map.string(KEY_BROWSE_ID) ?: return null
    val playlistId = map.string(KEY_PLAYLIST_ID) ?: return null
    val title = map.string(KEY_TITLE) ?: return null
    return YouTubeMusicPlaylist(
        browseId = browseId,
        playlistId = playlistId,
        title = title,
        subtitle = map.text(KEY_SUBTITLE),
        coverUrl = map.text(KEY_COVER_URL),
        trackCount = map.int(KEY_TRACK_COUNT, default = map.int(KEY_COUNT)),
        creatorName = map.text(KEY_CREATOR_NAME)
    )
}

private fun Map<*, *>.number(key: String): Number? = this[key] as? Number

private fun Map<*, *>.string(key: String): String? = this[key] as? String

private fun Map<*, *>.text(key: String): String = string(key).orEmpty()

private fun Map<*, *>.long(key: String): Long = number(key)?.toLong() ?: 0L

private fun Map<*, *>.int(key: String, default: Int = 0): Int = number(key)?.toInt() ?: default

private fun Map<*, *>.biliPlaylistKind(): BiliPlaylistKind =
    string(KEY_KIND)
        ?.let { name -> BiliPlaylistKind.entries.firstOrNull { it.name == name } }
        ?: BiliPlaylistKind.CREATED_FAVORITE

fun AlbumSummary.toSaveMap(): HashMap<String, Any?> = hashMapOf(
    KEY_ID to id,
    KEY_NAME to name,
    KEY_PIC_URL to picUrl,
    KEY_TRACK_COUNT to size
)

fun PlaylistSummary.toSaveMap(): HashMap<String, Any?> = hashMapOf(
    KEY_ID to id,
    KEY_NAME to name,
    KEY_PIC_URL to picUrl,
    KEY_PLAY_COUNT to playCount,
    KEY_TRACK_COUNT to trackCount
)

fun BiliPlaylist.toSaveMap(): HashMap<String, Any?> = hashMapOf(
    KEY_MEDIA_ID to mediaId,
    KEY_FID to fid,
    KEY_MID to mid,
    KEY_TITLE to title,
    KEY_COUNT to count,
    KEY_COVER_URL to coverUrl,
    KEY_KIND to kind.name,
    KEY_SUBTITLE to subtitle
)

fun YouTubeMusicPlaylist.toSaveMap(): HashMap<String, Any?> = hashMapOf(
    KEY_BROWSE_ID to browseId,
    KEY_PLAYLIST_ID to playlistId,
    KEY_TITLE to title,
    KEY_TRACK_COUNT to trackCount,
    KEY_COVER_URL to coverUrl,
    KEY_SUBTITLE to subtitle,
    KEY_CREATOR_NAME to creatorName
)
