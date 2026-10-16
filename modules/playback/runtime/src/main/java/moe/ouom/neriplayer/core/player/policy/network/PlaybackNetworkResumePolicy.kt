package moe.ouom.neriplayer.core.player.policy.network

/**
 * 断网后停在原处等网络回来；窗口之外不再自动出声，
 * 免得几个小时后网络恢复时突然开始播放
 */
const val PLAYBACK_NETWORK_RESUME_WINDOW_MS = 30L * 60L * 1000L

data class PlaybackNetworkResumeRequest(
    val songKey: String,
    val requestToken: Long,
    val positionGeneration: Long,
    val positionMs: Long,
    val armedAtElapsedMs: Long,
    val reason: String
)

/**
 * 只在用户仍想播放、当前曲目需要联网、而系统此刻确实没有可用网络时接管，
 * 有网时的失败仍走原来的换源 / 跳曲 / 报错流程
 */
fun shouldHoldPlaybackForNetwork(
    playbackRequested: Boolean,
    networkUsable: Boolean,
    requiresNetwork: Boolean
): Boolean = playbackRequested && requiresNetwork && !networkUsable

/** 用户在等待期间做过任何播放操作、换过歌、拖过进度或已经手动播放，都不再自动恢复 */
fun shouldResumePlaybackAfterNetworkRestored(
    request: PlaybackNetworkResumeRequest,
    nowElapsedMs: Long,
    currentSongKey: String?,
    currentRequestToken: Long,
    currentPositionGeneration: Long,
    playbackRequested: Boolean,
    windowMs: Long = PLAYBACK_NETWORK_RESUME_WINDOW_MS
): Boolean {
    val elapsedMs = nowElapsedMs - request.armedAtElapsedMs
    return elapsedMs in 0..windowMs &&
        currentSongKey == request.songKey &&
        currentRequestToken == request.requestToken &&
        currentPositionGeneration == request.positionGeneration &&
        !playbackRequested
}
