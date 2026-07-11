package moe.ouom.neriplayer.ui.screen.tab.settings.component

import android.content.Context
import android.media.AudioFormat
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.debug.UsbExclusiveDiagnosticsSnapshot
import moe.ouom.neriplayer.data.settings.MAX_USB_EXCLUSIVE_BUFFER_MS
import moe.ouom.neriplayer.data.settings.MIN_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS
import moe.ouom.neriplayer.data.settings.MIN_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS
import moe.ouom.neriplayer.data.settings.USB_EXCLUSIVE_BUFFER_STEP_MS
import moe.ouom.neriplayer.data.settings.UsbExclusiveBitDepthMode
import moe.ouom.neriplayer.data.settings.UsbExclusiveBufferProfile
import moe.ouom.neriplayer.data.settings.UsbExclusivePreferences
import moe.ouom.neriplayer.data.settings.UsbExclusiveSampleRateMode
import moe.ouom.neriplayer.data.settings.UsbExclusiveUnsupportedFormatPolicy
import moe.ouom.neriplayer.data.settings.normalizeUsbExclusiveBackgroundBufferMs
import moe.ouom.neriplayer.data.settings.normalizeUsbExclusiveForegroundBufferMs
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsChoiceRow
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

@Composable
internal fun UsbExclusiveQualityContent(
    snapshot: UsbExclusiveDiagnosticsSnapshot,
    preferences: UsbExclusivePreferences,
    onSampleRateModeChange: (UsbExclusiveSampleRateMode) -> Unit,
    onBitDepthModeChange: (UsbExclusiveBitDepthMode) -> Unit,
    onBufferProfileChange: (UsbExclusiveBufferProfile) -> Unit,
    onUnsupportedFormatPolicyChange: (UsbExclusiveUnsupportedFormatPolicy) -> Unit,
    onSampleRateCompatibilityChange: (Boolean) -> Unit,
    onBitDepthCompatibilityChange: (Boolean) -> Unit,
    onChannelCompatibilityChange: (Boolean) -> Unit,
    onForegroundBufferMsChange: (Int) -> Unit,
    onBackgroundBufferMsChange: (Int) -> Unit
) {
    val context = LocalContext.current
    var activeDialog by remember { mutableStateOf<UsbQualityDialog?>(null) }
    val sampleRateMode = preferences.sampleRateMode
    val bitDepthMode = preferences.bitDepthMode
    val bufferProfile = preferences.bufferProfile
    val unsupportedPolicy = preferences.unsupportedFormatPolicy

    UsbQualityChoiceItem(
        title = stringResource(R.string.settings_usb_exclusive_sample_rate_policy),
        value = sampleRateLabel(sampleRateMode),
        detail = sampleRateDescription(sampleRateMode),
        onClick = { activeDialog = UsbQualityDialog.SampleRate }
    )
    SettingsDivider()
    UsbQualityChoiceItem(
        title = stringResource(R.string.settings_usb_exclusive_bit_depth_policy),
        value = bitDepthLabel(bitDepthMode),
        detail = bitDepthDescription(bitDepthMode),
        onClick = { activeDialog = UsbQualityDialog.BitDepth }
    )
    SettingsDivider()
    UsbQualityChoiceItem(
        title = stringResource(R.string.settings_usb_exclusive_buffer_profile),
        value = bufferProfileLabel(bufferProfile),
        detail = bufferProfileDescription(bufferProfile),
        onClick = { activeDialog = UsbQualityDialog.BufferProfile }
    )
    SettingsDivider()
    UsbQualityChoiceItem(
        title = stringResource(R.string.settings_usb_exclusive_unsupported_policy),
        value = unsupportedPolicyLabel(unsupportedPolicy),
        detail = unsupportedPolicyDescription(unsupportedPolicy),
        onClick = { activeDialog = UsbQualityDialog.UnsupportedPolicy }
    )
    SettingsDivider()
    UsbCompatibilitySwitchItem(
        title = stringResource(R.string.settings_usb_exclusive_bit_depth_compatibility),
        detail = stringResource(R.string.settings_usb_exclusive_bit_depth_compatibility_desc),
        checked = preferences.bitDepthCompatibilityEnabled,
        onCheckedChange = onBitDepthCompatibilityChange
    )
    SettingsDivider()
    UsbCompatibilitySwitchItem(
        title = stringResource(R.string.settings_usb_exclusive_sample_rate_compatibility),
        detail = stringResource(R.string.settings_usb_exclusive_sample_rate_compatibility_desc),
        checked = preferences.sampleRateCompatibilityEnabled,
        onCheckedChange = onSampleRateCompatibilityChange
    )
    SettingsDivider()
    UsbCompatibilitySwitchItem(
        title = stringResource(R.string.settings_usb_exclusive_channel_compatibility),
        detail = stringResource(R.string.settings_usb_exclusive_channel_compatibility_desc),
        checked = preferences.channelCompatibilityEnabled,
        onCheckedChange = onChannelCompatibilityChange
    )
    SettingsDivider()
    UsbBufferSliderItem(
        title = stringResource(R.string.settings_usb_exclusive_foreground_buffer),
        bufferMs = preferences.foregroundBufferMs,
        minBufferMs = MIN_USB_EXCLUSIVE_FOREGROUND_BUFFER_MS,
        normalizeBufferMs = ::normalizeUsbExclusiveForegroundBufferMs,
        onBufferChange = onForegroundBufferMsChange
    )
    SettingsDivider()
    UsbBufferSliderItem(
        title = stringResource(R.string.settings_usb_exclusive_background_buffer),
        bufferMs = preferences.backgroundBufferMs,
        minBufferMs = MIN_USB_EXCLUSIVE_BACKGROUND_BUFFER_MS,
        normalizeBufferMs = ::normalizeUsbExclusiveBackgroundBufferMs,
        onBufferChange = onBackgroundBufferMsChange
    )
    UsbDeviceCapabilities(snapshot)

    when (activeDialog) {
        UsbQualityDialog.SampleRate -> UsbPreferenceChoiceDialog(
            title = stringResource(R.string.settings_usb_exclusive_choose_sample_rate),
            options = UsbExclusiveSampleRateMode.entries,
            selected = sampleRateMode,
            optionLabel = { sampleRateLabel(it) },
            optionDescription = { sampleRateDescription(it) },
            onSelect = { mode ->
                showUnsupportedSampleRateWarning(context, snapshot, mode)
                onSampleRateModeChange(mode)
                activeDialog = null
            },
            onDismiss = { activeDialog = null }
        )
        UsbQualityDialog.BitDepth -> UsbPreferenceChoiceDialog(
            title = stringResource(R.string.settings_usb_exclusive_choose_bit_depth),
            options = UsbExclusiveBitDepthMode.entries,
            selected = bitDepthMode,
            optionLabel = { bitDepthLabel(it) },
            optionDescription = { bitDepthDescription(it) },
            onSelect = { mode ->
                showUnsupportedBitDepthWarning(context, snapshot, mode)
                onBitDepthModeChange(mode)
                activeDialog = null
            },
            onDismiss = { activeDialog = null }
        )
        UsbQualityDialog.BufferProfile -> UsbPreferenceChoiceDialog(
            title = stringResource(R.string.settings_usb_exclusive_choose_buffer_profile),
            options = UsbExclusiveBufferProfile.entries,
            selected = bufferProfile,
            optionLabel = { bufferProfileLabel(it) },
            optionDescription = { bufferProfileDescription(it) },
            onSelect = { profile ->
                onBufferProfileChange(profile)
                activeDialog = null
            },
            onDismiss = { activeDialog = null }
        )
        UsbQualityDialog.UnsupportedPolicy -> UsbPreferenceChoiceDialog(
            title = stringResource(R.string.settings_usb_exclusive_choose_unsupported_policy),
            options = UsbExclusiveUnsupportedFormatPolicy.entries,
            selected = unsupportedPolicy,
            optionLabel = { unsupportedPolicyLabel(it) },
            optionDescription = { unsupportedPolicyDescription(it) },
            onSelect = { policy ->
                onUnsupportedFormatPolicyChange(policy)
                activeDialog = null
            },
            onDismiss = { activeDialog = null }
        )
        null -> Unit
    }
}

