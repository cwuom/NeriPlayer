package moe.ouom.neriplayer.ui.screen.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 心动模式 Snackbar 文案判定测试（任务书 §四 步骤 2 文案表）。
 *
 * 这个函数是「官方可用 / 本地降级 / 已关闭」三种文案的**唯一**判定点，
 * 所以这里逐条固化任务书要求的映射，防止以后改动把后缀弄丢。
 */
class NowPlayingHeartbeatFeedbackTest {

    private val enabled = "心动模式已开启"
    private val enabledLocal = "心动模式已开启（本地推荐）"
    private val disabled = "心动模式已关闭"

    @Test
    fun `enabled with official source uses the plain message`() {
        assertEquals(
            enabled,
            resolveHeartbeatSnackbarMessage(
                heartbeatEnabled = true,
                officialAvailable = true,
                messageEnabled = enabled,
                messageEnabledLocal = enabledLocal,
                messageDisabled = disabled,
            ),
        )
    }

    @Test
    fun `enabled with local fallback must carry the local suffix`() {
        assertEquals(
            "本地降级时必须带（本地推荐）后缀，否则会把本地算法说成官方推荐",
            enabledLocal,
            resolveHeartbeatSnackbarMessage(
                heartbeatEnabled = true,
                officialAvailable = false,
                messageEnabled = enabled,
                messageEnabledLocal = enabledLocal,
                messageDisabled = disabled,
            ),
        )
    }

    @Test
    fun `disabled always uses the disabled message`() {
        assertEquals(
            disabled,
            resolveHeartbeatSnackbarMessage(
                heartbeatEnabled = false,
                officialAvailable = true,
                messageEnabled = enabled,
                messageEnabledLocal = enabledLocal,
                messageDisabled = disabled,
            ),
        )
        assertEquals(
            disabled,
            resolveHeartbeatSnackbarMessage(
                heartbeatEnabled = false,
                officialAvailable = false,
                messageEnabled = enabled,
                messageEnabledLocal = enabledLocal,
                messageDisabled = disabled,
            ),
        )
    }
}
