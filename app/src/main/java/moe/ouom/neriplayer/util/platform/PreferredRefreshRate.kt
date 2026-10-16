package moe.ouom.neriplayer.util.platform

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.View
import android.view.ViewGroup

internal fun resolvePreferredRefreshRate(
    highRefreshRateEnabled: Boolean,
    supportedRefreshRates: Iterable<Float>,
    currentRefreshRate: Float?
): Float {
    if (!highRefreshRateEnabled) return 0f
    return supportedRefreshRates
        .filter { rate -> rate.isFinite() && rate > 0f }
        .maxOrNull()
        ?: currentRefreshRate?.takeIf { rate -> rate.isFinite() && rate > 0f }
        ?: 0f
}

/** 播放页静置时只剩歌词推进、波形和背景这类慢速动效，60 Hz 足够顺滑 */
internal const val AMBIENT_REFRESH_RATE_HZ = 60f

/** 触摸结束后多久才降频，惯性滚动和点按反馈都在这之前结束 */
internal const val AMBIENT_REFRESH_RATE_IDLE_MS = 3_000L

internal fun isAmbientRefreshRateIdle(
    nowPlayingShown: Boolean,
    resumed: Boolean,
    touching: Boolean,
    idleForMs: Long
): Boolean = nowPlayingShown && resumed && !touching && idleForMs >= AMBIENT_REFRESH_RATE_IDLE_MS

/** 屏幕本来就不高于 60 Hz 时没有可省的，返回 null 沿用原偏好 */
internal fun resolveAmbientRefreshRate(supportedRefreshRates: Iterable<Float>): Float? {
    val rates = supportedRefreshRates.filter { rate -> rate.isFinite() && rate > 0f }
    if (rates.none { rate -> rate > AMBIENT_REFRESH_RATE_HZ + 1f }) return null
    return rates.filter { rate -> rate >= AMBIENT_REFRESH_RATE_HZ - 1f }.minOrNull()
}

internal fun Activity.applyPreferredHighRefreshRate(
    highRefreshRateEnabled: Boolean,
    ambientIdle: Boolean = false
): Boolean {
    val display = window.decorView.display ?:
        (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
    val supportedRates = display?.supportedModes.orEmpty().map { mode -> mode.refreshRate }
    val ambientRefreshRate = if (ambientIdle) resolveAmbientRefreshRate(supportedRates) else null
    val preferredRefreshRate = ambientRefreshRate ?: resolvePreferredRefreshRate(
        highRefreshRateEnabled = highRefreshRateEnabled,
        supportedRefreshRates = supportedRates,
        currentRefreshRate = display?.refreshRate
    )
    // 窗口偏好只决定默认模式，持续变化的内容仍会被系统按图层升到最高刷新率；
    // 静置降频时同时指定显示模式，并在 Android 15+ 给视图图层投 60 fps 的票
    val preferredModeId = ambientRefreshRate?.let { rate -> display?.matchingModeId(rate) } ?: 0
    applyPreferredRefreshRate(preferredRefreshRate, preferredModeId)
    applyRequestedViewFrameRate(ambientRefreshRate)
    return ambientRefreshRate != null
}

private fun Activity.applyRequestedViewFrameRate(frameRate: Float?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val requested = frameRate ?: View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
    fun apply(view: View) {
        val current = view.requestedFrameRate
        val unchanged = current == requested || (current.isNaN() && requested.isNaN())
        if (!unchanged) view.requestedFrameRate = requested
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) apply(view.getChildAt(index))
        }
    }
    apply(window.decorView)
}

private fun Display.matchingModeId(refreshRate: Float): Int? {
    val current = mode ?: return null
    return supportedModes.orEmpty().firstOrNull { candidate ->
        candidate.physicalWidth == current.physicalWidth &&
            candidate.physicalHeight == current.physicalHeight &&
            kotlin.math.abs(candidate.refreshRate - refreshRate) < 1f
    }?.modeId
}

private fun Activity.applyPreferredRefreshRate(preferredRefreshRate: Float, preferredModeId: Int) {
    val attributes = window.attributes
    if (attributes.preferredRefreshRate == preferredRefreshRate &&
        attributes.preferredDisplayModeId == preferredModeId
    ) {
        return
    }
    attributes.preferredRefreshRate = preferredRefreshRate
    attributes.preferredDisplayModeId = preferredModeId
    window.attributes = attributes
}
