package moe.ouom.neriplayer.util.platform

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import moe.ouom.neriplayer.core.logging.NPLogger

private const val BACKGROUND_BEHAVIOR_TAG = "NERI-BackgroundBehavior"
private const val OPSTR_RUN_IN_BACKGROUND = "android:run_in_background"
private const val OPSTR_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background"

data class BackgroundBehaviorAllowance(
    val ignoringBatteryOptimizations: Boolean,
    val backgroundAppOpsAllowed: Boolean
) {
    val fullyAllowed: Boolean
        get() = ignoringBatteryOptimizations && backgroundAppOpsAllowed
}

fun Context.readBackgroundBehaviorAllowance(): BackgroundBehaviorAllowance {
    return BackgroundBehaviorAllowance(
        ignoringBatteryOptimizations = isIgnoringBatteryOptimizationsCompat(),
        backgroundAppOpsAllowed = areBackgroundAppOpsAllowedCompat()
    )
}

fun Context.isIgnoringBatteryOptimizationsCompat(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val powerManager = getSystemService(PowerManager::class.java) ?: return false
    return powerManager.isIgnoringBatteryOptimizations(packageName)
}

fun Context.areBackgroundAppOpsAllowedCompat(): Boolean {
    val appOpsManager = getSystemService(AppOpsManager::class.java) ?: return true
    return isBackgroundAppOpAllowed(appOpsManager, OPSTR_RUN_IN_BACKGROUND) &&
        isBackgroundAppOpAllowed(appOpsManager, OPSTR_RUN_ANY_IN_BACKGROUND)
}

@SuppressLint("BatteryLife")
fun Context.requestIgnoreBatteryOptimizationsCompat(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
    if (isIgnoringBatteryOptimizationsCompat()) return true

    val packageUri = Uri.parse("package:$packageName")
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return startActivitySafely(intent) {
        openAppBackgroundSettings()
    }
}

fun Context.openAppBackgroundSettings(): Boolean {
    val packageUri = Uri.parse("package:$packageName")
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return startActivitySafely(intent) {
        val fallback = Intent(Settings.ACTION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivitySafely(fallback)
    }
}

private fun Context.startActivitySafely(
    intent: Intent,
    fallback: (() -> Boolean)? = null
): Boolean {
    return try {
        startActivity(intent)
        true
    } catch (error: ActivityNotFoundException) {
        NPLogger.w(
            BACKGROUND_BEHAVIOR_TAG,
            "background behavior settings activity missing: action=${intent.action}",
            error
        )
        fallback?.invoke() == true
    } catch (error: SecurityException) {
        NPLogger.w(
            BACKGROUND_BEHAVIOR_TAG,
            "background behavior settings activity blocked: action=${intent.action}",
            error
        )
        fallback?.invoke() == true
    }
}

@Suppress("DEPRECATION")
private fun Context.isBackgroundAppOpAllowed(
    appOpsManager: AppOpsManager,
    op: String
): Boolean {
    val mode = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(op, applicationInfo.uid, packageName)
        } else {
            appOpsManager.checkOpNoThrow(op, applicationInfo.uid, packageName)
        }
    }.onFailure { error ->
        NPLogger.w(
            BACKGROUND_BEHAVIOR_TAG,
            "background app-op check failed: op=$op",
            error
        )
    }.getOrNull() ?: return true
    return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
}
