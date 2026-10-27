package moe.ouom.neriplayer.ui.screen.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.playback.usb.UsbAudioOutputDebugInfo
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnostics
import moe.ouom.neriplayer.data.model.playback.usb.UsbHostDeviceDebugInfo
import moe.ouom.neriplayer.core.player.usb.session.UsbExclusiveSessionController
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.R as AppR

@Composable
fun UsbExclusiveDebugScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(UsbExclusiveDiagnostics.snapshot(context)) }
    var nativeState by remember { mutableStateOf(UsbExclusiveSessionController.state.value) }
    var copied by remember { mutableStateOf(false) }
    var debugActionRunning by remember { mutableStateOf(false) }
    val miniH = LocalMiniPlayerHeight.current
    val noneText = stringResource(AppR.string.debug_usb_exclusive_none)

    fun runDebugAction(
        reason: String,
        action: () -> Unit = {}
    ) {
        if (debugActionRunning) return
        debugActionRunning = true
        scope.launch {
            val refreshed = withContext(Dispatchers.IO) {
                runCatching {
                    action()
                    UsbExclusiveDiagnostics.logSnapshot(appContext, reason)
                    UsbExclusiveSessionController.refresh(appContext)
                    UsbExclusiveDiagnostics.snapshot(appContext) to
                        UsbExclusiveSessionController.state.value
                }
            }
            refreshed.onSuccess { (nextSnapshot, nextNativeState) ->
                snapshot = nextSnapshot
                nativeState = nextNativeState
            }.onFailure { error ->
                NPLogger.e("NERI-UsbExclusive", "debug action failed: reason=$reason", error)
            }
            debugActionRunning = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(bottom = miniH),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(AppR.string.debug_usb_exclusive_title),
            style = MaterialTheme.typography.titleLarge
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    copied = false
                    runDebugAction("debug_refresh")
                },
                enabled = !debugActionRunning,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(CoreCommonR.string.action_refresh)
                )
                Text(stringResource(CoreCommonR.string.action_refresh))
            }
            OutlinedButton(
                onClick = {
                    copyUsbReport(context, snapshot.toReport())
                    copied = true
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(AppR.string.debug_usb_exclusive_copy_report)
                )
                Text(stringResource(AppR.string.debug_usb_exclusive_copy_report))
            }
        }

        Button(
            onClick = {
                runDebugAction("debug_request_permission") {
                    UsbExclusiveDiagnostics.ensureUsbPermissionIfNeeded(
                        context = appContext,
                        reason = "debug_button"
                    )
                }
            },
            enabled = snapshot.canRequestPermission && !debugActionRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Outlined.Usb,
                contentDescription = stringResource(AppR.string.debug_usb_exclusive_request_permission)
            )
            Text(stringResource(AppR.string.debug_usb_exclusive_request_permission))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    copied = false
                    runDebugAction("debug_start_native_generated_tone") {
                        UsbExclusiveSessionController.startGeneratedTone(appContext)
                    }
                },
                enabled = snapshot.hasUsbPermission &&
                    !debugActionRunning &&
                    !nativeState.transitioning &&
                    !nativeState.streaming &&
                    nativeState.source != "player_pcm",
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Usb,
                    contentDescription = stringResource(AppR.string.debug_usb_exclusive_start_native_tone)
                )
                Text(stringResource(AppR.string.debug_usb_exclusive_start_native_tone))
            }
            OutlinedButton(
                onClick = {
                    runDebugAction("debug_stop_native_generated_tone") {
                        UsbExclusiveSessionController.stopGeneratedTone()
                    }
                },
                enabled = !debugActionRunning &&
                    !nativeState.transitioning &&
                    nativeState.source == "tone",
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Stop,
                    contentDescription = stringResource(AppR.string.debug_usb_exclusive_stop_native_tone)
                )
                Text(stringResource(AppR.string.debug_usb_exclusive_stop_native_tone))
            }
        }

        if (copied) {
            Text(
                text = stringResource(AppR.string.log_copied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        DebugCard(title = stringResource(AppR.string.debug_usb_exclusive_section_summary)) {
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_mode), snapshot.systemRouteSummary)
            DebugRow(
                stringResource(AppR.string.debug_usb_exclusive_limitation),
                snapshot.systemRouteLimitation
            )
            DebugRow(
                stringResource(CoreCommonR.string.settings_usb_exclusive_playback),
                snapshot.usbExclusivePlaybackEnabled.yesNoText()
            )
            DebugRow(
                stringResource(CoreCommonR.string.settings_allow_mixed_playback),
                snapshot.allowMixedPlaybackEnabled.yesNoText()
            )
            DebugRow(
                stringResource(AppR.string.debug_usb_exclusive_player_state),
                "init=${snapshot.playerInitialized}, playing=${snapshot.playerPlaying}"
            )
            DebugRow(
                stringResource(AppR.string.debug_usb_exclusive_current_output),
                "${snapshot.currentPlayerDeviceType ?: noneText}:${snapshot.currentPlayerDeviceName ?: noneText}"
            )
            DebugRow(
                stringResource(AppR.string.debug_usb_exclusive_last_permission),
                snapshot.lastPermissionEvent?.compactLine() ?: noneText
            )
        }

        DebugCard(title = stringResource(AppR.string.debug_usb_exclusive_section_native_runtime)) {
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_native), snapshot.nativeExclusiveSummary)
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_available), nativeState.available.yesNoText())
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_opened), nativeState.opened.yesNoText())
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_streaming), nativeState.streaming.yesNoText())
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_paused), nativeState.paused.yesNoText())
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_transitioning), nativeState.transitioning.yesNoText())
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_source), nativeState.source)
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_device), nativeState.selectedDeviceName ?: noneText)
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_requested_path), snapshot.requestedPath)
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_effective_path), snapshot.effectivePath)
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_fallback_reason), snapshot.fallbackReason ?: noneText)
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_input_format), nativeState.inputFormat)
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_output_format), nativeState.outputFormat)
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_buffer), "${nativeState.bufferDurationMs}ms")
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_completed_frames), nativeState.completedAudioFrames.toString())
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_queued_frames), nativeState.queuedAudioFrames.toString())
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_pcm_bytes), "${nativeState.pcmLevelBytes}/${nativeState.pcmCapacityBytes}")
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_pcm_free), nativeState.pcmFreeBytes.toString())
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_backpressure), "events=${nativeState.pcmBackpressureEvents}, current=${nativeState.pcmBackpressureCurrentMs}ms, max=${nativeState.pcmBackpressureMaxMs}ms")
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_service), "instance=${snapshot.audioServiceInstanceActive.yesNoText()}, foreground=${snapshot.audioServiceForegroundActive.yesNoText()}, wakeLock=${snapshot.usbWakeLockHeld.yesNoText()}")
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_runtime), nativeState.runtimeReport)
            DebugRow(stringResource(AppR.string.debug_usb_exclusive_error), nativeState.lastError ?: noneText)
        }

        DebugCard(title = stringResource(AppR.string.debug_usb_exclusive_section_audio_outputs)) {
            if (snapshot.audioOutputs.isEmpty()) {
                Text(
                    text = stringResource(AppR.string.debug_usb_exclusive_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                snapshot.audioOutputs.forEach { output ->
                    AudioOutputBlock(output)
                }
            }
        }

        DebugCard(title = stringResource(AppR.string.debug_usb_exclusive_section_usb_host)) {
            if (snapshot.usbHostDevices.isEmpty()) {
                Text(
                    text = stringResource(AppR.string.debug_usb_exclusive_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                snapshot.usbHostDevices.forEach { device ->
                    UsbHostDeviceBlock(device)
                }
            }
        }

        DebugCard(title = stringResource(AppR.string.debug_usb_exclusive_section_report)) {
            Text(
                text = snapshot.toReport(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun DebugCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun AudioOutputBlock(output: UsbAudioOutputDebugInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "${output.typeName} | ${output.productName}",
            style = MaterialTheme.typography.titleSmall
        )
        DebugRow(stringResource(AppR.string.debug_usb_exclusive_id), output.id.toString())
        DebugRow(stringResource(AppR.string.debug_usb_exclusive_usb), output.isUsbOutput.yesNoText())
        DebugRow(stringResource(AppR.string.debug_usb_exclusive_address), output.address)
        DebugRow(stringResource(AppR.string.debug_usb_exclusive_rates), output.sampleRates.joinToString().ifBlank { "[]" })
        DebugRow(stringResource(AppR.string.debug_usb_exclusive_channels), output.channelCounts.joinToString().ifBlank { "[]" })
        DebugRow(stringResource(AppR.string.debug_usb_exclusive_encodings), output.encodings.joinToString().ifBlank { "[]" })
    }
}

@Composable
private fun UsbHostDeviceBlock(device: UsbHostDeviceDebugInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "${device.productName} | ${device.vendorProductId}",
            style = MaterialTheme.typography.titleSmall
        )
        DebugRow(stringResource(AppR.string.debug_usb_exclusive_name), device.deviceName)
        DebugRow(stringResource(AppR.string.debug_usb_exclusive_manufacturer), device.manufacturerName)
        DebugRow(stringResource(AppR.string.debug_usb_exclusive_class), "${device.deviceClassName}(${device.deviceClass})")
        DebugRow(stringResource(AppR.string.debug_usb_exclusive_audio), device.hasAudioInterface.yesNoText())
        DebugRow(stringResource(AppR.string.debug_usb_exclusive_streaming), device.hasAudioStreamingInterface.yesNoText())
        DebugRow(stringResource(AppR.string.debug_usb_exclusive_permission), device.hasPermission.yesNoText())
        DebugRow(
            stringResource(AppR.string.debug_usb_exclusive_interfaces),
            device.interfaces.joinToString { it.compactLine() }.ifBlank { "[]" }
        )
    }
}

@Composable
private fun DebugRow(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun Boolean.yesNoText(): String {
    return stringResource(
        if (this) AppR.string.debug_usb_exclusive_yes else AppR.string.debug_usb_exclusive_no
    )
}

private fun copyUsbReport(
    context: Context,
    report: String
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(
        ClipData.newPlainText("USB Exclusive Diagnostics", report)
    )
}
