package moe.ouom.neriplayer.ui.effect.glass

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.data.settings.appearance.isCurrentBuildDimensity

/**
 * Liquid glass adds a lens pass on top of the frosted mask. It is skipped in battery saver,
 * on low-RAM devices, and on Dimensity GPUs unless the user explicitly chose High quality.
 */
internal fun resolveLiquidGlassDeviceAllowed(
    powerSaveMode: Boolean,
    lowRamDevice: Boolean,
    dimensityDevice: Boolean,
    quality: AdvancedBlurQuality
): Boolean = !powerSaveMode && !lowRamDevice &&
    (!dimensityDevice || quality == AdvancedBlurQuality.High)

@Composable
internal fun rememberLiquidGlassDeviceAllowed(quality: AdvancedBlurQuality): Boolean {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val powerManager = remember(appContext) { appContext.getSystemService(PowerManager::class.java) }
    var powerSaveMode by remember(powerManager) {
        mutableStateOf(powerManager?.isPowerSaveMode == true)
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
        onDispose { runCatching { appContext.unregisterReceiver(receiver) } }
    }
    val lowRamDevice = remember(appContext) {
        appContext.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    }
    val dimensityDevice = remember { isCurrentBuildDimensity() }
    return resolveLiquidGlassDeviceAllowed(powerSaveMode, lowRamDevice, dimensityDevice, quality)
}
