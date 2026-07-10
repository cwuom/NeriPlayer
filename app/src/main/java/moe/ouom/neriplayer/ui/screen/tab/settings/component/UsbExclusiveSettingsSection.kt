package moe.ouom.neriplayer.ui.screen.tab.settings.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnostics
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnosticsSnapshot
import moe.ouom.neriplayer.core.player.usb.UsbExclusiveNativeState
import moe.ouom.neriplayer.core.player.usb.UsbExclusiveSessionController
import moe.ouom.neriplayer.data.settings.UsbExclusiveBitDepthMode
import moe.ouom.neriplayer.data.settings.UsbExclusiveBufferProfile
import moe.ouom.neriplayer.data.settings.DEFAULT_USB_EXCLUSIVE_DEVICE_KEY
import moe.ouom.neriplayer.data.settings.UsbExclusivePreferences
import moe.ouom.neriplayer.data.settings.UsbExclusiveSampleRateMode
import moe.ouom.neriplayer.data.settings.UsbExclusiveUnsupportedFormatPolicy
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard

private const val USB_STATUS_REFRESH_INTERVAL_MS = 1_000L

@Composable
internal fun UsbExclusiveSettingsSection(
    usbExclusivePlayback: Boolean,
    onUsbExclusivePlaybackChange: (Boolean) -> Unit,
    preferences: UsbExclusivePreferences,
    onDeviceKeyChange: (String) -> Unit,
    onSampleRateModeChange: (UsbExclusiveSampleRateMode) -> Unit,
    onBitDepthModeChange: (UsbExclusiveBitDepthMode) -> Unit,
    onBufferProfileChange: (UsbExclusiveBufferProfile) -> Unit,
    onUnsupportedFormatPolicyChange: (UsbExclusiveUnsupportedFormatPolicy) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val nativeState by UsbExclusiveSessionController.state.collectAsState()
    var snapshot by remember(context) {
        mutableStateOf(UsbExclusiveDiagnostics.snapshot(context))
    }

    LaunchedEffect(context) {
        while (currentCoroutineContext().isActive) {
            delay(USB_STATUS_REFRESH_INTERVAL_MS)
            UsbExclusiveSessionController.refresh(context)
            snapshot = UsbExclusiveDiagnostics.snapshot(context)
        }
    }
    LaunchedEffect(nativeState, usbExclusivePlayback) {
        UsbExclusiveSessionController.refresh(context)
        snapshot = UsbExclusiveDiagnostics.snapshot(context)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MiuixSettingsSectionCard {
            UsbExclusiveMasterSwitch(
                enabled = usbExclusivePlayback,
                onEnabledChange = onUsbExclusivePlaybackChange
            )
            SettingsDivider()
            UsbExclusiveStatusContent(
                enabled = usbExclusivePlayback,
                snapshot = snapshot,
                nativeState = nativeState
            )
        }

        MiuixSettingsSectionCard {
            SettingsSectionTitle(
                icon = Icons.Outlined.Usb,
                title = stringResource(R.string.settings_usb_exclusive_device_section)
            )
            UsbExclusiveDeviceContent(
                snapshot = snapshot,
                preferences = preferences,
                onDeviceKeyChange = onDeviceKeyChange
            )
        }

        MiuixSettingsSectionCard {
            SettingsSectionTitle(
                icon = Icons.Outlined.GraphicEq,
                title = stringResource(R.string.settings_usb_exclusive_quality_section)
            )
            UsbExclusiveQualityContent(
                snapshot = snapshot,
                preferences = preferences,
                onSampleRateModeChange = onSampleRateModeChange,
                onBitDepthModeChange = onBitDepthModeChange,
                onBufferProfileChange = onBufferProfileChange,
                onUnsupportedFormatPolicyChange = onUnsupportedFormatPolicyChange
            )
        }

        MiuixSettingsSectionCard {
            SettingsSectionTitle(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.settings_usb_exclusive_compatibility_section)
            )
            SettingsInfoItem(
                title = stringResource(R.string.settings_usb_exclusive_app_processing),
                value = stringResource(R.string.settings_usb_exclusive_app_processing_desc)
            )
            SettingsDivider()
            SettingsInfoItem(
                title = stringResource(R.string.settings_usb_exclusive_system_effects),
                value = stringResource(R.string.settings_usb_exclusive_system_effects_desc)
            )
        }
    }
}

