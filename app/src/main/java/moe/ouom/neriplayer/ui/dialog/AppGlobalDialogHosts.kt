package moe.ouom.neriplayer.ui.dialog

import androidx.compose.runtime.Composable
import moe.ouom.neriplayer.core.download.GlobalDownloadManager

@Composable
internal fun AppTrafficRiskDialogHost(
    request: GlobalDownloadManager.TrafficRiskDownloadRequest?,
    onConfirm: (GlobalDownloadManager.TrafficRiskDownloadRequest) -> Unit,
    onDismiss: () -> Unit
) {
    trafficRiskDialogContent(request, onConfirm, onDismiss)()
}

private fun trafficRiskDialogContent(
    request: GlobalDownloadManager.TrafficRiskDownloadRequest?,
    onConfirm: (GlobalDownloadManager.TrafficRiskDownloadRequest) -> Unit,
    onDismiss: () -> Unit
): @Composable () -> Unit {
    if (request == null) return {}
    return {
        TrafficRiskDownloadDialog(
            request = request,
            onConfirm = { onConfirm(request) },
            onDismiss = onDismiss
        )
    }
}

@Composable
internal fun AppUsbBackgroundPermissionDialogHost(
    visible: Boolean,
    readBatteryOptimizationAllowed: () -> Boolean,
    onRequestBatteryOptimization: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onNeverShowAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    if (visible) {
        UsbExclusiveBackgroundPermissionDialog(
            batteryOptimizationAllowed = readBatteryOptimizationAllowed(),
            onRequestBatteryOptimization = onRequestBatteryOptimization,
            onOpenAppSettings = onOpenAppSettings,
            onNeverShowAgain = onNeverShowAgain,
            onDismiss = onDismiss
        )
    }
}
