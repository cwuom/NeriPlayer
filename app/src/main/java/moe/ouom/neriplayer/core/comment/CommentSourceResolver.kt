package moe.ouom.neriplayer.core.comment

import java.util.Locale
import moe.ouom.neriplayer.core.api.bili.biliBvidOrNull
import moe.ouom.neriplayer.core.api.bili.biliCidOrNull
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.comment.model.CommentSource
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem

internal const val BILIBILI_CHANNEL_ID = "bilibili"
internal const val NETEASE_CHANNEL_ID = "netease"

/**
 * 根据歌曲的「逻辑音源」解析评论来源。
 *
 * 关键规则: 平台只由 SongItem 的原始平台信息决定 ——
 * 显式 [SongItem.channelId] 优先, 其次才是 [SongItem.album] 的来源标记;
 * **绝不**由最终播放地址 (mediaUri / streamUrl) 或播放回退结果推断。
 *
 * 因此网易云歌曲即使音频回退到 Bilibili, 仍然返回网易云评论来源 (任务书 §49.2 的规范用例)。
 *
 * @return 平台不支持评论时返回 null (本地歌曲 / YouTube Music / 未知来源)
 */
internal fun resolveCommentSource(song: SongItem?): CommentSource? {
    if (song == null) return null

    val channelId = song.channelId?.trim()?.lowercase(Locale.ROOT).orEmpty()
    val platform = when {
        channelId == BILIBILI_CHANNEL_ID -> CommentPlatform.BILIBILI
        channelId == NETEASE_CHANNEL_ID -> CommentPlatform.NETEASE
        // channelId 缺失或未知时, 才回退到 album 的来源标记
        song.album.startsWith(PlayerManager.BILI_SOURCE_TAG, ignoreCase = true) ->
            CommentPlatform.BILIBILI

        song.album.startsWith(PlayerManager.NETEASE_SOURCE_TAG, ignoreCase = true) ->
            CommentPlatform.NETEASE

        else -> null
    } ?: return null

    val explicitResourceId = song.audioId?.trim()?.toLongOrNull()?.takeIf { it > 0L }
    val resourceId = explicitResourceId
        ?: song.id.takeIf { it > 0L }
        ?: return null
    val isBili = platform == CommentPlatform.BILIBILI
    val normalizedSong = if (isBili) song.copy(channelId = BILIBILI_CHANNEL_ID) else song
    val bvid = normalizedSong.biliBvidOrNull().takeIf { isBili }
    val cid = normalizedSong.biliCidOrNull().takeIf { isBili }

    return CommentSource(
        platform = platform,
        resourceId = resourceId,
        secondaryId = bvid,
        subResourceId = cid,
        resourceTitle = song.name.takeIf { isBili && bvid == null && cid == null },
        hasExplicitResourceId = isBili && explicitResourceId != null
    )
}
