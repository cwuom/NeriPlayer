package moe.ouom.neriplayer.util

import android.content.Context
import moe.ouom.neriplayer.util.platform.areBackgroundAppOpsAllowedCompat as areBackgroundAppOpsAllowedCompatImpl
import moe.ouom.neriplayer.util.platform.isIgnoringBatteryOptimizationsCompat as isIgnoringBatteryOptimizationsCompatImpl
import moe.ouom.neriplayer.util.platform.openAppBackgroundSettings as openAppBackgroundSettingsImpl
import moe.ouom.neriplayer.util.platform.readBackgroundBehaviorAllowance as readBackgroundBehaviorAllowanceImpl
import moe.ouom.neriplayer.util.platform.requestIgnoreBatteryOptimizationsCompat as requestIgnoreBatteryOptimizationsCompatImpl

data class BackgroundBehaviorAllowance(
    val ignoringBatteryOptimizations: Boolean,
    val backgroundAppOpsAllowed: Boolean
) {
    val fullyAllowed: Boolean
        get() = ignoringBatteryOptimizations && backgroundAppOpsAllowed
}

fun Context.readBackgroundBehaviorAllowance(): BackgroundBehaviorAllowance {
    return this.readBackgroundBehaviorAllowanceImpl().toRootAllowance()
}

fun Context.isIgnoringBatteryOptimizationsCompat(): Boolean {
    return this.isIgnoringBatteryOptimizationsCompatImpl()
}

fun Context.areBackgroundAppOpsAllowedCompat(): Boolean {
    return this.areBackgroundAppOpsAllowedCompatImpl()
}

fun Context.requestIgnoreBatteryOptimizationsCompat(): Boolean {
    return this.requestIgnoreBatteryOptimizationsCompatImpl()
}

fun Context.openAppBackgroundSettings(): Boolean {
    return this.openAppBackgroundSettingsImpl()
}

private fun moe.ouom.neriplayer.util.platform.BackgroundBehaviorAllowance.toRootAllowance(): BackgroundBehaviorAllowance {
    return BackgroundBehaviorAllowance(
        ignoringBatteryOptimizations = ignoringBatteryOptimizations,
        backgroundAppOpsAllowed = backgroundAppOpsAllowed
    )
}
