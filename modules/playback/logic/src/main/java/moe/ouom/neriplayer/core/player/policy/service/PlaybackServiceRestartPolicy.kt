package moe.ouom.neriplayer.core.player.policy.service

fun shouldKeepPlaybackServiceSticky(
    playerRuntimeReady: Boolean,
    hasPlaybackSurfaceContent: Boolean,
    hasResumableQueue: Boolean,
    foregroundPlaybackRequired: Boolean,
    listenTogetherSessionActive: Boolean,
): Boolean {
    if (!playerRuntimeReady || !hasPlaybackSurfaceContent) return false
    return hasResumableQueue || foregroundPlaybackRequired || listenTogetherSessionActive
}

fun shouldUseStickyStartModeWhilePlayerRuntimeInitializes(
    hasExplicitAction: Boolean,
): Boolean = !hasExplicitAction

/**
 * 前台提升失败时是否应保留 PlayerManager 运行时
 *
 * 前台提升失败仅代表"服务无法保持前台", 不代表"播放运行时必须销毁"
 * 当引擎正在播放或用户仍有播放诉求 (播放控制处于播放态) 时, 销毁运行时会直接
 * 杀掉正在播放的会话; 此时应仅放弃前台化并停止服务, 保住当前播放
 * 仅浏览绑定保留的暂停队列也不能因前台提升失败而销毁
 */
fun shouldPreservePlayerRuntimeOnForegroundPromotionFailure(
    enginePlaying: Boolean,
    playbackControlPlaying: Boolean,
    keepPausedRuntime: Boolean = false,
): Boolean = enginePlaying || playbackControlPlaying || keepPausedRuntime
