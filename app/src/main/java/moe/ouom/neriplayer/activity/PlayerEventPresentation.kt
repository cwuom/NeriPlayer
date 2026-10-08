package moe.ouom.neriplayer.activity

import moe.ouom.neriplayer.data.model.playback.PlayerEvent

internal sealed interface PlayerEventPresentation {
    val message: String

    /** 需要用户确认的提示，例如登录或播放失败 */
    data class Dialog(override val message: String) : PlayerEventPresentation

    /** 自动消失的提示，不打断当前操作 */
    data class Notice(override val message: String) : PlayerEventPresentation
}

internal fun playerEventPresentation(event: PlayerEvent): PlayerEventPresentation = when (event) {
    is PlayerEvent.ShowLoginPrompt -> PlayerEventPresentation.Dialog(event.message)
    is PlayerEvent.ShowError -> PlayerEventPresentation.Dialog(event.message)
    is PlayerEvent.ShowNotice -> PlayerEventPresentation.Notice(event.message)
}
