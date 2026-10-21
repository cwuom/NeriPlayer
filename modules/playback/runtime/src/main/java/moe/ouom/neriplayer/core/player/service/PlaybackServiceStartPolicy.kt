package moe.ouom.neriplayer.core.player.service

import android.os.Build
import androidx.lifecycle.Lifecycle

internal const val APP_BOOTSTRAP_START_SOURCE = "app_bootstrap"

internal fun shouldUseForegroundServiceStart(
    sdkInt: Int,
    forceForeground: Boolean,
    shouldRunPlaybackServiceInForeground: Boolean,
    callerHasResumedUi: Boolean
): Boolean {
    if (callerHasResumedUi) {
        return false
    }
    return sdkInt >= Build.VERSION_CODES.O ||
        forceForeground ||
        shouldRunPlaybackServiceInForeground
}

fun canUseDirectPlaybackServiceStart(
    isFinishing: Boolean,
    isDestroyed: Boolean,
    lifecycleState: Lifecycle.State?,
    hasWindowFocus: Boolean
): Boolean {
    if (isFinishing || isDestroyed) {
        return false
    }
    return lifecycleState?.isAtLeast(Lifecycle.State.RESUMED) == true && hasWindowFocus
}

internal fun isServiceStartNotAllowedFailure(error: Throwable): Boolean {
    if (error !is IllegalStateException) {
        return false
    }
    val simpleName = error::class.java.simpleName
    if (
        simpleName == "BackgroundServiceStartNotAllowedException" ||
        simpleName == "ForegroundServiceStartNotAllowedException"
    ) {
        return true
    }
    return error.message?.contains("Not allowed to start service") == true
}
