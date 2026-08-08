package moe.ouom.neriplayer.core.api.bili

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
 * File: moe.ouom.neriplayer.core.api.bili/BiliSongResolver
 * Updated: 2026/3/23
 */

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem

private val biliPartPrefixRegex = Regex("^\\d+\\.\\s*")
private val biliPartSeparatorRegex = Regex("\\s[-\\u2013\\u2014]\\s")

data class ResolvedBiliSong(
    val avid: Long,
    val cid: Long,
    val videoInfo: BiliClient.VideoBasicInfo,
    val pageInfo: BiliClient.VideoPage?
)

internal fun buildBiliSongAlbum(
    cid: Long? = null,
    bvid: String? = null
): String {
    val normalizedCid = cid?.takeIf { it > 0L }
    val normalizedBvid = bvid?.trim()?.takeIf { it.isNotEmpty() }
    if (normalizedCid == null && normalizedBvid == null) {
        return PlayerManager.BILI_SOURCE_TAG
    }
    return buildString {
        append(PlayerManager.BILI_SOURCE_TAG)
        append('|')
        normalizedCid?.let(::append)
        normalizedBvid?.let {
            append('|')
            append(it)
        }
    }
}

fun buildBiliPartSong(
    page: BiliClient.VideoPage,
    basicInfo: BiliClient.VideoBasicInfo,
    coverUrl: String
): SongItem {
    val (title, artist) = parseBiliPartMetadata(page.part, basicInfo.ownerName)
    return SongItem(
        id = basicInfo.aid,
        name = title,
        artist = artist,
        album = buildBiliSongAlbum(
            cid = page.cid,
            bvid = basicInfo.bvid
        ),
        albumId = 0L,
        durationMs = page.durationSec * 1000L,
        coverUrl = coverUrl,
        channelId = "bilibili",
        audioId = basicInfo.aid.toString(),
        subAudioId = page.cid.toString()
    )
}

private fun parseBiliPartMetadata(part: String, fallbackArtist: String): Pair<String, String> {
    val rawTitle = part.trim()
    val normalizedTitle = rawTitle.replace(biliPartPrefixRegex, "").trim().ifBlank { rawTitle }
    val separators = biliPartSeparatorRegex.findAll(normalizedTitle).toList()
    val separatorMatch = separators.singleOrNull() ?: return normalizedTitle to fallbackArtist

    val title = normalizedTitle.substring(0, separatorMatch.range.first).trim()
    val artist = normalizedTitle.substring(separatorMatch.range.last + 1).trim()
    return if (title.isBlank() || artist.isBlank()) {
        normalizedTitle to fallbackArtist
    } else {
        title to artist
    }
}

suspend fun resolveBiliSong(song: SongItem, client: BiliClient): ResolvedBiliSong? {
    val resolutionSong = song.toBiliResolutionSongOrNull() ?: return null

    val storedCid = resolutionSong.biliCidOrNull()
    val storedBvid = resolutionSong.biliBvidOrNull()

    if (storedBvid != null) {
        val resolved = resolveByBvid(
            song = resolutionSong,
            client = client,
            bvid = storedBvid,
            preferredCid = storedCid
        )
        if (resolved != null) return resolved
    }

    if (storedCid != null) {
        val resolved = resolveByCandidates(
            song = resolutionSong,
            client = client,
            preferredCid = storedCid
        )
        if (resolved != null) return resolved
    }

    val direct = resolveDirect(resolutionSong, client)
    val legacy = resolveLegacy(resolutionSong, client)

    return legacy ?: direct
}

internal fun SongItem.toBiliResolutionSongOrNull(): SongItem? {
    if (album.startsWith(PlayerManager.BILI_SOURCE_TAG)) {
        return this
    }
    if (!channelId.equals("bilibili", ignoreCase = true)) {
        return null
    }
    val avid = audioId?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: return null
    val cid = biliCidOrNull()
    val bvid = biliBvidOrNull()
    return copy(
        id = avid,
        album = buildBiliSongAlbum(cid = cid, bvid = bvid)
    )
}

