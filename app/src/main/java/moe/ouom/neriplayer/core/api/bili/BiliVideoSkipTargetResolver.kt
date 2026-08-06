package moe.ouom.neriplayer.core.api.bili

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipTarget

internal data class BiliVideoSkipTargetOption(
    val target: BiliVideoSkipTarget,
    val label: String,
    val durationMs: Long
)

internal suspend fun resolveBiliVideoSkipTarget(
    song: SongItem,
    client: BiliClient
): BiliVideoSkipTarget? {
    val resolved = resolveBiliSong(song, client) ?: return null
    return BiliVideoSkipTarget(
        bvid = resolved.videoInfo.bvid,
        cid = resolved.cid
    ).normalizedOrNull()
}

internal suspend fun resolveBiliVideoSkipTargetOptions(
    song: SongItem,
    client: BiliClient
): List<BiliVideoSkipTargetOption> {
    val resolved = resolveBiliSong(song, client) ?: return emptyList()
    return resolved.videoInfo.toBiliVideoSkipTargetOptions()
}

internal suspend fun resolveBiliVideoSkipTargetOptions(
    bvid: String,
    client: BiliClient
): List<BiliVideoSkipTargetOption> {
    val normalizedBvid = bvid.trim()
    if (normalizedBvid.isEmpty()) return emptyList()
    return client.getVideoBasicInfoByBvid(normalizedBvid).toBiliVideoSkipTargetOptions()
}

private fun BiliClient.VideoBasicInfo.toBiliVideoSkipTargetOptions(): List<BiliVideoSkipTargetOption> {
    return pages.mapNotNull { page ->
        val target = BiliVideoSkipTarget(bvid = bvid, cid = page.cid).normalizedOrNull()
            ?: return@mapNotNull null
        BiliVideoSkipTargetOption(
            target = target,
            label = page.part.trim().ifBlank { "P${page.page}" },
            durationMs = page.durationSec.toLong().coerceAtLeast(0L) * 1_000L
        )
    }
}
