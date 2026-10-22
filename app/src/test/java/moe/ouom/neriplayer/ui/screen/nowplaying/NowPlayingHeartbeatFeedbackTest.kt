package moe.ouom.neriplayer.ui.screen.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心动模式文案判定测试（任务书 §四 步骤 2 文案表）。
 *
 * 界面上实际展示的是「用户主动开启 + 本地推荐」这一条（见 [shouldShowHeartbeatLocalHint]），
 * 这里同时覆盖那条提示的触发条件与 [resolveHeartbeatSnackbarMessage] 的完整文案表。
 */
class NowPlayingHeartbeatFeedbackTest {

    private val enabled = "心动模式已开启"
    private val enabledLocal = "心动模式已开启（本地推荐）"
    private val disabled = "心动模式已关闭"

    @Test
    fun `local hint shows only when official source is unavailable`() {
        assertTrue(
            "未登录或官方接口不可用时，用户需要知道当前用的是本地推荐",
            shouldShowHeartbeatLocalHint(officialAvailable = false),
        )
        assertFalse(
            "官方可用时用户已得到期待的效果，不该多弹一条提示",
            shouldShowHeartbeatLocalHint(officialAvailable = true),
        )
    }

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
