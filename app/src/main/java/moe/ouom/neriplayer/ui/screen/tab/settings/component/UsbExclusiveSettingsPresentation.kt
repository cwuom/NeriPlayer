package moe.ouom.neriplayer.ui.screen.tab.settings.component

import android.media.AudioFormat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnosticsSnapshot
import moe.ouom.neriplayer.core.player.usb.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.core.player.usb.UsbExclusiveNativeState

@Composable
internal fun resolveUsbStatus(
    enabled: Boolean,
    snapshot: UsbExclusiveDiagnosticsSnapshot,
    nativeState: UsbExclusiveNativeState
): UsbStatusPresentation {
    val colorScheme = MaterialTheme.colorScheme
    val waitingReason = snapshot.fallbackReason
        ?: snapshot.nativeExclusiveError
        ?: snapshot.nativeExclusiveRuntime
    return when {
        !enabled -> UsbStatusPresentation(
            title = stringResource(R.string.settings_usb_exclusive_status_disabled),
            description = stringResource(R.string.settings_usb_exclusive_status_disabled_desc),
            icon = Icons.Outlined.Info,
            color = colorScheme.onSurfaceVariant
        )
        !snapshot.hasUsbHostAudioDevice -> UsbStatusPresentation(
            title = stringResource(R.string.settings_usb_exclusive_status_no_device),
            description = stringResource(R.string.settings_usb_exclusive_status_no_device_desc),
            icon = Icons.Outlined.Usb,
            color = colorScheme.onSurfaceVariant
        )
        !snapshot.hasUsbPermission -> UsbStatusPresentation(
            title = stringResource(R.string.settings_usb_exclusive_status_permission),
            description = stringResource(R.string.settings_usb_exclusive_status_permission_desc),
            icon = Icons.Outlined.ErrorOutline,
            color = colorScheme.error
        )
        enabled && snapshot.fallbackReason.containsUsbExclusivePendingIdle() ->
            UsbStatusPresentation(
                title = stringResource(R.string.settings_usb_exclusive_status_system_fallback),
                description = stringResource(R.string.settings_usb_exclusive_issue_pending_idle),
                icon = Icons.Outlined.HourglassTop,
                color = colorScheme.tertiary
            )
        enabled && isUsbExclusiveWaitingReason(waitingReason) -> UsbStatusPresentation(
            title = stringResource(R.string.settings_usb_exclusive_status_waiting),
            description = stringResource(R.string.settings_usb_exclusive_issue_cooldown),
            icon = Icons.Outlined.HourglassTop,
            color = colorScheme.tertiary
        )
        nativeState.transitioning -> UsbStatusPresentation(
            title = stringResource(R.string.settings_usb_exclusive_status_transitioning),
            description = stringResource(R.string.settings_usb_exclusive_status_transitioning_desc),
            icon = Icons.Outlined.HourglassTop,
            color = colorScheme.tertiary
        )
        snapshot.nativeExclusiveStreaming && snapshot.nativeExclusiveSource == "player_pcm" ->
            UsbStatusPresentation(
                title = stringResource(R.string.settings_usb_exclusive_status_streaming),
                description = stringResource(R.string.settings_usb_exclusive_status_streaming_desc),
                icon = Icons.Outlined.CheckCircle,
                color = colorScheme.primary
            )
        snapshot.nativeExclusiveStreaming -> UsbStatusPresentation(
            title = stringResource(R.string.settings_usb_exclusive_status_test_tone),
            description = stringResource(R.string.settings_usb_exclusive_status_test_tone_desc),
            icon = Icons.Outlined.GraphicEq,
            color = colorScheme.tertiary
        )
        enabled &&
            snapshot.effectivePath == UsbExclusiveAudioPathState.EFFECTIVE_SYSTEM &&
            (
                snapshot.sinkPlaying ||
                    (!snapshot.fallbackReason.isNullOrBlank() && snapshot.fallbackReason != "none")
                ) ->
            UsbStatusPresentation(
                title = stringResource(R.string.settings_usb_exclusive_status_system_fallback),
                description = snapshot.fallbackReason
                    ?.takeUnless { it.isBlank() || it == "none" }
                    ?.let { usbExclusiveIssueLabel(it) }
                    ?: stringResource(R.string.settings_usb_exclusive_status_system_fallback_desc),
                icon = Icons.Outlined.HourglassTop,
                color = colorScheme.tertiary
            )
        !snapshot.nativeExclusiveError.isNullOrBlank() && snapshot.nativeExclusiveError != "none" ->
            UsbStatusPresentation(
                title = stringResource(R.string.settings_usb_exclusive_status_error),
                description = usbExclusiveIssueLabel(snapshot.nativeExclusiveError),
                icon = Icons.Outlined.ErrorOutline,
                color = colorScheme.error
            )
        nativeState.available -> UsbStatusPresentation(
            title = stringResource(R.string.settings_usb_exclusive_status_ready),
            description = stringResource(R.string.settings_usb_exclusive_status_ready_desc),
            icon = Icons.Outlined.Memory,
            color = colorScheme.secondary
        )
        else -> UsbStatusPresentation(
            title = stringResource(R.string.settings_usb_exclusive_status_unavailable),
            description = stringResource(R.string.settings_usb_exclusive_status_unavailable_desc),
            icon = Icons.Outlined.ErrorOutline,
            color = colorScheme.error
        )
    }
}