@Composable
private fun UsbExclusiveMasterSwitch(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    ListItem(
        modifier = Modifier.settingsItemClickable {
            onEnabledChange(!enabled)
        },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Usb,
                contentDescription = stringResource(R.string.settings_usb_exclusive_playback),
                modifier = Modifier.size(24.dp)
            )
        },
        headlineContent = {
            Text(stringResource(R.string.settings_usb_exclusive_playback))
        },
        supportingContent = {
            Text(stringResource(R.string.settings_usb_exclusive_playback_desc))
        },
        trailingContent = {
            MiuixSettingsSwitch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        },
        colors = transparentListItemColors()
    )
}

@Composable
private fun UsbExclusiveStatusContent(
    enabled: Boolean,
    snapshot: UsbExclusiveDiagnosticsSnapshot,
    nativeState: UsbExclusiveNativeState
) {
    val status = resolveUsbStatus(enabled, snapshot, nativeState)
    StatusBanner(status)
    SettingsInfoItem(
        title = stringResource(R.string.settings_usb_exclusive_native_mode),
        value = snapshot.nativeExclusiveSummary
    )
    SettingsDivider()
    SettingsInfoItem(
        title = stringResource(R.string.settings_usb_exclusive_native_source),
        value = nativeSourceLabel(snapshot.nativeExclusiveSource)
    )
    SettingsDivider()
    SettingsInfoItem(
        title = stringResource(R.string.settings_usb_exclusive_player_state),
        value = stringResource(
            if (snapshot.playerPlaying) {
                R.string.settings_usb_exclusive_player_playing
            } else {
                R.string.settings_usb_exclusive_player_idle
            }
        )
    )
    UsbExclusiveRuntimeSummary(snapshot, nativeState)
}

@Composable
private fun UsbExclusiveRuntimeSummary(
    snapshot: UsbExclusiveDiagnosticsSnapshot,
    nativeState: UsbExclusiveNativeState
) {
    val inputSummary = summarizeInputFormat(snapshot.inputFormat)
    val outputSummary = summarizeNativeOutput(nativeState, snapshot.nativeExclusiveRuntime)
    val bufferSummary = summarizeNativeBuffer(snapshot.nativeExclusiveRuntime, nativeState)
    val rawError = if (snapshot.usbExclusivePlaybackEnabled) {
        snapshot.fallbackReason
            ?: snapshot.nativeExclusiveError?.takeUnless { it.isBlank() || it == "none" }
    } else {
        null
    }
    val errorSummary = usbExclusiveIssueLabel(rawError)

    if (inputSummary != null) {
        SettingsDivider()
        SettingsInfoItem(
            title = stringResource(R.string.settings_usb_exclusive_input_format),
            value = inputSummary
        )
    }
    if (outputSummary != null) {
        SettingsDivider()
        SettingsInfoItem(
            title = stringResource(R.string.settings_usb_exclusive_output_format),
            value = outputSummary
        )
    }
    if (bufferSummary != null) {
        SettingsDivider()
        SettingsInfoItem(
            title = stringResource(R.string.settings_usb_exclusive_buffer_state),
            value = bufferSummary
        )
    }
    SettingsDivider()
    SettingsInfoItem(
        title = stringResource(R.string.settings_usb_exclusive_error),
        value = errorSummary,
        compact = true
    )
}

