package moe.ouom.neriplayer.ui.onboarding

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import java.util.Locale
import moe.ouom.neriplayer.data.settings.appearance.currentBuildUsesDimensityBlurCompatibility
import moe.ouom.neriplayer.data.settings.appearance.isCurrentBuildDimensity
import moe.ouom.neriplayer.data.settings.appearance.isCurrentDeviceEntryLevelBlurDevice

internal fun readStartupDeviceSpec(context: Context): StartupDeviceSpec {
    val appContext = context.applicationContext
    val activityManager = appContext.getSystemService(ActivityManager::class.java)
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager?.getMemoryInfo(memoryInfo)
    val soc = identifyStartupSoc(
        socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null,
        socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
        hardware = Build.HARDWARE
    )
    return StartupDeviceSpec(
        displayName = resolveStartupDeviceDisplayName(appContext),
        modelCode = Build.MODEL.orEmpty(),
        socLabel = soc.label,
        socClass = soc.socClass,
        sdkInt = Build.VERSION.SDK_INT,
        ramGb = roundStartupRamGb(memoryInfo.totalMem),
        cpuCores = Runtime.getRuntime().availableProcessors(),
        maxRefreshRate = roundStartupRefreshRate(readStartupMaxRefreshRate(appContext)),
        lowRamDevice = activityManager?.isLowRamDevice == true,
        powerSaveMode = appContext.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true,
        dimensityDevice = isCurrentBuildDimensity(),
        dimensityBlurCompatibility = currentBuildUsesDimensityBlurCompatibility(),
        entryLevelBlurDevice = isCurrentDeviceEntryLevelBlurDevice(appContext)
    )
}

private fun readStartupMaxRefreshRate(context: Context): Float {
    val display = context.getSystemService(DisplayManager::class.java)
        ?.getDisplay(Display.DEFAULT_DISPLAY)
        ?: return 60f
    return display.supportedModes.maxOfOrNull { it.refreshRate } ?: display.refreshRate
}

// 系统设备名多为厂商的市场名, 拿不到时退回 厂商 + 型号
private fun resolveStartupDeviceDisplayName(context: Context): String {
    val deviceName = runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull()?.trim()
    if (!deviceName.isNullOrEmpty()) return deviceName
    val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
    }
    val model = Build.MODEL.orEmpty()
    return when {
        model.startsWith(manufacturer, ignoreCase = true) -> model
        manufacturer.isEmpty() -> model
        else -> "$manufacturer $model"
    }
}
