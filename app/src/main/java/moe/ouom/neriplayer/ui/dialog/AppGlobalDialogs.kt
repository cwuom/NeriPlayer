package moe.ouom.neriplayer.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType
import moe.ouom.neriplayer.ui.haptic.HapticTextButton

internal fun trafficRiskNetworkLabelResource(networkType: TrafficNetworkType): Int = when (networkType) {
    TrafficNetworkType.ROAMING -> R.string.traffic_risk_network_roaming
    TrafficNetworkType.MOBILE -> R.string.traffic_risk_network_mobile
    TrafficNetworkType.WIFI -> R.string.traffic_risk_network_wifi
}

internal fun formatTrafficRiskDownloadMessage(
    request: GlobalDownloadManager.TrafficRiskDownloadRequest,
    networkLabel: String,
    singleMessage: (networkLabel: String, songName: String) -> String,
    batchMessage: (songCount: Int, networkLabel: String) -> String
): String = if (request.songCount <= 1) {
    singleMessage(networkLabel, request.songs.firstOrNull()?.displayName().orEmpty())
} else {
    batchMessage(request.songCount, networkLabel)
}

@Composable
private fun trafficRiskDownloadMessage(
    request: GlobalDownloadManager.TrafficRiskDownloadRequest,
    networkLabel: String
): String {
    val resources = LocalResources.current
    return formatTrafficRiskDownloadMessage(
        request = request,
        networkLabel = networkLabel,
        singleMessage = { network, songName ->
            resources.getString(R.string.traffic_risk_download_single_message, network, songName)
        },
        batchMessage = { count, network ->
            resources.getQuantityString(
                R.plurals.traffic_risk_download_batch_message, count, network, count
            )
        }
    )
}

@Composable
internal fun TrafficRiskDownloadDialog(
    request: GlobalDownloadManager.TrafficRiskDownloadRequest,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val networkLabel = stringResource(trafficRiskNetworkLabelResource(request.networkType))
    val message = trafficRiskDownloadMessage(request, networkLabel)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.traffic_risk_download_title)) },
        text = { Text(message) },
        confirmButton = {
            HapticTextButton(onClick = onConfirm) {
                Text(stringResource(R.string.traffic_risk_download_confirm))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
internal fun MobileDataDownloadInterruptionDialog(
    request: GlobalDownloadManager.MobileDataDownloadInterruptionRequest,
    onContinue: () -> Unit,
    onWaitWifi: () -> Unit,
    onCancelAll: () -> Unit
) {
    val networkLabel = stringResource(trafficRiskNetworkLabelResource(request.networkType))

    AlertDialog(
        onDismissRequest = onWaitWifi,
        title = { Text(stringResource(R.string.mobile_data_download_interruption_title)) },
        confirmButton = {},
        dismissButton = {},
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    pluralStringResource(
                        R.plurals.mobile_data_download_interruption_message,
                        request.taskCount,
                        networkLabel,
                        request.taskCount
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    HapticTextButton(onClick = onWaitWifi) {
                        Text(stringResource(R.string.mobile_data_download_wait_wifi))
                    }
                    HapticTextButton(onClick = onContinue) {
                        Text(stringResource(R.string.traffic_risk_download_confirm))
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.End)
                ) {
                    HapticTextButton(onClick = onCancelAll) {
                        Text(
                            stringResource(R.string.mobile_data_download_cancel_all),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    )
}

@Composable
internal fun UsbExclusiveBackgroundPermissionDialog(
    batteryOptimizationAllowed: Boolean,
    onRequestBatteryOptimization: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onNeverShowAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.settings_usb_exclusive_background_permission_title))
        },
        confirmButton = {},
        dismissButton = {},
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_usb_exclusive_background_permission_desc))
                if (!batteryOptimizationAllowed) {
                    HapticTextButton(
                        onClick = onRequestBatteryOptimization,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_usb_exclusive_background_permission_battery))
                    }
                }
                HapticTextButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_usb_exclusive_background_permission_app_settings))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    HapticTextButton(onClick = onNeverShowAgain) {
                        Text(stringResource(R.string.settings_usb_exclusive_background_permission_never))
                    }
                    HapticTextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.settings_usb_exclusive_background_permission_later))
                    }
                }
            }
        }
    )
}
