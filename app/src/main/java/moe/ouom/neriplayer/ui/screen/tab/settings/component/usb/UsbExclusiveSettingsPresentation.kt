package moe.ouom.neriplayer.ui.screen.tab.settings.component.usb

import moe.ouom.neriplayer.core.player.audio.icon

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
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveDiagnosticsSnapshot
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveAudioPathState
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveNativeState

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
            title = stringResource(CoreCommonR.string.settings_usb_exclusive_status_disabled),
            description = stringResource(CoreCommonR.string.settings_usb_exclusive_status_disabled_desc),
            icon = Icons.Outlined.Info,
            color = colorScheme.onSurfaceVariant
        )
        !snapshot.hasUsbHostAudioDevice -> UsbStatusPresentation(
            title = stringResource(CoreCommonR.string.settings_usb_exclusive_status_no_device),
            description = stringResource(CoreCommonR.string.settings_usb_exclusive_status_no_device_desc),
            icon = Icons.Outlined.Usb,
            color = colorScheme.onSurfaceVariant
        )
        !snapshot.hasUsbPermission -> UsbStatusPresentation(
            title = stringResource(CoreCommonR.string.settings_usb_exclusive_status_permission),
            description = stringResource(CoreCommonR.string.settings_usb_exclusive_status_permission_desc),
            icon = Icons.Outlined.ErrorOutline,
            color = colorScheme.error
        )
        enabled && snapshot.fallbackReason.containsUsbExclusivePendingIdle() ->
            UsbStatusPresentation(
                title = stringResource(CoreCommonR.string.settings_usb_exclusive_status_system_fallback),
                description = stringResource(CoreCommonR.string.settings_usb_exclusive_issue_pending_idle),
                icon = Icons.Outlined.HourglassTop,
                color = colorScheme.tertiary
            )
        enabled && isUsbExclusiveWaitingReason(waitingReason) -> UsbStatusPresentation(
            title = stringResource(CoreCommonR.string.settings_usb_exclusive_status_waiting),
            description = stringResource(CoreCommonR.string.settings_usb_exclusive_issue_cooldown),
            icon = Icons.Outlined.HourglassTop,
            color = colorScheme.tertiary
        )
        nativeState.transitioning -> UsbStatusPresentation(
            title = stringResource(CoreCommonR.string.settings_usb_exclusive_status_transitioning),
            description = stringResource(CoreCommonR.string.settings_usb_exclusive_status_transitioning_desc),
            icon = Icons.Outlined.HourglassTop,
            color = colorScheme.tertiary
        )
        snapshot.nativeExclusiveStreaming && snapshot.nativeExclusiveSource == "player_pcm" ->
            UsbStatusPresentation(
                title = stringResource(CoreCommonR.string.settings_usb_exclusive_status_streaming),
                description = stringResource(CoreCommonR.string.settings_usb_exclusive_status_streaming_desc),
                icon = Icons.Outlined.CheckCircle,
                color = colorScheme.primary
            )
        snapshot.nativeExclusiveStreaming -> UsbStatusPresentation(
            title = stringResource(CoreCommonR.string.settings_usb_exclusive_status_test_tone),
            description = stringResource(CoreCommonR.string.settings_usb_exclusive_status_test_tone_desc),
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
                title = stringResource(CoreCommonR.string.settings_usb_exclusive_status_system_fallback),
                description = snapshot.fallbackReason
                    ?.takeUnless { it.isBlank() || it == "none" }
                    ?.let { usbExclusiveIssueLabel(it) }
                    ?: stringResource(CoreCommonR.string.settings_usb_exclusive_status_system_fallback_desc),
                icon = Icons.Outlined.HourglassTop,
                color = colorScheme.tertiary
            )
        !snapshot.nativeExclusiveError.isNullOrBlank() && snapshot.nativeExclusiveError != "none" ->
            UsbStatusPresentation(
                title = stringResource(CoreCommonR.string.settings_usb_exclusive_status_error),
                description = usbExclusiveIssueLabel(snapshot.nativeExclusiveError),
                icon = Icons.Outlined.ErrorOutline,
                color = colorScheme.error
            )
        nativeState.available -> UsbStatusPresentation(
            title = stringResource(CoreCommonR.string.settings_usb_exclusive_status_ready),
            description = stringResource(CoreCommonR.string.settings_usb_exclusive_status_ready_desc),
            icon = Icons.Outlined.Memory,
            color = colorScheme.secondary
        )
        else -> UsbStatusPresentation(
            title = stringResource(CoreCommonR.string.settings_usb_exclusive_status_unavailable),
            description = stringResource(CoreCommonR.string.settings_usb_exclusive_status_unavailable_desc),
            icon = Icons.Outlined.ErrorOutline,
            color = colorScheme.error
        )
    }
}

@Composable
internal fun nativeSourceLabel(source: String): String {
    return when (source) {
        "player_pcm" -> stringResource(CoreCommonR.string.settings_usb_exclusive_source_player)
        "tone" -> stringResource(CoreCommonR.string.settings_usb_exclusive_source_tone)
        else -> stringResource(CoreCommonR.string.settings_usb_exclusive_source_idle)
    }
}