@Composable
private fun UsbExclusiveDeviceContent(
    snapshot: UsbExclusiveDiagnosticsSnapshot,
    preferences: UsbExclusivePreferences,
    onDeviceKeyChange: (String) -> Unit
) {
    var showDeviceDialog by remember { mutableStateOf(false) }
    val device = snapshot.selectedUsbHostDevice
    val output = snapshot.selectedUsbOutput
    val deviceName = device?.productName
        ?.takeIf(String::isNotBlank)
        ?: output?.productName?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.settings_usb_exclusive_no_device)
    val selectedDeviceLabel = if (preferences.selectedDeviceKey == DEFAULT_USB_EXCLUSIVE_DEVICE_KEY) {
        stringResource(R.string.settings_usb_exclusive_device_selection_auto, deviceName)
    } else {
        device?.productName?.takeIf(String::isNotBlank)
            ?: deviceName
    }

    ListItem(
        modifier = Modifier.settingsItemClickable {
            showDeviceDialog = true
        },
        headlineContent = {
            Text(stringResource(R.string.settings_usb_exclusive_device_selection))
        },
        supportingContent = {
            Text(selectedDeviceLabel)
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f)
            )
        },
        colors = transparentListItemColors()
    )
    SettingsDivider()
    SettingsInfoItem(
        title = stringResource(R.string.settings_usb_exclusive_device_id),
        value = device?.vendorProductId
            ?: stringResource(R.string.settings_usb_exclusive_not_available)
    )
    SettingsDivider()
    UsbPermissionItem(snapshot)
    SettingsDivider()
    SettingsInfoItem(
        title = stringResource(R.string.settings_usb_exclusive_system_output),
        value = output?.let {
            "${it.typeName} · ${it.address.ifBlank { stringResource(R.string.settings_usb_exclusive_not_available) }}"
        } ?: stringResource(R.string.settings_usb_exclusive_not_available)
    )

    if (showDeviceDialog) {
        UsbDeviceSelectionDialog(
            snapshot = snapshot,
            selectedDeviceKey = preferences.selectedDeviceKey,
            currentAutoDeviceName = deviceName,
            onSelect = { deviceKey ->
                onDeviceKeyChange(deviceKey)
                showDeviceDialog = false
            },
            onDismiss = { showDeviceDialog = false }
        )
    }
}

@Composable
private fun UsbDeviceSelectionDialog(
    snapshot: UsbExclusiveDiagnosticsSnapshot,
    selectedDeviceKey: String,
    currentAutoDeviceName: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val autoTitle = stringResource(R.string.settings_usb_exclusive_device_auto)
    val autoSubtitle = stringResource(
        R.string.settings_usb_exclusive_device_selection_auto,
        currentAutoDeviceName
    )
    val options = listOf(
        UsbDeviceSelectionOption(
            key = DEFAULT_USB_EXCLUSIVE_DEVICE_KEY,
            title = autoTitle,
            subtitle = autoSubtitle
        )
    ) + snapshot.usbHostDevices
        .filter { it.hasAudioInterface }
        .map { device ->
            UsbDeviceSelectionOption(
                key = device.deviceKey,
                title = device.productName.takeIf(String::isNotBlank)
                    ?: device.deviceName,
                subtitle = device.vendorProductId
            )
        }

    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_usb_exclusive_choose_device)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(options) { option ->
                    MiuixSettingsChoiceRow(
                        title = option.title,
                        subtitle = option.subtitle,
                        selected = option.key == selectedDeviceKey,
                        onClick = { onSelect(option.key) }
                    )
                }
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

private data class UsbDeviceSelectionOption(
    val key: String,
    val title: String,
    val subtitle: String
)

private fun summarizeInputFormat(inputFormat: String): String? {
    val sampleRate = inputFormat.valueAfter("sampleRate")?.toIntOrNull()
    val channels = inputFormat.valueAfter("channels")?.toIntOrNull()
    val encoding = inputFormat.valueAfter("encoding")?.toIntOrNull()
    return joinUsbFormatParts(
        sampleRate?.formatSampleRate(),
        channels?.let { "$it ch" },
        encoding?.audioEncodingLabel()
    )
}

