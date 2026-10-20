package moe.ouom.neriplayer.ui.screen.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 心动模式文案判定测试（任务书 §四 步骤 2 文案表）。
 *
 * 当前 UI 只在**失败**时提示（用户要求成功不提示），所以 `resolveHeartbeatSnackbarMessage`
 * 并不在界面上被调用。保留并测试它的原因是：任务书要求「本地降级不得表述为官方推荐」，
 * 这条合规映射需要一个单一判定点，将来恢复成功提示时直接接回去即可。
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
