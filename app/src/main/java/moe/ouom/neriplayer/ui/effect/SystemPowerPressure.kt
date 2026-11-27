package moe.ouom.neriplayer.ui.effect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** 系统声明体验已明显受影响时才让出可选特效, 轻中度温控期间保持原样 */
@RequiresApi(Build.VERSION_CODES.Q)
internal fun isSevereThermalThrottling(thermalStatus: Int): Boolean =
    thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE

internal data class SystemPowerPressure(
    val powerSaveMode: Boolean,
    val thermalThrottling: Boolean
) {
    val reduceOptionalEffects: Boolean
        get() = powerSaveMode || thermalThrottling
}

/** 省电模式和温控都会在使用中变化, 这里持续观察而不是进入页面时读一次 */
@Composable
internal fun rememberSystemPowerPressure(): SystemPowerPressure {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val powerManager = remember(appContext) { appContext.getSystemService(PowerManager::class.java) }
    var powerSaveMode by remember(powerManager) {
        mutableStateOf(powerManager?.isPowerSaveMode == true)
    }
    var thermalThrottling by remember(powerManager) {
        mutableStateOf(powerManager?.isSevereThermalThrottlingNow() == true)
    }
    DisposableEffect(appContext, powerManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                powerSaveMode = powerManager?.isPowerSaveMode == true
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val unregisterThermal = if (powerManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager.observeSevereThermalThrottling(appContext) { thermalThrottling = it }
        } else {
            null
        }
        onDispose {
            runCatching { appContext.unregisterReceiver(receiver) }
            unregisterThermal?.invoke()
        }
    }
    return SystemPowerPressure(
        powerSaveMode = powerSaveMode,
        thermalThrottling = thermalThrottling
    )
}

private fun PowerManager.isSevereThermalThrottlingNow(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isSevereThermalThrottling(currentThermalStatus)

@RequiresApi(Build.VERSION_CODES.Q)
private fun PowerManager.observeSevereThermalThrottling(
    context: Context,
    onChanged: (Boolean) -> Unit
): () -> Unit {
    val listener = PowerManager.OnThermalStatusChangedListener { status ->
        onChanged(isSevereThermalThrottling(status))
    }
    addThermalStatusListener(ContextCompat.getMainExecutor(context), listener)
    return { runCatching { removeThermalStatusListener(listener) } }
}
