package moe.ouom.neriplayer.data.platform.bili.skip.model

data class BiliSponsorBlockSegment(
    val uuid: String,
    val category: String,
    val startMs: Long,
    val endMs: Long
)
