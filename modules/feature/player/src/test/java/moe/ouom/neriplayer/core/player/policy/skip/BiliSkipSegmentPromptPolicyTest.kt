package moe.ouom.neriplayer.core.player.policy.skip

import moe.ouom.neriplayer.core.player.presentation.skip.resolveBiliSkipSegmentPromptMessageRes

import moe.ouom.neriplayer.core.common.R as CoreCommonR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliSkipSegmentPromptPolicyTest {
    @Test
    fun `disabled setting suppresses every automatic skip prompt`() {
        BiliSkipSegmentSource.entries.forEach { source ->
            assertNull(resolveBiliSkipSegmentPromptMessageRes(false, source))
        }
    }

    @Test
    fun `enabled setting preserves the source-specific skip prompt`() {
        assertEquals(
            CoreCommonR.string.toast_bili_video_skip_skipped,
            resolveBiliSkipSegmentPromptMessageRes(
                promptsEnabled = true,
                source = BiliSkipSegmentSource.CUSTOM_INTERVAL
            )
        )
        assertEquals(
            CoreCommonR.string.toast_bili_sponsor_block_skipped,
            resolveBiliSkipSegmentPromptMessageRes(
                promptsEnabled = true,
                source = BiliSkipSegmentSource.SPONSOR_BLOCK
            )
        )
    }
}
