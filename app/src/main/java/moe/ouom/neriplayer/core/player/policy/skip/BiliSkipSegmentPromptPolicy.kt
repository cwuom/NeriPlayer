package moe.ouom.neriplayer.core.player.policy.skip

import moe.ouom.neriplayer.R

internal enum class BiliSkipSegmentSource {
    CUSTOM_INTERVAL,
    SPONSOR_BLOCK
}

internal fun resolveBiliSkipSegmentPromptMessageRes(
    promptsEnabled: Boolean,
    source: BiliSkipSegmentSource
): Int? {
    if (!promptsEnabled) return null
    return when (source) {
        BiliSkipSegmentSource.CUSTOM_INTERVAL -> R.string.toast_bili_video_skip_skipped
        BiliSkipSegmentSource.SPONSOR_BLOCK -> R.string.toast_bili_sponsor_block_skipped
    }
}