@Composable
private fun UsbCompatibilitySwitchItem(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        modifier = Modifier.settingsItemClickable(onClick = { onCheckedChange(!checked) }),
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            MiuixSettingsSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun UsbBufferSliderItem(
    title: String,
    bufferMs: Int,
    minBufferMs: Int,
    normalizeBufferMs: (Int) -> Int,
    onBufferChange: (Int) -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(bufferMs.toFloat()) }
    LaunchedEffect(bufferMs) {
        if (sliderValue.roundToInt() != bufferMs) {
            sliderValue = bufferMs.toFloat()
        }
    }
    val displayValue = normalizeBufferMs(sliderValue.roundToInt())
    ListItem(
        headlineContent = {
            Text(title)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(
                        R.string.settings_usb_exclusive_buffer_ms,
                        displayValue
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                MiuixSettingsSlider(
                    value = sliderValue,
                    onValueChange = { value ->
                        sliderValue = normalizeBufferMs(value.roundToInt()).toFloat()
                    },
                    valueRange = minBufferMs.toFloat()..
                        MAX_USB_EXCLUSIVE_BUFFER_MS.toFloat(),
                    steps = ((MAX_USB_EXCLUSIVE_BUFFER_MS - minBufferMs) /
                        USB_EXCLUSIVE_BUFFER_STEP_MS - 1).coerceAtLeast(0),
                    onValueChangeFinished = {
                        onBufferChange(normalizeBufferMs(sliderValue.roundToInt()))
                    }
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

private fun showUnsupportedSampleRateWarning(
    context: Context,
    snapshot: UsbExclusiveDiagnosticsSnapshot,
    mode: UsbExclusiveSampleRateMode
) {
    val requestedRate = mode.sampleRateHz ?: return
    val supportedRates = snapshot.selectedUsbOutput?.sampleRates
        ?.filter { it > 0 }
        ?.takeIf { it.isNotEmpty() }
        ?: return
    if (requestedRate in supportedRates) return
    Toast.makeText(
        context,
        context.getString(
            R.string.settings_usb_exclusive_sample_rate_not_supported,
            requestedRate.formatSampleRate()
        ),
        Toast.LENGTH_LONG
    ).show()
}

private fun showUnsupportedBitDepthWarning(
    context: Context,
    snapshot: UsbExclusiveDiagnosticsSnapshot,
    mode: UsbExclusiveBitDepthMode
) {
    val requestedBitDepth = mode.bitDepth ?: return
    val supportedBitDepths = snapshot.selectedUsbOutput?.encodings
        ?.mapNotNull(::bitDepthForAudioEncoding)
        ?.takeIf { it.isNotEmpty() }
        ?: return
    if (requestedBitDepth in supportedBitDepths) return
    Toast.makeText(
        context,
        context.getString(
            R.string.settings_usb_exclusive_bit_depth_not_supported,
            requestedBitDepth
        ),
        Toast.LENGTH_LONG
    ).show()
}

private fun bitDepthForAudioEncoding(encoding: Int): Int? {
    return when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> 16
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        AudioFormat.ENCODING_PCM_32BIT -> 32
        else -> null
    }
}

@Composable
private fun UsbDeviceCapabilities(snapshot: UsbExclusiveDiagnosticsSnapshot) {
    val output = snapshot.selectedUsbOutput
    val channelSuffix = stringResource(R.string.settings_usb_exclusive_channel_suffix)
    SettingsDivider()
    SettingsInfoItem(
        title = stringResource(R.string.settings_usb_exclusive_device_sample_rates),
        value = output?.sampleRates
            ?.takeIf(List<Int>::isNotEmpty)
            ?.joinToString(separator = ", ") { it.formatSampleRate() }
            ?: stringResource(R.string.settings_usb_exclusive_not_reported)
    )
    SettingsDivider()
    SettingsInfoItem(
        title = stringResource(R.string.settings_usb_exclusive_channel_capabilities),
        value = output?.channelCounts
            ?.takeIf(List<Int>::isNotEmpty)
            ?.joinToString(separator = ", ") { "$it $channelSuffix" }
            ?: stringResource(R.string.settings_usb_exclusive_not_reported)
    )
    SettingsDivider()
    SettingsInfoItem(
        title = stringResource(R.string.settings_usb_exclusive_encoding_capabilities),
        value = output?.encodings
            ?.takeIf(List<Int>::isNotEmpty)
            ?.joinToString(separator = ", ") { it.audioEncodingLabel() }
            ?: stringResource(R.string.settings_usb_exclusive_not_reported)
    )
}

@Composable
private fun UsbQualityChoiceItem(
    title: String,
    value: String,
    detail: String,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.settingsItemClickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f)
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun <T> UsbPreferenceChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    optionDescription: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(options) { option ->
                    MiuixSettingsChoiceRow(
                        title = optionLabel(option),
                        subtitle = optionDescription(option),
                        selected = option == selected,
                        onClick = { onSelect(option) }
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

@Composable
private fun sampleRateLabel(mode: UsbExclusiveSampleRateMode): String {
    return mode.sampleRateHz?.formatSampleRate()
        ?: stringResource(R.string.settings_usb_exclusive_sample_rate_follow_source)
}

@Composable
private fun sampleRateDescription(mode: UsbExclusiveSampleRateMode): String {
    val sampleRate = mode.sampleRateHz
    return if (sampleRate == null) {
        stringResource(R.string.settings_usb_exclusive_sample_rate_follow_source_desc)
    } else {
        stringResource(
            R.string.settings_usb_exclusive_sample_rate_fixed_desc,
            sampleRate.formatSampleRate()
        )
    }
}

@Composable
private fun bitDepthLabel(mode: UsbExclusiveBitDepthMode): String {
    return mode.bitDepth?.let {
        stringResource(R.string.settings_usb_exclusive_bit_depth_fixed, it)
    } ?: stringResource(R.string.settings_usb_exclusive_bit_depth_auto)
}

@Composable
private fun bitDepthDescription(mode: UsbExclusiveBitDepthMode): String {
    return mode.bitDepth?.let {
        stringResource(R.string.settings_usb_exclusive_bit_depth_fixed_desc, it)
    } ?: stringResource(R.string.settings_usb_exclusive_bit_depth_auto_desc)
}

@Composable
private fun bufferProfileLabel(profile: UsbExclusiveBufferProfile): String {
    return stringResource(
        when (profile) {
            UsbExclusiveBufferProfile.LOW_LATENCY ->
                R.string.settings_usb_exclusive_buffer_low_latency
            UsbExclusiveBufferProfile.BALANCED ->
                R.string.settings_usb_exclusive_buffer_balanced
            UsbExclusiveBufferProfile.STABLE ->
                R.string.settings_usb_exclusive_buffer_stable
        }
    )
}

@Composable
private fun bufferProfileDescription(profile: UsbExclusiveBufferProfile): String {
    return stringResource(
        when (profile) {
            UsbExclusiveBufferProfile.LOW_LATENCY ->
                R.string.settings_usb_exclusive_buffer_low_latency_desc
            UsbExclusiveBufferProfile.BALANCED ->
                R.string.settings_usb_exclusive_buffer_balanced_desc
            UsbExclusiveBufferProfile.STABLE ->
                R.string.settings_usb_exclusive_buffer_stable_desc
        }
    )
}

@Composable
private fun unsupportedPolicyLabel(policy: UsbExclusiveUnsupportedFormatPolicy): String {
    return stringResource(
        when (policy) {
            UsbExclusiveUnsupportedFormatPolicy.SYSTEM_FALLBACK ->
                R.string.settings_usb_exclusive_unsupported_system_fallback
            UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED ->
                R.string.settings_usb_exclusive_unsupported_closest
        }
    )
}

@Composable
private fun unsupportedPolicyDescription(policy: UsbExclusiveUnsupportedFormatPolicy): String {
    return stringResource(
        when (policy) {
            UsbExclusiveUnsupportedFormatPolicy.SYSTEM_FALLBACK ->
                R.string.settings_usb_exclusive_unsupported_system_fallback_desc
            UsbExclusiveUnsupportedFormatPolicy.CLOSEST_SUPPORTED ->
                R.string.settings_usb_exclusive_unsupported_closest_desc
        }
    )
}

private enum class UsbQualityDialog {
    SampleRate,
    BitDepth,
    BufferProfile,
    UnsupportedPolicy
}
