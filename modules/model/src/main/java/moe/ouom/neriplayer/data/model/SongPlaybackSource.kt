package moe.ouom.neriplayer.data.model

import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels

/**
 * 歌曲的播放渠道短标识，用于拼身份键（`bili:123`、`netease:123` 等）。
 *
 * 放在 model 层而不是播放策略层，是因为「怎么判断一首歌属于哪个平台」需要看
 * 各平台自己的明细字段（如网易云的 `neteaseArtists`），而播放策略层的依赖边界
 * 不允许直接引用那些平台明细类型。
 *
 * 必须按渠道的**取值**判断，不能按「有没有值」判断：网易云歌曲的 `channelId`
 * 也是非空的（值为 `netease`），按存在性判断会把它们全当成 B 站，
 * 于是 bvid 恰好等于网易云歌曲 id 时两者身份相同，跳过降权、不喜欢名单、
 * 队列排除会互相串。
 */
fun SongItem.playbackSourceKey(): String {
    when (channelId?.trim()) {
        ListenTogetherChannels.BILIBILI -> return "bili"
        ListenTogetherChannels.YOUTUBE_MUSIC -> return "youtube"
        ListenTogetherChannels.NETEASE -> return "netease"
        ListenTogetherChannels.LOCAL -> return "local"
    }
    // 老数据可能没有 channelId，只能靠平台明细字段回推
    if (!neteaseArtists.isNullOrEmpty()) return "netease"
    if (album.startsWith(SongSourceTags.BILIBILI)) return "bili"
    return "local"
}
