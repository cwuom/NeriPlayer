package moe.ouom.neriplayer.core.player.service

internal fun shouldKeepPlaybackServiceSticky(
    playerRuntimeReady: Boolean,
    hasPlaybackSurfaceContent: Boolean,
    hasResumableQueue: Boolean,
    foregroundPlaybackRequired: Boolean,
    listenTogetherSessionActive: Boolean,
): Boolean {
    if (!playerRuntimeReady || !hasPlaybackSurfaceContent) return false
    return hasResumableQueue || foregroundPlaybackRequired || listenTogetherSessionActive
}

internal fun shouldUseStickyStartModeWhilePlayerRuntimeInitializes(
    hasExplicitAction: Boolean,
): Boolean = !hasExplicitAction

/**
 * 前台提升失败时是否应保留 PlayerManager 运行时。
 *
 * 前台提升失败仅代表"服务无法保持前台"，不代表"播放运行时必须销毁"。
 * 当引擎正在播放或用户仍有播放诉求（播放控制处于播放态）时，销毁运行时会直接
 * 杀掉正在播放的会话；此时应仅放弃前台化并停止服务，保住当前播放。
 * 只有确无音频输出诉求（已暂停/无播放意图）时才允许销毁运行时。
 */
internal fun shouldPreservePlayerRuntimeOnForegroundPromotionFailure(
    enginePlaying: Boolean,
    playbackControlPlaying: Boolean,
): Boolean = enginePlaying || playbackControlPlaying
