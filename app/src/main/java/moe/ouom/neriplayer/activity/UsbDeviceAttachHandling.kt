package moe.ouom.neriplayer.activity

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import moe.ouom.neriplayer.core.logging.NPLogger

internal const val USB_DEVICE_ATTACHED_ACTIVITY_ALIAS_NAME =
    "moe.ouom.neriplayer.activity.UsbDeviceAttachedActivityAlias"

internal fun usbDeviceAttachAliasComponentState(
    handlingEnabled: Boolean
): Int {
    return if (handlingEnabled) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
}

internal fun shouldProcessUsbDeviceAttachedAction(
    action: String?,
    handlingEnabled: Boolean
): Boolean {
    return action != UsbManager.ACTION_USB_DEVICE_ATTACHED || handlingEnabled
}

internal object UsbDeviceAttachHandling {
    fun applyComponentState(context: Context, handlingEnabled: Boolean): Boolean {
        val appContext = context.applicationContext
        val packageManager = appContext.packageManager
        val component = ComponentName(
            appContext.packageName,
            USB_DEVICE_ATTACHED_ACTIVITY_ALIAS_NAME
        )
        val desiredState = usbDeviceAttachAliasComponentState(handlingEnabled)
        return runCatching {
            val currentState = packageManager.getComponentEnabledSetting(component)
            if (currentState == desiredState) {
                false
            } else {
                packageManager.setComponentEnabledSetting(
                    component,
                    desiredState,
                    PackageManager.DONT_KILL_APP
                )
                NPLogger.i(
                    "UsbDeviceAttachHandling",
                    "USB device attached alias state updated: enabled=$handlingEnabled"
                )
                true
            }
        }.getOrElse { error ->
            NPLogger.e(
                "UsbDeviceAttachHandling",
                "Failed to update USB device attached alias state",
                error
            )
            false
        }
    }
}
