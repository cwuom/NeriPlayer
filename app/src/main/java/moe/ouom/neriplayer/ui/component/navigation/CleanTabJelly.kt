package moe.ouom.neriplayer.ui.component.navigation

import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Jelly physics of the enhanced tab lens, after FlClash / iOS 26: the lens stretches along its
 * travel in proportion to its speed, and once it slows an underdamped spring lets the stretch
 * overshoot into a brief squash, so the glass wobbles like jelly before it settles
 */

// 刚度和阻尼决定果冻回弹的节奏：约 0.3s 一个周期，回弹一次半后静止
internal const val JellyStiffness = 420f
internal const val JellyDampingRatio = 0.36f
// 拉伸量按「每秒划过几个 Tab」换算，上下限避免透镜被拉成细条或压成扁片
private const val JellyStretchPerTabPerSecond = 0.075f
internal const val JellyMaxStretch = 0.24f
internal const val JellyMaxSquash = -0.14f
// 积分子步长，帧率波动时保持弹簧稳定
private const val JellyMaxStepSeconds = 1f / 240f

internal data class JellySpring(val value: Float, val velocity: Float) {
    fun isAtRest(): Boolean = abs(value) < 0.002f && abs(velocity) < 0.02f

    companion object {
        val Rest = JellySpring(0f, 0f)
    }
}

/** Stretch the lens aims for at [speedPxPerSecond], relative to the width of one tab. */
internal fun jellyTarget(speedPxPerSecond: Float, tabWidthPx: Float): Float {
    if (tabWidthPx <= 0f) return 0f
    val tabsPerSecond = abs(speedPxPerSecond) / tabWidthPx
    return (tabsPerSecond * JellyStretchPerTabPerSecond).coerceAtMost(JellyMaxStretch)
}

/** Advances the jelly spring toward [target] by [dtSeconds] with semi-implicit Euler substeps. */
internal fun stepJelly(
    current: JellySpring,
    target: Float,
    dtSeconds: Float,
    stiffness: Float = JellyStiffness,
    dampingRatio: Float = JellyDampingRatio
): JellySpring {
    if (dtSeconds <= 0f) return current
    val damping = 2f * dampingRatio * sqrt(stiffness)
    var value = current.value
    var velocity = current.velocity
    var remaining = dtSeconds
    while (remaining > 0f) {
        val step = minOf(remaining, JellyMaxStepSeconds)
        velocity += (stiffness * (target - value) - damping * velocity) * step
        value += velocity * step
        remaining -= step
    }
    return JellySpring(value.coerceIn(JellyMaxSquash, JellyMaxStretch), velocity)
}

/**
 * Horizontal and vertical scale of the lens for a jelly value: the jelly only stretches or
 * narrows the lens along its travel. Its height never changes while dragging, since a lens that
 * flattens in motion springs back up when the finger stops and reads as the bar lifting
 */
internal fun jellyScale(jelly: Float): Pair<Float, Float> {
    val clamped = jelly.coerceIn(JellyMaxSquash, JellyMaxStretch)
    return (1f + clamped) to 1f
}
