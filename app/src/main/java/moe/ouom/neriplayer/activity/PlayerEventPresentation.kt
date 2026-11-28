package moe.ouom.neriplayer.activity

import moe.ouom.neriplayer.data.model.playback.PlayerEvent

internal sealed interface PlayerEventPresentation {
    /** 需要用户确认的提示，例如登录或播放失败 */
    data class Dialog(val message: String) : PlayerEventPresentation

    /** 自动消失的提示，不打断当前操作 */
    data class Notice(val message: String) : PlayerEventPresentation

    /** 目录授权失效，交给独立弹窗提供重新授权入口 */
    data class StoragePermission(val event: PlayerEvent.StoragePermissionLost) : PlayerEventPresentation
}

internal fun playerEventPresentation(event: PlayerEvent): PlayerEventPresentation = when (event) {
    is PlayerEvent.ShowLoginPrompt -> PlayerEventPresentation.Dialog(event.message)
    is PlayerEvent.ShowError -> PlayerEventPresentation.Dialog(event.message)
    is PlayerEvent.ShowNotice -> PlayerEventPresentation.Notice(event.message)
    is PlayerEvent.StoragePermissionLost -> PlayerEventPresentation.StoragePermission(event)
}
