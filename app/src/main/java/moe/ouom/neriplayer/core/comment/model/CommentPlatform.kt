package moe.ouom.neriplayer.core.comment.model

/**
 * 评论来源平台。
 *
 * 注意: 平台只由歌曲的「逻辑音源」决定 (SongItem 的 channelId / album 来源标记)，
 * 与最终播放地址 (MediaItem / streamUrl / 播放回退结果) 无关。
 */
enum class CommentPlatform {
    NETEASE,
    BILIBILI
}