@Composable
internal fun nativeSourceLabel(source: String): String {
    return when (source) {
        "player_pcm" -> stringResource(R.string.settings_usb_exclusive_source_player)
        "tone" -> stringResource(R.string.settings_usb_exclusive_source_tone)
        else -> stringResource(R.string.settings_usb_exclusive_source_idle)
    }
}

@Composable
internal fun usbExclusiveIssueLabel(reason: String?): String {
    val normalized = reason?.trim()
        ?.takeUnless { it.isBlank() || it == "none" }
        ?: return stringResource(R.string.settings_usb_exclusive_error_none)
    return when {
        normalized.startsWith("sample_rate_unsupported") ->
            stringResource(R.string.settings_usb_exclusive_issue_sample_rate)
        normalized.startsWith("bit_depth_unsupported") ->
            stringResource(R.string.settings_usb_exclusive_issue_bit_depth)
        normalized.containsUsbExclusivePendingIdle() ->
            stringResource(R.string.settings_usb_exclusive_issue_pending_idle)
        normalized.startsWith("native_reconfiguration_cooldown") ->
            stringResource(R.string.settings_usb_exclusive_issue_cooldown)
        normalized.startsWith("native_open_deferred") ||
            normalized.startsWith("native_reopen_cooling_down") ->
            stringResource(R.string.settings_usb_exclusive_issue_cooldown)
        normalized.contains("permission", ignoreCase = true) ->
            stringResource(R.string.settings_usb_exclusive_issue_permission)
        normalized.contains("transport", ignoreCase = true) ->
            stringResource(R.string.settings_usb_exclusive_issue_transport)
        normalized.contains("usb_exclusive_disabled", ignoreCase = true) ->
            stringResource(R.string.settings_usb_exclusive_error_none)
        normalized.contains("no permitted", ignoreCase = true) ||
            normalized.startsWith("no_selected", ignoreCase = true) ||
            normalized.contains("no_compatible", ignoreCase = true) ->
            stringResource(R.string.settings_usb_exclusive_issue_device)
        else -> stringResource(R.string.settings_usb_exclusive_issue_generic)
    }
}

private fun String?.containsUsbExclusivePendingIdle(): Boolean {
    return this?.contains("pending_idle", ignoreCase = true) == true
}

private fun isUsbExclusiveWaitingReason(reason: String?): Boolean {
    val normalized = reason?.trim()?.takeUnless { it.isBlank() || it == "none" } ?: return false
    return normalized.startsWith("native_open_deferred") ||
        normalized.startsWith("native_reopen_cooling_down") ||
        normalized.startsWith("native_reconfiguration_cooldown")
}

internal fun Int.formatSampleRate(): String {
    return if (this % 1_000 == 0) {
        "${this / 1_000} kHz"
    } else {
        "${this / 1_000}.${(this % 1_000) / 100} kHz"
    }
}

internal fun Int.audioEncodingLabel(): String {
    return when (this) {
        AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
        AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM Float"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
        else -> "encoding=$this"
    }
}

internal data class UsbStatusPresentation(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val color: Color
)
