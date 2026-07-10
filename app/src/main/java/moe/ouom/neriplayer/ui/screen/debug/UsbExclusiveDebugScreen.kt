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
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.debug.UsbAudioOutputDebugInfo
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnostics
import moe.ouom.neriplayer.core.player.debug.UsbHostDeviceDebugInfo
import moe.ouom.neriplayer.core.player.usb.UsbExclusiveSessionController
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.util.NPLogger

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
            text = stringResource(R.string.debug_usb_exclusive_title),
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
                    contentDescription = stringResource(R.string.action_refresh)
                )
                Text(stringResource(R.string.action_refresh))
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
                    contentDescription = stringResource(R.string.debug_usb_exclusive_copy_report)
                )
                Text(stringResource(R.string.debug_usb_exclusive_copy_report))
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
                contentDescription = stringResource(R.string.debug_usb_exclusive_request_permission)
            )
            Text(stringResource(R.string.debug_usb_exclusive_request_permission))
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
                    contentDescription = stringResource(R.string.debug_usb_exclusive_start_native_tone)
                )
                Text(stringResource(R.string.debug_usb_exclusive_start_native_tone))
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
                    contentDescription = stringResource(R.string.debug_usb_exclusive_stop_native_tone)
                )
                Text(stringResource(R.string.debug_usb_exclusive_stop_native_tone))
            }
        }

        if (copied) {
            Text(
                text = stringResource(R.string.log_copied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        DebugCard(title = stringResource(R.string.debug_usb_exclusive_section_summary)) {
            DebugRow(stringResource(R.string.debug_usb_exclusive_mode), snapshot.systemRouteSummary)
            DebugRow(
                stringResource(R.string.debug_usb_exclusive_limitation),
                snapshot.systemRouteLimitation
            )
            DebugRow(
                stringResource(R.string.settings_usb_exclusive_playback),
                snapshot.usbExclusivePlaybackEnabled.yesNoText()
            )
            DebugRow(
                stringResource(R.string.settings_allow_mixed_playback),
                snapshot.allowMixedPlaybackEnabled.yesNoText()
            )
            DebugRow(
                stringResource(R.string.debug_usb_exclusive_player_state),
                "init=${snapshot.playerInitialized}, playing=${snapshot.playerPlaying}"
            )
            DebugRow(
                stringResource(R.string.debug_usb_exclusive_current_output),
                "${snapshot.currentPlayerDeviceType ?: "none"}:${snapshot.currentPlayerDeviceName ?: "none"}"
            )
            DebugRow(
                stringResource(R.string.debug_usb_exclusive_last_permission),
                snapshot.lastPermissionEvent?.compactLine() ?: "none"
            )
        }

        DebugCard(title = stringResource(R.string.debug_usb_exclusive_section_native_runtime)) {
            DebugRow("Native", snapshot.nativeExclusiveSummary)
            DebugRow("Available", nativeState.available.yesNoText())
            DebugRow("Opened", nativeState.opened.yesNoText())
            DebugRow("Streaming", nativeState.streaming.yesNoText())
            DebugRow("Paused", nativeState.paused.yesNoText())
            DebugRow("Transitioning", nativeState.transitioning.yesNoText())
            DebugRow("Source", nativeState.source)
            DebugRow("Device", nativeState.selectedDeviceName ?: "none")
            DebugRow("Requested path", snapshot.requestedPath)
            DebugRow("Effective path", snapshot.effectivePath)
            DebugRow("Fallback reason", snapshot.fallbackReason ?: "none")
            DebugRow("Input format", nativeState.inputFormat)
            DebugRow("Output format", nativeState.outputFormat)
            DebugRow("Buffer", "${nativeState.bufferDurationMs}ms")
            DebugRow("Completed frames", nativeState.completedAudioFrames.toString())
            DebugRow("Queued frames", nativeState.queuedAudioFrames.toString())
            DebugRow("Runtime", nativeState.runtimeReport)
            DebugRow("Error", nativeState.lastError ?: "none")
        }

        DebugCard(title = stringResource(R.string.debug_usb_exclusive_section_audio_outputs)) {
            if (snapshot.audioOutputs.isEmpty()) {
                Text(
                    text = stringResource(R.string.debug_usb_exclusive_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                snapshot.audioOutputs.forEach { output ->
                    AudioOutputBlock(output)
                }
            }
        }

        DebugCard(title = stringResource(R.string.debug_usb_exclusive_section_usb_host)) {
            if (snapshot.usbHostDevices.isEmpty()) {
                Text(
                    text = stringResource(R.string.debug_usb_exclusive_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                snapshot.usbHostDevices.forEach { device ->
                    UsbHostDeviceBlock(device)
                }
            }
        }

        DebugCard(title = stringResource(R.string.debug_usb_exclusive_section_report)) {
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
        DebugRow("ID", output.id.toString())
        DebugRow("USB", output.isUsbOutput.yesNoText())
        DebugRow("Address", output.address)
        DebugRow("Rates", output.sampleRates.joinToString().ifBlank { "[]" })
        DebugRow("Channels", output.channelCounts.joinToString().ifBlank { "[]" })
        DebugRow("Encodings", output.encodings.joinToString().ifBlank { "[]" })
    }
}

@Composable
private fun UsbHostDeviceBlock(device: UsbHostDeviceDebugInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "${device.productName} | ${device.vendorProductId}",
            style = MaterialTheme.typography.titleSmall
        )
        DebugRow("Name", device.deviceName)
        DebugRow("Manufacturer", device.manufacturerName)
        DebugRow("Class", "${device.deviceClassName}(${device.deviceClass})")
        DebugRow("Audio", device.hasAudioInterface.yesNoText())
        DebugRow("Streaming", device.hasAudioStreamingInterface.yesNoText())
        DebugRow("Permission", device.hasPermission.yesNoText())
        DebugRow(
            "Interfaces",
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

private fun Boolean.yesNoText(): String {
    return if (this) "yes" else "no"
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