@Composable
internal fun usbExclusiveIssueLabel(reason: String?): String {
    val normalized = reason?.trim()
        ?.takeUnless { it.isBlank() || it == "none" }
        ?: return stringResource(CoreCommonR.string.settings_usb_exclusive_error_none)
    return when {
        normalized.startsWith("sample_rate_unsupported") ->
            stringResource(CoreCommonR.string.settings_usb_exclusive_issue_sample_rate)
        normalized.startsWith("bit_depth_unsupported") ->
            stringResource(CoreCommonR.string.settings_usb_exclusive_issue_bit_depth)
        normalized.containsUsbExclusivePendingIdle() ->
            stringResource(CoreCommonR.string.settings_usb_exclusive_issue_pending_idle)
        normalized.startsWith("native_reconfiguration_cooldown") ->
            stringResource(CoreCommonR.string.settings_usb_exclusive_issue_cooldown)
        normalized.startsWith("native_open_deferred") ||
            normalized.startsWith("native_reopen_cooling_down") ->
            stringResource(CoreCommonR.string.settings_usb_exclusive_issue_cooldown)
        normalized.contains("permission", ignoreCase = true) ->
            stringResource(CoreCommonR.string.settings_usb_exclusive_issue_permission)
        normalized.contains("transport", ignoreCase = true) ->
            stringResource(CoreCommonR.string.settings_usb_exclusive_issue_transport)
        normalized.contains("usb_exclusive_disabled", ignoreCase = true) ->
            stringResource(CoreCommonR.string.settings_usb_exclusive_error_none)
        normalized.contains("no permitted", ignoreCase = true) ||
            normalized.startsWith("no_selected", ignoreCase = true) ||
            normalized.contains("no_compatible", ignoreCase = true) ->
            stringResource(CoreCommonR.string.settings_usb_exclusive_issue_device)
        else -> stringResource(CoreCommonR.string.settings_usb_exclusive_issue_generic)
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

internal sealed interface UsbBitPerfectStatus {
    data object Off : UsbBitPerfectStatus
    data object Active : UsbBitPerfectStatus
    data class Resampled(val inputRate: Int, val outputRate: Int) : UsbBitPerfectStatus
    data class Truncated(val inputBits: Int, val outputBits: Int) : UsbBitPerfectStatus
    data class ChannelsMapped(val inputChannels: Int, val outputChannels: Int) : UsbBitPerfectStatus
}

/**
 * 只有原生输出格式已知时才判断；浮点解码看不出音源位深，
 * ≤24 位整数源解出的浮点能逐位还原，所以不按浮点判定截断
 */
internal fun resolveUsbBitPerfectStatus(
    bitPerfect: Boolean,
    inputFormat: String,
    outputFormat: String
): UsbBitPerfectStatus? {
    val outputRate = outputFormat.valueAfter("rate")?.toIntOrNull() ?: return null
    if (!bitPerfect) return UsbBitPerfectStatus.Off
    val inputRate = inputFormat.valueAfter("sampleRate")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    if (inputRate != outputRate) return UsbBitPerfectStatus.Resampled(inputRate, outputRate)
    val inputChannels = inputFormat.valueAfter("channels")?.toIntOrNull()
    val outputChannels = outputFormat.valueAfter("channels")?.toIntOrNull()
    if (inputChannels != null && outputChannels != null && inputChannels > outputChannels) {
        return UsbBitPerfectStatus.ChannelsMapped(inputChannels, outputChannels)
    }
    val inputBits = inputFormat.valueAfter("encoding")?.toIntOrNull()?.integerPcmBits()
    val outputBits = outputFormat.valueAfter("bits")?.toIntOrNull()
    if (inputBits != null && outputBits != null && outputBits < inputBits) {
        return UsbBitPerfectStatus.Truncated(inputBits, outputBits)
    }
    return UsbBitPerfectStatus.Active
}

@Composable
internal fun usbBitPerfectStatusLabel(status: UsbBitPerfectStatus): String = when (status) {
    UsbBitPerfectStatus.Off -> stringResource(CoreCommonR.string.settings_usb_exclusive_bit_perfect_status_off)
    UsbBitPerfectStatus.Active -> stringResource(CoreCommonR.string.settings_usb_exclusive_bit_perfect_status_active)
    is UsbBitPerfectStatus.Resampled -> stringResource(
        CoreCommonR.string.settings_usb_exclusive_bit_perfect_status_resampled,
        status.inputRate.formatSampleRate(),
        status.outputRate.formatSampleRate()
    )
    is UsbBitPerfectStatus.Truncated -> stringResource(
        CoreCommonR.string.settings_usb_exclusive_bit_perfect_status_truncated,
        status.inputBits,
        status.outputBits
    )
    is UsbBitPerfectStatus.ChannelsMapped -> stringResource(
        CoreCommonR.string.settings_usb_exclusive_bit_perfect_status_channels,
        status.inputChannels,
        status.outputChannels
    )
}

private fun Int.integerPcmBits(): Int? = when (this) {
    AudioFormat.ENCODING_PCM_8BIT -> 8
    AudioFormat.ENCODING_PCM_16BIT -> 16
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
    AudioFormat.ENCODING_PCM_32BIT -> 32
    else -> null
}

internal fun String.valueAfter(key: String): String? {
    val regex = Regex("(?:^|\\s)${Regex.escape(key)}=([^\\s]+)")
    return regex.find(this)?.groupValues?.getOrNull(1)
}

internal data class UsbStatusPresentation(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val color: Color
)
