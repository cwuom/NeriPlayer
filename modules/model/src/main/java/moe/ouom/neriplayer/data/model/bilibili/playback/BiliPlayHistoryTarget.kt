package moe.ouom.neriplayer.data.model.bilibili.playback

/** 观看历史按 aid + cid 记到具体分 P 上 */
data class BiliPlayHistoryTarget(
    val aid: Long,
    val cid: Long,
    val bvid: String?
)
