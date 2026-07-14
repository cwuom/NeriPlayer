package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.model.MAX_PLAYBACK_LOUDNESS_GAIN_MB
import moe.ouom.neriplayer.core.player.model.MAX_PLAYBACK_PITCH
import moe.ouom.neriplayer.core.player.model.MAX_PLAYBACK_SPEED
import moe.ouom.neriplayer.core.player.model.MIN_PLAYBACK_LOUDNESS_GAIN_MB
import moe.ouom.neriplayer.core.player.model.MIN_PLAYBACK_PITCH
import moe.ouom.neriplayer.core.player.model.MIN_PLAYBACK_SPEED
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerPresets
import moe.ouom.neriplayer.core.player.model.PlaybackSoundState
import moe.ouom.neriplayer.core.player.model.formatEqualizerFrequencyLabel
import moe.ouom.neriplayer.core.player.model.formatPlaybackGainLabel
import moe.ouom.neriplayer.core.player.model.normalizePlaybackPitch
import moe.ouom.neriplayer.core.player.model.normalizePlaybackLoudnessGainMb
import moe.ouom.neriplayer.core.player.model.normalizePlaybackSpeed
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.haptic.HapticOutlinedButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val SPEED_QUICK_PRESETS = listOf(0.1f, 0.5f, 0.75f, 0.85f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
private val PITCH_QUICK_PRESETS = listOf(0.5f, 0.75f, 0.85f, 1.0f, 1.25f, 1.5f)
private val LOUDNESS_QUICK_PRESETS = listOf(0, 300, 600, 900, 1_200, 1_500)
private const val PLAYBACK_RATIO_SLIDER_STEPS = 0
private const val LOUDNESS_SLIDER_STEP_MB = 50
private const val EQUALIZER_SLIDER_STEP_MB = 50

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaybackSoundSheet(
    state: PlaybackSoundState,
    onSpeedChange: (Float, Boolean) -> Unit,
    onPitchChange: (Float, Boolean) -> Unit,
    onLoudnessGainChange: (Int, Boolean) -> Unit,
    onEqualizerEnabledChange: (Boolean) -> Unit,
    onPresetSelected: (String) -> Unit,
    onBandLevelChange: (Int, Int, Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

    val presets = buildList {
        if (state.presetId == PlaybackEqualizerPresetId.CUSTOM) {
            add(PlaybackChipData(PlaybackEqualizerPresetId.CUSTOM, "Custom"))
        }
        addAll(PlaybackEqualizerPresets.map { PlaybackChipData(it.id, it.label) })
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.9f)
            .bottomSheetScrollGuard { scrollState.value == 0 }
            .verticalScroll(scrollState)
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .windowInsetsPadding(WindowInsets.navigationBars),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.nowplaying_audio_effects_title),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = androidx.compose.ui.res.stringResource(R.string.nowplaying_audio_effects_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        PlaybackControlCard(
            title = androidx.compose.ui.res.stringResource(R.string.nowplaying_playback_speed),
            valueLabel = formatMultiplier(state.speed),
            quickPresets = SPEED_QUICK_PRESETS,
            currentValue = state.speed,
            range = MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED,
            steps = PLAYBACK_RATIO_SLIDER_STEPS,
            normalize = ::normalizePlaybackSpeed,
            onValueChange = onSpeedChange
        )

        PlaybackControlCard(
            title = androidx.compose.ui.res.stringResource(R.string.nowplaying_playback_pitch),
            valueLabel = formatMultiplier(state.pitch),
            quickPresets = PITCH_QUICK_PRESETS,
            currentValue = state.pitch,
            range = MIN_PLAYBACK_PITCH..MAX_PLAYBACK_PITCH,
            steps = PLAYBACK_RATIO_SLIDER_STEPS,
            normalize = ::normalizePlaybackPitch,
            onValueChange = onPitchChange
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                var loudnessSliderValue by remember(state.loudnessGainMb) {
                    mutableIntStateOf(state.loudnessGainMb)
                }
                var showLoudnessInputDialog by remember { mutableStateOf(false) }
                if (showLoudnessInputDialog) {
                    PlaybackDecimalValueInputDialog(
                        title = androidx.compose.ui.res.stringResource(R.string.nowplaying_loudness_enhancer),
                        initialText = formatInputDecimal(loudnessSliderValue / 100f, fractionDigits = 1),
                        rangeStartText = formatPlaybackGainLabel(MIN_PLAYBACK_LOUDNESS_GAIN_MB),
                        rangeEndText = formatPlaybackGainLabel(MAX_PLAYBACK_LOUDNESS_GAIN_MB),
                        parseValue = ::parseDbInput,
                        isValueInRange = { value ->
                            val gainMb = (value * 100f).roundToInt()
                            gainMb in MIN_PLAYBACK_LOUDNESS_GAIN_MB..MAX_PLAYBACK_LOUDNESS_GAIN_MB
                        },
                        onValueCommit = { value ->
                            loudnessSliderValue = normalizePlaybackLoudnessGainMb((value * 100f).roundToInt())
                            onLoudnessGainChange(loudnessSliderValue, true)
                        },
                        onDismiss = { showLoudnessInputDialog = false }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.nowplaying_loudness_enhancer),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (state.audioSessionId == null) {
                                androidx.compose.ui.res.stringResource(R.string.nowplaying_equalizer_wait_for_session)
                            } else if (!state.loudnessEnhancerAvailable) {
                                androidx.compose.ui.res.stringResource(R.string.nowplaying_loudness_unsupported)
                            } else {
                                androidx.compose.ui.res.stringResource(R.string.nowplaying_loudness_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = formatPlaybackGainLabel(state.loudnessGainMb),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showLoudnessInputDialog = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = loudnessSliderValue.toFloat(),
                    onValueChange = { raw ->
                        val normalized = ((raw / LOUDNESS_SLIDER_STEP_MB).roundToInt() * LOUDNESS_SLIDER_STEP_MB)
                            .coerceIn(
                                minimumValue = MIN_PLAYBACK_LOUDNESS_GAIN_MB,
                                maximumValue = MAX_PLAYBACK_LOUDNESS_GAIN_MB
                            )
                        loudnessSliderValue = normalizePlaybackLoudnessGainMb(normalized)
                        onLoudnessGainChange(loudnessSliderValue, false)
                    },
                    onValueChangeFinished = {
                        onLoudnessGainChange(loudnessSliderValue, true)
                    },
                    valueRange = MIN_PLAYBACK_LOUDNESS_GAIN_MB.toFloat()..MAX_PLAYBACK_LOUDNESS_GAIN_MB.toFloat(),
                    steps = buildDiscreteSliderSteps(
                        range = MIN_PLAYBACK_LOUDNESS_GAIN_MB..MAX_PLAYBACK_LOUDNESS_GAIN_MB,
                        stepSize = LOUDNESS_SLIDER_STEP_MB
                    )
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LOUDNESS_QUICK_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = loudnessSliderValue == preset,
                            onClick = {
                                loudnessSliderValue = preset
                                onLoudnessGainChange(loudnessSliderValue, true)
                            },
                            label = { Text(formatPlaybackGainLabel(preset)) }
                        )
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.nowplaying_equalizer),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.nowplaying_equalizer_presets),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = state.equalizerEnabled,
                        onCheckedChange = onEqualizerEnabledChange
                    )
                }

                val infoText = when {
                    state.audioSessionId == null ->
                        androidx.compose.ui.res.stringResource(R.string.nowplaying_equalizer_wait_for_session)
                    state.equalizerEnabled && !state.equalizerAvailable ->
                        androidx.compose.ui.res.stringResource(R.string.nowplaying_equalizer_unsupported)
                    else ->
                        androidx.compose.ui.res.stringResource(R.string.nowplaying_audio_effects_desc)
                }
                Text(
                    text = infoText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = state.presetId == preset.id,
                            onClick = { onPresetSelected(preset.id) },
                            label = { Text(preset.label) }
                        )
                    }
                }

                HorizontalDivider()

                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.nowplaying_equalizer_manual_bands),
                    style = MaterialTheme.typography.titleSmall
                )

                state.bands.forEach { band ->
                    var bandSliderValue by remember(band.index, band.levelMb) {
                        mutableIntStateOf(band.levelMb)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatEqualizerFrequencyLabel(band.centerFreqHz),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = formatBandLevelDb(band.levelMb),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                        Slider(
                            value = bandSliderValue.toFloat(),
                            onValueChange = { raw ->
                                val normalized = ((raw / EQUALIZER_SLIDER_STEP_MB).roundToInt() * EQUALIZER_SLIDER_STEP_MB)
                                    .coerceIn(
                                        minimumValue = state.bandLevelRangeMb.first,
                                        maximumValue = state.bandLevelRangeMb.last
                                    )
                                bandSliderValue = normalized
                                onBandLevelChange(band.index, bandSliderValue, false)
                            },
                            onValueChangeFinished = {
                                onBandLevelChange(band.index, bandSliderValue, true)
                            },
                            valueRange = state.bandLevelRangeMb.first.toFloat()..state.bandLevelRangeMb.last.toFloat(),
                            steps = buildDiscreteSliderSteps(
                                range = state.bandLevelRangeMb,
                                stepSize = EQUALIZER_SLIDER_STEP_MB
                            )
                        )
                    }
                }
            }
        }

        HapticOutlinedButton(
            onClick = onReset,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(stringResource(R.string.nowplaying_audio_effects_reset))
        }

        Spacer(modifier = Modifier.height(4.dp))

        HapticTextButton(
            onClick = onDismiss,
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(stringResource(R.string.action_done))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackControlCard(
    title: String,
    valueLabel: String,
    quickPresets: List<Float>,
    currentValue: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    normalize: (Float) -> Float,
    onValueChange: (Float, Boolean) -> Unit
) {
    var sliderValue by remember(currentValue) {
        mutableFloatStateOf(currentValue)
    }
    var showInputDialog by remember { mutableStateOf(false) }
    if (showInputDialog) {
        PlaybackDecimalValueInputDialog(
            title = title,
            initialText = formatInputDecimal(sliderValue, fractionDigits = 2),
            rangeStartText = formatMultiplier(range.start),
            rangeEndText = formatMultiplier(range.endInclusive),
            parseValue = ::parseMultiplierInput,
            isValueInRange = { it in range },
            onValueCommit = { value ->
                sliderValue = normalize(value)
                onValueChange(sliderValue, true)
            },
            onDismiss = { showInputDialog = false }
        )
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = valueLabel,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showInputDialog = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = normalize(it)
                    onValueChange(sliderValue, false)
                },
                onValueChangeFinished = {
                    onValueChange(sliderValue, true)
                },
                valueRange = range,
                steps = steps
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickPresets.forEach { preset ->
                    val normalizedPreset = normalize(preset)
                    FilterChip(
                        selected = abs(sliderValue - normalizedPreset) < 0.001f,
                        onClick = {
                            sliderValue = normalizedPreset
                            onValueChange(sliderValue, true)
                        },
                        label = {
                            Text(
                                text = formatMultiplier(preset),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaybackDecimalValueInputDialog(
    title: String,
    initialText: String,
    rangeStartText: String,
    rangeEndText: String,
    parseValue: (String) -> Float?,
    isValueInRange: (Float) -> Boolean,
    onValueCommit: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var inputText by remember(title, initialText) {
        mutableStateOf(initialText)
    }
    val parsedValue = parseValue(inputText)
    val errorText = when {
        inputText.isBlank() -> null
        parsedValue == null -> stringResource(R.string.nowplaying_value_input_invalid)
        !isValueInRange(parsedValue) -> stringResource(
            R.string.nowplaying_value_input_range,
            rangeStartText,
            rangeEndText
        )
        else -> null
    }
    val canCommit = parsedValue != null && isValueInRange(parsedValue)
    val commitValue = {
        val value = parsedValue
        if (value != null && isValueInRange(value)) {
            onValueCommit(value)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.nowplaying_value_input_label)) },
                    singleLine = true,
                    isError = errorText != null,
                    supportingText = {
                        Text(
                            text = errorText ?: stringResource(
                                R.string.nowplaying_value_input_range,
                                rangeStartText,
                                rangeEndText
                            )
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        if (canCommit) commitValue()
                    })
                )
            }
        },
        confirmButton = {
            HapticTextButton(
                onClick = commitValue,
                enabled = canCommit
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            HapticTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

private fun buildDiscreteSliderSteps(range: IntRange, stepSize: Int): Int {
    val rawSteps = ((range.last - range.first) / stepSize) - 1
    return rawSteps.coerceAtLeast(0)
}

private fun parseMultiplierInput(raw: String): Float? {
    return raw.trim()
        .removeSuffix("x")
        .removeSuffix("X")
        .removeSuffix("×")
        .trim()
        .toFloatOrNull()
}

private fun parseDbInput(raw: String): Float? {
    return raw.trim()
        .removePrefix("+")
        .removeSuffix("dB")
        .removeSuffix("db")
        .removeSuffix("DB")
        .trim()
        .toFloatOrNull()
}

private fun formatInputDecimal(value: Float, fractionDigits: Int): String {
    return String.format(Locale.US, "%.${fractionDigits}f", value)
}

private fun formatMultiplier(value: Float): String {
    return String.format(Locale.US, "%.2fx", value)
}

private fun formatBandLevelDb(levelMb: Int): String {
    return String.format(Locale.US, "%+.1f dB", levelMb / 100f)
}

private data class PlaybackChipData(
    val id: String,
    val label: String
)
