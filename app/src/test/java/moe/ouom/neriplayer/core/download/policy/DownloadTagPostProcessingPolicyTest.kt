package moe.ouom.neriplayer.core.download.policy

import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriteOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归测试: 标签后处理失败时保留音频, 但不能伪造完成状态
 */
class DownloadTagPostProcessingPolicyTest {

    @Test
    fun `success finalizes with tags`() {
        assertEquals(
            TagPostProcessingAction.FINALIZE_TAGGED,
            tagPostProcessingAction(DownloadedAudioTagWriteOutcome.SUCCESS, hasRemainingAttempts = true)
        )
        assertEquals(
            TagPostProcessingAction.FINALIZE_TAGGED,
            tagPostProcessingAction(DownloadedAudioTagWriteOutcome.SUCCESS, hasRemainingAttempts = false)
        )
    }

    @Test
    fun `unsupported container keeps audio without retrying`() {
        assertEquals(
            TagPostProcessingAction.FINALIZE_UNTAGGED,
            tagPostProcessingAction(
                DownloadedAudioTagWriteOutcome.UNSUPPORTED_CONTAINER,
                hasRemainingAttempts = true
            )
        )
        assertEquals(
            TagPostProcessingAction.FINALIZE_UNTAGGED,
            tagPostProcessingAction(
                DownloadedAudioTagWriteOutcome.UNSUPPORTED_CONTAINER,
                hasRemainingAttempts = false
            )
        )
    }

    @Test
    fun `transient failure retries while attempts remain`() {
        assertEquals(
            TagPostProcessingAction.RETRY,
            tagPostProcessingAction(DownloadedAudioTagWriteOutcome.FAILED, hasRemainingAttempts = true)
        )
        assertEquals(
            TagPostProcessingAction.RETRY,
            tagPostProcessingAction(outcome = null, hasRemainingAttempts = true)
        )
    }

    @Test
    fun `persistent failure keeps audio unfinalized for later retry`() {
        assertEquals(
            TagPostProcessingAction.PRESERVE_UNFINALIZED,
            tagPostProcessingAction(DownloadedAudioTagWriteOutcome.FAILED, hasRemainingAttempts = false)
        )
        assertEquals(
            TagPostProcessingAction.PRESERVE_UNFINALIZED,
            tagPostProcessingAction(outcome = null, hasRemainingAttempts = false)
        )
    }
}