internal fun SongItem.biliCidOrNull(): Long? {
    val isBiliSong = channelId.equals("bilibili", ignoreCase = true) ||
        album.startsWith(PlayerManager.BILI_SOURCE_TAG, ignoreCase = true)
    if (!isBiliSong) return null

    return subAudioId
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?: album
            .substringAfter('|', "")
            .substringBefore('|')
            .trim()
            .toLongOrNull()
            ?.takeIf { it > 0L }
}

internal fun SongItem.biliBvidOrNull(): String? {
    val isBiliSong = channelId.equals("bilibili", ignoreCase = true) ||
        album.startsWith(PlayerManager.BILI_SOURCE_TAG, ignoreCase = true)
    if (!isBiliSong) return null

    return album
        .split('|')
        .getOrNull(2)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

internal fun selectBiliPlaybackPage(
    pages: List<BiliClient.VideoPage>,
    songName: String,
    preferredCid: Long? = null
): BiliClient.VideoPage? {
    if (preferredCid != null) {
        return pages.firstOrNull { page -> page.cid == preferredCid }
    }
    return pages.firstOrNull { page -> page.part == songName }
        ?: pages.firstOrNull()
}

private suspend fun resolveByBvid(
    song: SongItem,
    client: BiliClient,
    bvid: String,
    preferredCid: Long?
): ResolvedBiliSong? {
    val videoInfo = runCatching { client.getVideoBasicInfoByBvid(bvid) }.getOrNull() ?: return null
    val pageInfo = selectBiliPlaybackPage(
        pages = videoInfo.pages,
        songName = song.name,
        preferredCid = preferredCid
    )
    if (preferredCid != null && pageInfo == null) return null
    return ResolvedBiliSong(
        avid = videoInfo.aid,
        cid = pageInfo?.cid ?: 0L,
        videoInfo = videoInfo,
        pageInfo = pageInfo
    )
}

private suspend fun resolveByCandidates(
    song: SongItem,
    client: BiliClient,
    preferredCid: Long
): ResolvedBiliSong? {
    val direct = runCatching { client.getVideoBasicInfoByAvid(song.id) }.getOrNull()
    val directPage = direct?.pages?.firstOrNull { it.cid == preferredCid }
    if (direct != null && directPage != null) {
        return ResolvedBiliSong(
            avid = song.id,
            cid = directPage.cid,
            videoInfo = direct,
            pageInfo = directPage
        )
    }

    val legacyAvid = song.id / 10_000L
    if (legacyAvid <= 0L) return null

    val legacy = runCatching { client.getVideoBasicInfoByAvid(legacyAvid) }.getOrNull()
    val legacyPage = legacy?.pages?.firstOrNull { it.cid == preferredCid }
    if (legacy != null && legacyPage != null) {
        return ResolvedBiliSong(
            avid = legacyAvid,
            cid = legacyPage.cid,
            videoInfo = legacy,
            pageInfo = legacyPage
        )
    }

    return null
}

private suspend fun resolveDirect(song: SongItem, client: BiliClient): ResolvedBiliSong? {
    val videoInfo = runCatching { client.getVideoBasicInfoByAvid(song.id) }.getOrNull() ?: return null
    val pageInfo = selectBiliPlaybackPage(
        pages = videoInfo.pages,
        songName = song.name
    )

    val looksDirect = pageInfo?.part == song.name || videoInfo.title == song.name ||
        videoInfo.pages.size == 1
    if (!looksDirect) return null

    val cid = pageInfo?.cid ?: 0L
    return ResolvedBiliSong(
        avid = song.id,
        cid = cid,
        videoInfo = videoInfo,
        pageInfo = pageInfo
    )
}

private suspend fun resolveLegacy(song: SongItem, client: BiliClient): ResolvedBiliSong? {
    val legacyAvid = song.id / 10_000L
    val legacyPage = (song.id % 10_000L).toInt()
    if (legacyAvid <= 0L || legacyPage <= 0) return null

    val videoInfo = runCatching { client.getVideoBasicInfoByAvid(legacyAvid) }.getOrNull() ?: return null
    val pageInfo = videoInfo.pages.firstOrNull { page ->
        page.page == legacyPage || page.part == song.name
    } ?: return null

    return ResolvedBiliSong(
        avid = legacyAvid,
        cid = pageInfo.cid,
        videoInfo = videoInfo,
        pageInfo = pageInfo
    )
}
