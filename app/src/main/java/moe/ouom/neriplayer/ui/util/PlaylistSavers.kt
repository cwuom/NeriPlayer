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
import moe.ouom.neriplayer.ui.viewmodel.tab.BiliPlaylist
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseAlbum
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteasePlaylist

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

val neteasePlaylistSaver: Saver<NeteasePlaylist?, Any> = mapSaver(
    save = { playlist ->
        playlist?.toSaveMap() ?: emptyMap()
    },
    restore = { saved ->
        if (saved.isEmpty()) {
            null
        } else {
            restoreNeteasePlaylist(saved)
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

fun restoreNeteaseAlbum(map: Map<*, *>?): NeteaseAlbum? {
    if (map == null || map.isEmpty()) return null
    val id = (map[KEY_ID] as? Number)?.toLong() ?: return null
    val name = map[KEY_NAME] as? String ?: return null
    val picUrl = map[KEY_PIC_URL] as? String ?: ""
    val size = (map[KEY_TRACK_COUNT] as? Number)?.toInt() ?: 0
    return NeteaseAlbum(
        id = id,
        name = name,
        picUrl = picUrl,
        size = size
    )
}

fun restoreNeteasePlaylist(map: Map<*, *>?): NeteasePlaylist? {
    if (map == null || map.isEmpty()) return null
    val id = (map[KEY_ID] as? Number)?.toLong() ?: return null
    val name = map[KEY_NAME] as? String ?: return null
    val picUrl = map[KEY_PIC_URL] as? String ?: ""
    val playCount = (map[KEY_PLAY_COUNT] as? Number)?.toLong() ?: 0L
    val trackCount = (map[KEY_TRACK_COUNT] as? Number)?.toInt() ?: 0
    return NeteasePlaylist(
        id = id,
        name = name,
        picUrl = picUrl,
        playCount = playCount,
        trackCount = trackCount
    )
}

fun restoreBiliPlaylist(map: Map<*, *>?): BiliPlaylist? {
    if (map == null || map.isEmpty()) return null
    val mediaId = (map[KEY_MEDIA_ID] as? Number)?.toLong() ?: return null
    val fid = (map[KEY_FID] as? Number)?.toLong() ?: 0L
    val mid = (map[KEY_MID] as? Number)?.toLong() ?: 0L
    val title = map[KEY_TITLE] as? String ?: return null
    val count = (map[KEY_COUNT] as? Number)?.toInt() ?: 0
    val coverUrl = map[KEY_COVER_URL] as? String ?: ""
    return BiliPlaylist(
        mediaId = mediaId,
        fid = fid,
        mid = mid,
        title = title,
        count = count,
        coverUrl = coverUrl
    )
}

fun NeteaseAlbum.toSaveMap(): HashMap<String, Any?> = hashMapOf(
    KEY_ID to id,
    KEY_NAME to name,
    KEY_PIC_URL to picUrl,
    KEY_TRACK_COUNT to size
)

fun NeteasePlaylist.toSaveMap(): HashMap<String, Any?> = hashMapOf(
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
    KEY_COVER_URL to coverUrl
)