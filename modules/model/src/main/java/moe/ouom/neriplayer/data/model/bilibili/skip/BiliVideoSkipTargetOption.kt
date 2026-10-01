package moe.ouom.neriplayer.data.model.bilibili.skip

data class BiliVideoSkipTargetOption(
    val target: BiliVideoSkipTarget,
    val label: String,
    val durationMs: Long
)
