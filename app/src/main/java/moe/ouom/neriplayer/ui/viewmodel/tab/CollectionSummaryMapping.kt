package moe.ouom.neriplayer.ui.viewmodel.tab

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
 * File: moe.ouom.neriplayer.ui.viewmodel.tab/CollectionSummaryModels
 * Created: 2026/4/6
 */

import moe.ouom.neriplayer.data.model.playlist.FavoritePlaylist

private const val BILI_FAVORITE_REFERENCE_PREFIX = "bili-playlist/v1/"

internal fun BiliPlaylist.toFavoriteBrowseId(): String {
    return buildString {
        append(BILI_FAVORITE_REFERENCE_PREFIX)
        append(kind.name)
        append('/')
        append(fid)
        append('/')
        append(mid)
    }
}

internal fun FavoritePlaylist.toBiliPlaylist(): BiliPlaylist {
    val reference = parseBiliFavoriteReference(browseId)
    return BiliPlaylist(
        mediaId = id,
        fid = reference?.fid ?: 0L,
        mid = reference?.mid ?: 0L,
        title = name,
        count = trackCount,
        coverUrl = coverUrl.orEmpty(),
        kind = reference?.kind ?: BiliPlaylistKind.CREATED_FAVORITE,
        subtitle = subtitle.orEmpty()
    )
}

private data class BiliFavoriteReference(
    val kind: BiliPlaylistKind,
    val fid: Long,
    val mid: Long
)

private fun parseBiliFavoriteReference(value: String?): BiliFavoriteReference? {
    val segments = biliFavoriteReferenceSegments(value) ?: return null
    val kind = runCatching { BiliPlaylistKind.valueOf(segments[0]) }.getOrNull() ?: return null
    val fid = segments[1].toLongOrNull() ?: return null
    val mid = segments[2].toLongOrNull() ?: return null
    return BiliFavoriteReference(kind = kind, fid = fid, mid = mid)
}

private fun biliFavoriteReferenceSegments(value: String?): List<String>? {
    if (value == null || !value.startsWith(BILI_FAVORITE_REFERENCE_PREFIX)) return null
    return value.removePrefix(BILI_FAVORITE_REFERENCE_PREFIX)
        .split('/')
        .takeIf { it.size == 3 }
}
