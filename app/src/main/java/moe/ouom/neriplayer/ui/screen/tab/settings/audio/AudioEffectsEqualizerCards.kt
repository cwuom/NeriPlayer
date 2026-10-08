package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.playback.effects.AUDIO_EFFECTS_EQ_BAND_LIMIT_DB
import moe.ouom.neriplayer.data.model.playback.effects.AUDIO_EFFECTS_PARAMETRIC_BAND_LIMIT
import moe.ouom.neriplayer.data.model.playback.effects.AUDIO_EFFECTS_PREAMP_MAX_DB
import moe.ouom.neriplayer.data.model.playback.effects.AUDIO_EFFECTS_PREAMP_MIN_DB
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsGraphicBandFrequenciesHz
import moe.ouom.neriplayer.data.model.playback.effects.AutoEqImportResult
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettings
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSound
import moe.ouom.neriplayer.data.model.playback.effects.ParametricEqBand
import moe.ouom.neriplayer.data.model.playback.effects.ParametricEqBandType
import moe.ouom.neriplayer.data.model.playback.effects.normalized
import moe.ouom.neriplayer.data.model.playback.effects.parseAutoEqText
import moe.ouom.neriplayer.data.model.playback.effects.withAutoEqImport
import moe.ouom.neriplayer.ui.feedback.AppFeedback
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextField
import java.util.Locale

@Composable
internal fun AudioEffectsEqualizerCard(
    sound: AudioEffectsSound,
    settings: AudioEffectsSettings,
    editor: AudioEffectsEditor
) {
    AudioEffectsCardIntro(
        title = stringResource(CoreCommonR.string.audio_effects_eq_title),
        description = stringResource(CoreCommonR.string.audio_effects_eq_desc)
    )
    var selectedBand by rememberSaveable { mutableIntStateOf(DEFAULT_SELECTED_BAND) }
    val changeBand: (Int, Float) -> Unit = { index, gain ->
        editor.sound { current ->
            val bands = current.equalizerBandsDb.toMutableList()
            if (index in bands.indices) bands[index] = gain
            current.copy(equalizerEnabled = true, equalizerBandsDb = bands)
        }
    }
    AudioEffectsEqualizerGraph(
        sound = sound,
        selectedBand = selectedBand,
        onSelectBand = { selectedBand = it },
        onBandChange = changeBand,
        modifier = Modifier.padding(vertical = 4.dp)
    )
    EqualizerBandStrip(
        bands = sound.equalizerBandsDb,
        selected = selectedBand,
        onSelect = { selectedBand = it },
        onChange = { gain -> changeBand(selectedBand, gain) }
    )
    AudioEffectsNote(stringResource(CoreCommonR.string.audio_effects_eq_hint))
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_preamp),
        description = stringResource(CoreCommonR.string.audio_effects_preamp_desc),
        value = sound.preampDb,
        valueRange = AUDIO_EFFECTS_PREAMP_MIN_DB..AUDIO_EFFECTS_PREAMP_MAX_DB,
        step = 0.5f,
        formatValue = ::formatSignedDb,
        input = AudioEffectsInputs.Db,
        onValueChange = { value -> editor.sound { it.copy(preampDb = value) } }
    )
    AudioEffectsSwitchRow(
        title = stringResource(CoreCommonR.string.audio_effects_auto_headroom),
        description = stringResource(CoreCommonR.string.audio_effects_auto_headroom_desc),
        checked = settings.autoHeadroom,
        onCheckedChange = { enabled -> editor.settings { it.copy(autoHeadroom = enabled) } }
    )
}

private const val DEFAULT_SELECTED_BAND = 5

