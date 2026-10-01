package moe.ouom.neriplayer.data.model.bilibili.video

data class VideoStats(
    val view: Long,
    val danmaku: Long,
    val reply: Long,
    val favorite: Long,
    val coin: Long,
    val share: Long,
    val like: Long
)

data class VideoBasicInfo(
    val aid: Long,
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val desc: String,
    val durationSec: Int,
    val ownerMid: Long,
    val ownerName: String,
    val ownerFace: String,
    val stats: VideoStats,
    val pages: List<VideoPage>,
    val ugcSeason: UgcSeason? = null
)

data class UgcSeason(
    val id: Long,
    val mid: Long,
    val title: String
)

data class VideoPage(
    val cid: Long,
    val page: Int,
    val part: String,
    val durationSec: Int,
    val width: Int,
    val height: Int
)