private fun summarizeNativeOutput(
    nativeState: UsbExclusiveNativeState,
    runtimeReport: String
): String? {
    val outputFormat = nativeState.outputFormat.takeUnless { it == "none" }
    val sampleRate = outputFormat?.valueAfter("rate")?.toIntOrNull()
        ?: runtimeReport.valueAfter("negotiatedRate")?.toIntOrNull()
        ?: runtimeReport.valueAfter("sampleRate")?.toIntOrNull()
    val channels = outputFormat?.valueAfter("channels")?.toIntOrNull()
        ?: runtimeReport.valueAfter("channels")?.toIntOrNull()
    val bits = outputFormat?.valueAfter("bits")?.toIntOrNull()
        ?: runtimeReport.valueAfter("bits")?.toIntOrNull()
    return joinUsbFormatParts(
        sampleRate?.formatSampleRate(),
        channels?.let { "$it ch" },
        bits?.let { "$it-bit" }
    )
}

private fun summarizeNativeBuffer(
    runtimeReport: String,
    nativeState: UsbExclusiveNativeState
): String? {
    val fifoMs = runtimeReport.valueAfter("fifoMs")?.toIntOrNull()
    val bufferMs = runtimeReport.valueAfter("bufferMs")?.toIntOrNull()
        ?: nativeState.bufferDurationMs.takeIf { it > 0 }
    return when {
        fifoMs != null && bufferMs != null -> "$fifoMs / $bufferMs ms"
        bufferMs != null -> "$bufferMs ms"
        else -> null
    }
}

private fun joinUsbFormatParts(vararg parts: String?): String? {
    return parts.filterNotNull()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = " · ")
}

private fun String.valueAfter(key: String): String? {
    val regex = Regex("(?:^|\\s)${Regex.escape(key)}=([^\\s]+)")
    return regex.find(this)?.groupValues?.getOrNull(1)
}

@Composable
private fun UsbPermissionItem(snapshot: UsbExclusiveDiagnosticsSnapshot) {
    val canRequestPermission = snapshot.canRequestPermission
    val permissionLabel = when {
        snapshot.hasUsbPermission -> stringResource(R.string.settings_usb_exclusive_permission_granted)
        canRequestPermission -> stringResource(R.string.settings_usb_exclusive_permission_required)
        else -> stringResource(R.string.settings_usb_exclusive_permission_no_device)
    }
    val context = LocalContext.current.applicationContext

    ListItem(
        modifier = Modifier.settingsItemClickable(enabled = canRequestPermission) {
            UsbExclusiveDiagnostics.ensureUsbPermissionIfNeeded(
                context = context,
                reason = "usb_exclusive_settings"
            )
        },
        headlineContent = {
            Text(stringResource(R.string.settings_usb_exclusive_permission))
        },
        supportingContent = {
            Text(permissionLabel)
        },
        trailingContent = if (canRequestPermission) {
            {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f)
                )
            }
        } else {
            null
        },
        colors = transparentListItemColors()
    )
}

@Composable
private fun StatusBanner(status: UsbStatusPresentation) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = status.color.copy(alpha = 0.12f),
        contentColor = status.color
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = status.icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = status.title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = status.description,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(icon: ImageVector, title: String) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(21.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
internal fun SettingsInfoItem(
    title: String,
    value: String,
    detail: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    compact: Boolean = false
) {
    ListItem(
        headlineContent = {
            Text(text = title)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = value,
                    color = valueColor,
                    style = if (compact) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    maxLines = if (compact) 5 else 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        colors = transparentListItemColors()
    )
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
private fun transparentListItemColors() = ListItemDefaults.colors(
    containerColor = Color.Transparent
)
