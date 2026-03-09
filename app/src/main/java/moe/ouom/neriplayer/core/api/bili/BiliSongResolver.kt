package moe.ouom.neriplayer.core.api.bili

import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

data class ResolvedBiliSong(
    val avid: Long,
    val cid: Long,
    val videoInfo: BiliClient.VideoBasicInfo,
    val pageInfo: BiliClient.VideoPage?
)

fun buildBiliPartSong(
    page: BiliClient.VideoPage,
    basicInfo: BiliClient.VideoBasicInfo,
    coverUrl: String
): SongItem {
    return SongItem(
        id = basicInfo.aid,
        name = page.part,
        artist = basicInfo.ownerName,
        album = "${PlayerManager.BILI_SOURCE_TAG}|${page.cid}",
        albumId = 0L,
        durationMs = page.durationSec * 1000L,
        coverUrl = coverUrl
    )
}

suspend fun resolveBiliSong(song: SongItem, client: BiliClient): ResolvedBiliSong? {
    if (!song.album.startsWith(PlayerManager.BILI_SOURCE_TAG)) return null

    val parts = song.album.split('|')
    val storedCid = parts.getOrNull(1)?.toLongOrNull()

    if (storedCid != null) {
        val resolved = resolveByCandidates(
            song = song,
            client = client,
            preferredCid = storedCid
        )
        if (resolved != null) return resolved
    }

    val direct = resolveDirect(song, client)
    val legacy = resolveLegacy(song, client)

    return when {
        legacy != null && direct == null -> legacy
        direct != null && legacy == null -> direct
        legacy != null && legacy.pageInfo != null -> legacy
        direct != null -> direct
        else -> legacy
    }
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
    val matchedPage = videoInfo.pages.firstOrNull { page ->
        page.part == song.name
    }
    val fallbackPage = matchedPage ?: videoInfo.pages.firstOrNull()

    val looksDirect = matchedPage != null || videoInfo.title == song.name || videoInfo.pages.size == 1
    if (!looksDirect) return null

    val cid = fallbackPage?.cid ?: 0L
    return ResolvedBiliSong(
        avid = song.id,
        cid = cid,
        videoInfo = videoInfo,
        pageInfo = matchedPage
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
