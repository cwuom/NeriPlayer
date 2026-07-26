package moe.ouom.neriplayer.core.download.policy

import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioTagWriteOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归测试 P1-1：标签后处理失败绝不能删除已完整下载的音频。
 *
 * 关键场景：SAF Provider 打不开可写 fd -> TagWriter 持续返回 FAILED -> 重试耗尽后
 * 必须保留音频（FINALIZE_UNTAGGED），而不是删除并反复重下耗流量。
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
    fun `persistent failure keeps complete audio instead of deleting`() {
        // P1-1 核心断言：重试耗尽（如 SAF 持续无可写 fd）也保留音频，绝不回滚删除
        assertEquals(
            TagPostProcessingAction.FINALIZE_UNTAGGED,
            tagPostProcessingAction(DownloadedAudioTagWriteOutcome.FAILED, hasRemainingAttempts = false)
        )
        assertEquals(
            TagPostProcessingAction.FINALIZE_UNTAGGED,
            tagPostProcessingAction(outcome = null, hasRemainingAttempts = false)
        )
    }
}
