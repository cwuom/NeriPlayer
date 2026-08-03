package moe.ouom.neriplayer.util.platform

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

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

internal fun Activity.applyPreferredHighRefreshRate(highRefreshRateEnabled: Boolean) {
    val display = window.decorView.display ?:
        (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
    val preferredRefreshRate = resolvePreferredRefreshRate(
        highRefreshRateEnabled = highRefreshRateEnabled,
        supportedRefreshRates = display?.supportedModes.orEmpty().map { mode ->
            mode.refreshRate
        },
        currentRefreshRate = display?.refreshRate
    )
    val attributes = window.attributes
    if (attributes.preferredRefreshRate == preferredRefreshRate) return
    attributes.preferredRefreshRate = preferredRefreshRate
    window.attributes = attributes
}
