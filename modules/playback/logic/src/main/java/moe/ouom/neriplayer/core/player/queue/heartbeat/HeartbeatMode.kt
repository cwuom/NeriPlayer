package moe.ouom.neriplayer.core.player.queue.heartbeat

/**
 * 心动模式开关取值。
 *
 * 沿用项目既有约定（与 [moe.ouom.neriplayer.core.player.queue.policy.QueueRepeatMode] 一致），
 * 用 Int 常量而非 Kotlin enum，以便直接写进播放状态持久化快照，
 * 且不会因为枚举序列化顺序变化而破坏旧存档。
 */
object HeartbeatMode {
    /** 心动模式关闭：播放推进完全沿用原有的顺序/随机/循环策略 */
    const val OFF = 0

    /** 心动模式开启：下一首由心动决策链决定 */
    const val ON = 1

    fun isValid(mode: Int): Boolean = mode == OFF || mode == ON

    fun normalized(mode: Int?): Int = if (mode != null && isValid(mode)) mode else OFF
}
