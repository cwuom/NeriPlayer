package moe.ouom.neriplayer.ui.screen.nowplaying.dlna

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.dlna.session.DlnaCastManager
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaAudioQualityReport
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaConnectionStatus
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDevice
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaDeviceOptions
import moe.ouom.neriplayer.data.model.playback.dlna.DlnaStreamMode
import moe.ouom.neriplayer.data.model.playback.formatPlaybackSampleRate
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetDragBlocker
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.screen.nowplaying.rememberAudioDeviceInfo
import moe.ouom.neriplayer.ui.theme.neriRoundedShape

@Composable
fun DlnaOutputAndVolumeSheetContent() {
    val context = LocalContext.current
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var currentLocalVolume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    val audioDeviceInfo = rememberAudioDeviceInfo()
    val castState by DlnaCastManager.castStateFlow.collectAsStateWithLifecycle()

    var expandedOptionsUdn by remember { mutableStateOf<String?>(null) }
    var showManualDialog by remember { mutableStateOf(false) }
    var manualInput by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }
    var manualProbeRequestId by remember { mutableIntStateOf(0) }

    LaunchedEffect(castState.isCastingActive) {
        if (!castState.isCastingActive) {
            currentLocalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }

    LaunchedEffect(Unit) {
        if (castState.discoveredDevices.isEmpty() && !castState.isDiscovering) {
            DlnaCastManager.refreshDiscovery()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bottomSheetDragBlocker()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header: current output & quick switch back to local
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = audioDeviceInfo.second,
                    contentDescription = audioDeviceInfo.first,
                    tint = if (castState.isCastingActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = audioDeviceInfo.first,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (castState.isCastingActive) {
                        Text(
                            text = when (castState.connectionStatus) {
                                DlnaConnectionStatus.CONNECTING ->
                                    stringResource(CoreCommonR.string.dlna_status_connecting)
                                DlnaConnectionStatus.CONNECTED ->
                                    stringResource(CoreCommonR.string.dlna_status_connected)
                                DlnaConnectionStatus.ERROR ->
                                    stringResource(CoreCommonR.string.dlna_status_error)
                                DlnaConnectionStatus.DISCONNECTED -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (castState.connectionStatus == DlnaConnectionStatus.ERROR) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }
            }
            if (castState.isCastingActive) {
                OutlinedButton(onClick = { DlnaCastManager.switchToLocalPlayback() }) {
                    Text(stringResource(CoreCommonR.string.dlna_switch_to_local))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Volume section (Remote DLNA volume when casting; local STREAM_MUSIC otherwise)
        if (castState.isCastingActive) {
            if (castState.volumeSupported && castState.remoteVolume != null) {
                val remoteVol = castState.remoteVolume ?: 0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(CoreCommonR.string.dlna_remote_volume, remoteVol),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Slider(
                    value = remoteVol.toFloat(),
                    onValueChange = { DlnaCastManager.setRemoteVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = stringResource(CoreCommonR.string.dlna_volume_unsupported),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = audioDeviceInfo.second,
                    contentDescription = audioDeviceInfo.first
                )
                Slider(
                    value = currentLocalVolume.toFloat(),
                    onValueChange = {
                        currentLocalVolume = it.toInt()
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentLocalVolume, 0)
                    },
                    valueRange = 0f..maxVolume.toFloat(),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Actual resolved audio quality / strict lossless guard status card
        castState.qualityReport?.let { report ->
            Spacer(modifier = Modifier.height(10.dp))
            DlnaQualityStatusCard(report = report)
        }

        castState.errorMessage?.takeIf {
            it.isNotBlank() && castState.qualityReport?.strictLosslessBlocked != true
        }?.let { errorText ->
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = neriRoundedShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = errorText,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(12.dp))

        // DLNA Devices Header + Scan / Add Manual IP
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(CoreCommonR.string.dlna_section_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showManualDialog = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(CoreCommonR.string.dlna_add_manual))
                }
                IconButton(
                    onClick = { DlnaCastManager.refreshDiscovery() },
                    enabled = !castState.isDiscovering
                ) {
                    if (castState.isDiscovering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(CoreCommonR.string.dlna_scan_devices)
                        )
                    }
                }
            }
        }

        if (castState.isDiscovering) {
            Text(
                text = stringResource(CoreCommonR.string.dlna_scanning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }

        if (castState.discoveredDevices.isEmpty() && !castState.isDiscovering) {
            Text(
                text = stringResource(CoreCommonR.string.dlna_no_devices),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            )
        } else {
            castState.discoveredDevices.forEach { device ->
                val isSelected = castState.activeDevice?.udn == device.udn
                val options = castState.optionsFor(device.udn)
                DlnaDeviceRow(
                    device = device,
                    isSelected = isSelected,
                    optionsExpanded = expandedOptionsUdn == device.udn,
                    options = options,
                    onSelect = {
                        if (isSelected) {
                            DlnaCastManager.switchToLocalPlayback()
                        } else {
                            DlnaCastManager.selectDevice(device)
                        }
                    },
                    onToggleOptions = {
                        expandedOptionsUdn = if (expandedOptionsUdn == device.udn) null else device.udn
                    },
                    onUpdateOptions = { transform ->
                        DlnaCastManager.updateDeviceOptions(device.udn, transform)
                    },
                    onRemoveManual = if (device.isManual) {
                        { DlnaCastManager.removeManualDevice(device.udn) }
                    } else null
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    if (showManualDialog) {
        AlertDialog(
            onDismissRequest = {
                showManualDialog = false
                manualError = null
            },
            title = { Text(stringResource(CoreCommonR.string.dlna_add_manual_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = {
                            manualInput = it
                            manualError = null
                            manualProbeRequestId += 1
                        },
                        label = { Text(stringResource(CoreCommonR.string.dlna_add_manual_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    manualError?.let { err ->
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        val reqId = manualProbeRequestId + 1
                        manualProbeRequestId = reqId
                        DlnaCastManager.addManualDevice(manualInput) { result ->
                            if (reqId != manualProbeRequestId || !showManualDialog) return@addManualDevice
                            result.onSuccess {
                                showManualDialog = false
                                manualInput = ""
                                manualError = null
                            }.onFailure { err ->
                                manualError = err.message ?: "Failed to connect"
                            }
                        }
                    }
                ) {
                    Text(stringResource(CoreCommonR.string.action_confirm))
                }
            },
            dismissButton = {
                HapticTextButton(
                    onClick = {
                        manualProbeRequestId += 1
                        showManualDialog = false
                        manualError = null
                    }
                ) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun DlnaQualityStatusCard(report: DlnaAudioQualityReport) {
    val containerColor = when {
        report.strictLosslessBlocked -> MaterialTheme.colorScheme.errorContainer
        report.deliveryMode == DlnaStreamMode.ADAPTIVE_DIRECT ->
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        report.degraded -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
    }
    val contentColor = when {
        report.strictLosslessBlocked -> MaterialTheme.colorScheme.onErrorContainer
        report.deliveryMode == DlnaStreamMode.ADAPTIVE_DIRECT ->
            MaterialTheme.colorScheme.onSurfaceVariant
        report.degraded -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = containerColor,
        shape = neriRoundedShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (report.strictLosslessBlocked ||
                    (report.degraded && report.deliveryMode != DlnaStreamMode.ADAPTIVE_DIRECT)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = if (report.strictLosslessBlocked) {
                        stringResource(CoreCommonR.string.dlna_strict_blocked_title)
                    } else {
                        stringResource(CoreCommonR.string.dlna_quality_card_title)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
            }
            val specParts = buildList {
                if (report.resolvedFormat.isNotBlank()) add(report.resolvedFormat)
                report.sampleRateHz?.takeIf { it > 0 }?.let { add(formatPlaybackSampleRate(it)) }
                report.bitDepth?.takeIf { it > 0 }?.let { add("$it-bit") }
                report.bitrateKbps?.takeIf { it > 0 }?.let { add("$it kbps") }
                report.channelCount?.takeIf { it > 0 }?.let { add("${it}ch") }
            }
            if (specParts.isNotEmpty()) {
                Text(
                    text = specParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor
                )
            }
            if (!report.strictLosslessBlocked) {
                val modeText = when (report.deliveryMode) {
                    DlnaStreamMode.ORIGINAL_FLAC -> stringResource(CoreCommonR.string.dlna_mode_original_flac)
                    DlnaStreamMode.LOSSLESS_WAV -> stringResource(CoreCommonR.string.dlna_mode_wav)
                    DlnaStreamMode.LOSSLESS_WAV_ID3 -> stringResource(CoreCommonR.string.dlna_mode_wav_id3)
                    DlnaStreamMode.ADAPTIVE_DIRECT -> stringResource(CoreCommonR.string.dlna_mode_adaptive_direct)
                }
                Text(
                    text = modeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.85f)
                )
            }
            if (report.strictLosslessBlocked && !report.blockReason.isNullOrBlank()) {
                Text(
                    text = report.blockReason.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor
                )
                OutlinedButton(
                    onClick = { DlnaCastManager.disableStrictLosslessAndRetryCurrent() },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(stringResource(CoreCommonR.string.dlna_allow_lossy_and_retry))
                }
            } else if (report.degraded &&
                report.deliveryMode != DlnaStreamMode.ADAPTIVE_DIRECT &&
                !report.degradationReason.isNullOrBlank()
            ) {
                Text(
                    text = stringResource(
                        CoreCommonR.string.dlna_degraded_notice,
                        report.degradationReason.orEmpty()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun DlnaDeviceRow(
    device: DlnaDevice,
    isSelected: Boolean,
    optionsExpanded: Boolean,
    options: DlnaDeviceOptions,
    onSelect: () -> Unit,
    onToggleOptions: () -> Unit,
    onUpdateOptions: ((DlnaDeviceOptions) -> DlnaDeviceOptions) -> Unit,
    onRemoveManual: (() -> Unit)?
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(
                    text = device.friendlyName,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            },
            supportingContent = {
                val subtitle = buildList {
                    if (device.host.isNotBlank()) add(device.host)
                    if (device.modelName.isNotBlank()) add(device.modelName)
                    if (device.supportsFlac) add("FLAC")
                    if (device.supportsWav) add("WAV")
                }.joinToString(" · ")
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            },
            leadingContent = {
                Icon(
                    imageVector = if (isSelected) Icons.Outlined.CastConnected else Icons.Outlined.Cast,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onRemoveManual != null) {
                        IconButton(onClick = onRemoveManual) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(CoreCommonR.string.dlna_remove_manual_device)
                            )
                        }
                    }
                    IconButton(onClick = onToggleOptions) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = stringResource(CoreCommonR.string.dlna_device_options)
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    Color.Transparent
                }
            ),
            modifier = Modifier
                .clip(neriRoundedShape(12.dp))
                .clickable(onClick = onSelect)
        )

        if (optionsExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DlnaOptionToggleRow(
                    label = stringResource(CoreCommonR.string.dlna_opt_prefer_flac),
                    checked = options.preferOriginalFlac,
                    onCheckedChange = { checked ->
                        onUpdateOptions { it.copy(preferOriginalFlac = checked) }
                    }
                )
                DlnaOptionToggleRow(
                    label = stringResource(CoreCommonR.string.dlna_opt_wav_fallback),
                    checked = options.wavFallbackEnabled,
                    onCheckedChange = { checked ->
                        onUpdateOptions { it.copy(wavFallbackEnabled = checked) }
                    }
                )
                DlnaOptionToggleRow(
                    label = stringResource(CoreCommonR.string.dlna_opt_wav_id3),
                    checked = options.wavEmbedId3Enabled,
                    onCheckedChange = { checked ->
                        onUpdateOptions { it.copy(wavEmbedId3Enabled = checked) }
                    }
                )
                DlnaOptionToggleRow(
                    label = stringResource(CoreCommonR.string.dlna_opt_strict_lossless),
                    checked = options.strictLosslessMode,
                    onCheckedChange = { checked ->
                        onUpdateOptions { it.copy(strictLosslessMode = checked) }
                    }
                )
            }
        }
    }
}

@Composable
private fun DlnaOptionToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
