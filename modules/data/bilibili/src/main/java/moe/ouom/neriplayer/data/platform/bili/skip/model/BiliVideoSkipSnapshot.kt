package moe.ouom.neriplayer.data.platform.bili.skip.model

data class BiliVideoSkipSnapshot(
    val rules: List<BiliVideoSkipRule>,
    val drafts: List<BiliVideoSkipDraft>
)
