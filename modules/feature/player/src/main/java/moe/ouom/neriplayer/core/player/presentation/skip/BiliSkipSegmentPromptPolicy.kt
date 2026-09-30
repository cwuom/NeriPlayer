package moe.ouom.neriplayer.core.player.presentation.skip

import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.policy.skip.BiliSkipSegmentSource

internal fun resolveBiliSkipSegmentPromptMessageRes(
    promptsEnabled: Boolean,
    source: BiliSkipSegmentSource
): Int? {
    if (!promptsEnabled) return null
    return when (source) {
        BiliSkipSegmentSource.CUSTOM_INTERVAL -> CoreCommonR.string.toast_bili_video_skip_skipped
        BiliSkipSegmentSource.SPONSOR_BLOCK -> CoreCommonR.string.toast_bili_sponsor_block_skipped
    }
}