/** 选中频段的精细调节：左右切换频段，加减按 0.5 dB 步进，点数值可直接输入 */
@Composable
private fun EqualizerBandStrip(
    bands: List<Float>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onChange: (Float) -> Unit
) {
    val index = selected.coerceIn(bands.indices)
    val gain = bands.getOrElse(index) { 0f }
    val frequency = formatFrequency(AudioEffectsGraphicBandFrequenciesHz[index])
    val limit = AUDIO_EFFECTS_EQ_BAND_LIMIT_DB
    val active = LocalAudioEffectsSectionEnabled.current
    var editing by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { onSelect(index - 1) }, enabled = index > 0) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                contentDescription = stringResource(CoreCommonR.string.audio_effects_eq_band_previous)
            )
        }
        Text(
            text = frequency,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp)
        )
        IconButton(onClick = { onSelect(index + 1) }, enabled = index < bands.lastIndex) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(CoreCommonR.string.audio_effects_eq_band_next)
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onChange((gain - BAND_STEP_DB).coerceIn(-limit, limit)) }, enabled = active && gain > -limit) {
            Icon(Icons.Outlined.Remove, contentDescription = stringResource(CoreCommonR.string.audio_effects_eq_band_decrease))
        }
        AudioEffectsValueChip(text = formatSignedDb(gain), editable = active, onClick = { editing = true })
        IconButton(onClick = { onChange((gain + BAND_STEP_DB).coerceIn(-limit, limit)) }, enabled = active && gain < limit) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(CoreCommonR.string.audio_effects_eq_band_increase))
        }
    }
    if (editing) {
        AudioEffectsValueInputDialog(
            title = frequency,
            value = gain,
            valueRange = -limit..limit,
            input = AudioEffectsInputs.Db,
            onDismiss = { editing = false },
            onConfirm = { typed ->
                editing = false
                onChange(typed)
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AudioEffectsCorrectionCard(sound: AudioEffectsSound, editor: AudioEffectsEditor) {
    val context = LocalContext.current
    val active = LocalAudioEffectsSectionEnabled.current
    var showImport by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    AudioEffectsCardIntro(
        title = stringResource(CoreCommonR.string.audio_effects_peq_title),
        description = stringResource(CoreCommonR.string.audio_effects_peq_desc)
    )
    AudioEffectsSwitchRow(
        title = stringResource(CoreCommonR.string.audio_effects_peq_enabled),
        description = null,
        checked = sound.parametricEnabled,
        onCheckedChange = { enabled -> editor.sound { it.copy(parametricEnabled = enabled) } }
    )
    if (sound.parametricBands.isEmpty()) {
        AudioEffectsNote(stringResource(CoreCommonR.string.audio_effects_peq_empty))
    }
    sound.parametricBands.forEachIndexed { index, band ->
        ParametricBandRow(band = band, enabled = active, onClick = { editingIndex = index })
    }
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MiuixSettingsOutlinedButton(onClick = { showImport = true }, enabled = active) {
            Text(stringResource(CoreCommonR.string.audio_effects_peq_import))
        }
        MiuixSettingsOutlinedButton(
            onClick = {
                editor.sound {
                    it.copy(parametricEnabled = true, parametricBands = it.parametricBands + ParametricEqBand())
                }
                editingIndex = sound.parametricBands.size
            },
            enabled = active && sound.parametricBands.size < AUDIO_EFFECTS_PARAMETRIC_BAND_LIMIT
        ) {
            Text(stringResource(CoreCommonR.string.audio_effects_peq_add))
        }
        if (sound.parametricBands.isNotEmpty()) {
            MiuixSettingsOutlinedButton(onClick = {
                editor.sound { it.copy(parametricBands = emptyList(), parametricEnabled = false) }
            }, enabled = active) {
                Text(stringResource(CoreCommonR.string.audio_effects_peq_clear))
            }
        }
    }
    if (showImport) {
        AutoEqImportDialog(
            onDismiss = { showImport = false },
            onImport = { text ->
                val result = parseAutoEqText(text)
                val message = autoEqImportMessage(context, result)
                if (result != AutoEqImportResult.Invalid) {
                    editor.sound { it.withAutoEqImport(result) }
                    showImport = false
                }
                AppFeedback.showToast(context = context, message = message)
            }
        )
    }
    val index = editingIndex
    val editingBand = index?.let { sound.parametricBands.getOrNull(it) }
    if (index != null && editingBand != null) {
        ParametricBandDialog(
            band = editingBand,
            onDismiss = { editingIndex = null },
            onChange = { updated ->
                editor.sound { current ->
                    val bands = current.parametricBands.toMutableList()
                    if (index in bands.indices) bands[index] = updated.normalized()
                    current.copy(parametricEnabled = true, parametricBands = bands)
                }
            },
            onDelete = {
                editor.sound { current ->
                    current.copy(parametricBands = current.parametricBands.filterIndexed { i, _ -> i != index })
                }
                editingIndex = null
            }
        )
    }
}

private fun autoEqImportMessage(context: android.content.Context, result: AutoEqImportResult): String = when (result) {
    is AutoEqImportResult.Parametric -> if (result.skippedFilters > 0) {
        context.getString(CoreCommonR.string.audio_effects_peq_import_skipped, result.bands.size, result.skippedFilters)
    } else {
        context.getString(CoreCommonR.string.audio_effects_peq_import_done, result.bands.size)
    }
    is AutoEqImportResult.Graphic -> context.getString(CoreCommonR.string.audio_effects_peq_import_graphic_done)
    AutoEqImportResult.Invalid -> context.getString(CoreCommonR.string.audio_effects_peq_import_failed)
}

@Composable
private fun ParametricBandRow(band: ParametricEqBand, enabled: Boolean, onClick: () -> Unit) {
    val type = ParametricEqBandType.fromStorageValue(band.type)
    ListItem(
        modifier = Modifier.settingsItemClickable(enabled = enabled, onClick = onClick),
        headlineContent = { Text(stringResource(type.labelRes())) },
        supportingContent = {
            Text(
                stringResource(
                    CoreCommonR.string.audio_effects_peq_band_summary,
                    formatFrequency(band.frequencyHz),
                    if (type.usesGain) formatSignedDb(band.gainDb) else "—",
                    String.format(Locale.US, "%.2f", band.q)
                )
            )
        },
        trailingContent = if (!band.enabled) {
            { Text(stringResource(CoreCommonR.string.audio_effects_off)) }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun AutoEqImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.audio_effects_peq_import)) },
        text = {
            MiuixSettingsTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 4,
                maxLines = 10,
                placeholder = { Text(stringResource(CoreCommonR.string.audio_effects_peq_import_hint)) }
            )
        },
        confirmButton = {
            MiuixSettingsTextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(CoreCommonR.string.action_confirm))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParametricBandDialog(
    band: ParametricEqBand,
    onDismiss: () -> Unit,
    onChange: (ParametricEqBand) -> Unit,
    onDelete: () -> Unit
) {
    val type = ParametricEqBandType.fromStorageValue(band.type)
    MiuixSettingsDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.audio_effects_peq_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AudioEffectsSwitchRow(
                    title = stringResource(CoreCommonR.string.audio_effects_peq_band_enabled),
                    description = null,
                    checked = band.enabled,
                    onCheckedChange = { onChange(band.copy(enabled = it)) }
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ParametricEqBandType.entries.forEach { option ->
                        FilterChip(
                            selected = option == type,
                            onClick = { onChange(band.copy(type = option.storageValue)) },
                            label = { Text(stringResource(option.labelRes())) }
                        )
                    }
                }
                AudioEffectsSliderRow(
                    title = stringResource(CoreCommonR.string.audio_effects_peq_frequency),
                    value = frequencyToSlider(band.frequencyHz),
                    valueRange = 0f..1f,
                    formatValue = { formatFrequency(sliderToFrequency(it)) },
                    input = AudioEffectsInputs.LogFrequency,
                    onValueChange = { onChange(band.copy(frequencyHz = sliderToFrequency(it))) }
                )
                if (type.usesGain) {
                    AudioEffectsSliderRow(
                        title = stringResource(CoreCommonR.string.audio_effects_peq_gain),
                        value = band.gainDb,
                        valueRange = -AUDIO_EFFECTS_EQ_BAND_LIMIT_DB..AUDIO_EFFECTS_EQ_BAND_LIMIT_DB,
                        step = 0.1f,
                        formatValue = ::formatSignedDb,
                        input = AudioEffectsInputs.Db,
                        onValueChange = { onChange(band.copy(gainDb = it)) }
                    )
                }
                AudioEffectsSliderRow(
                    title = stringResource(CoreCommonR.string.audio_effects_peq_q),
                    value = band.q,
                    valueRange = 0.1f..10f,
                    step = 0.05f,
                    formatValue = { String.format(Locale.US, "%.2f", it) },
                    input = AudioEffectsInputs.Plain,
                    onValueChange = { onChange(band.copy(q = it.coerceAtLeast(0.1f))) }
                )
            }
        },
        confirmButton = {
            MiuixSettingsTextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_done))
            }
        },
        dismissButton = {
            MiuixSettingsTextButton(onClick = onDelete) {
                Text(stringResource(CoreCommonR.string.audio_effects_delete_preset))
            }
        }
    )
}

private const val MIN_FREQUENCY_HZ = 20f
private const val MAX_FREQUENCY_HZ = 20_000f

/** 频率滑块按对数刻度，低频区域也能精确调节 */
internal fun frequencyToSlider(frequencyHz: Float): Float {
    val clamped = frequencyHz.coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)
    return (kotlin.math.ln(clamped / MIN_FREQUENCY_HZ) / kotlin.math.ln(MAX_FREQUENCY_HZ / MIN_FREQUENCY_HZ))
}

internal fun sliderToFrequency(position: Float): Float {
    val ratio = position.coerceIn(0f, 1f)
    val raw = MIN_FREQUENCY_HZ * kotlin.math.exp(ratio * kotlin.math.ln(MAX_FREQUENCY_HZ / MIN_FREQUENCY_HZ))
    return if (raw >= 1_000f) kotlin.math.round(raw / 10f) * 10f else kotlin.math.round(raw)
}
