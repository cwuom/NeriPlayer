package moe.ouom.neriplayer.ui.animation

/**
 * 播放页的装饰性持续动画（波形、歌词插值、动态背景）按绝对帧时间分格推进。
 * 各自的格长都是这个基准的整数倍，在 120 Hz 屏上会落到同一批 vsync，
 * 整个窗口按最快那个动画的 60 fps 出帧，而不是几个节奏错开后叠满每一帧
 */
internal const val AMBIENT_ANIMATION_FRAME_INTERVAL_NANOS = 1_000_000_000L / 60L

internal fun ambientFrameSlot(frameNanos: Long, intervalNanos: Long): Long =
    frameNanos / intervalNanos.coerceAtLeast(1L)

/** 本帧是否进入了新的格子；首帧总是渲染 */
internal fun isNewAmbientFrameSlot(
    lastRenderedFrameNanos: Long,
    frameNanos: Long,
    intervalNanos: Long
): Boolean {
    if (lastRenderedFrameNanos == 0L) return true
    return ambientFrameSlot(frameNanos, intervalNanos) != ambientFrameSlot(lastRenderedFrameNanos, intervalNanos)
}
