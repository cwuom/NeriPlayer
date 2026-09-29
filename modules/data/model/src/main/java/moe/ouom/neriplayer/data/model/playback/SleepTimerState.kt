package moe.ouom.neriplayer.data.model.playback

enum class SleepTimerMode {
    /** 倒计时模式 */
    COUNTDOWN,
    /** 倒计时结束后播放完当前歌曲 */
    COUNTDOWN_FINISH_CURRENT,
    /** 播放完当前歌曲后停止 */
    FINISH_CURRENT,
    /** 播放完播放列表后停止 */
    FINISH_PLAYLIST
}

data class SleepTimerState(
    val isActive: Boolean = false,
    val mode: SleepTimerMode = SleepTimerMode.COUNTDOWN,
    val remainingMillis: Long = 0,
    val totalMillis: Long = 0
)
