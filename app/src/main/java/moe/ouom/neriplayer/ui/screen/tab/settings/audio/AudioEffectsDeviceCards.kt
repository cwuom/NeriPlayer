package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.playback.MAX_PLAYBACK_PITCH
import moe.ouom.neriplayer.data.model.playback.MIN_PLAYBACK_PITCH
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundState
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsPowerMode
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsProfile
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettings
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute
import moe.ouom.neriplayer.data.model.playback.effects.SpeakerSize
import moe.ouom.neriplayer.data.model.playback.effects.effectiveProtectionHz
import moe.ouom.neriplayer.data.model.playback.pitchToSemitoneOffset
import moe.ouom.neriplayer.data.model.playback.semitoneOffsetToPitch
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSegmentedTabs
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton

private val SpeedQuickValues = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val PitchQuickSemitones = listOf(-3f, -1f, 0f, 1f, 3f)
private const val MIN_SPEED_SLIDER = 0.25f
private const val MAX_SPEED_SLIDER = 3f

@Composable
internal fun AudioEffectsSpeakerCard(settings: AudioEffectsSettings, route: AudioOutputRoute, editor: AudioEffectsEditor) {
    val speaker = settings.speaker
    AudioEffectsCardIntro(
        title = stringResource(CoreCommonR.string.audio_effects_speaker_title),
        description = stringResource(CoreCommonR.string.audio_effects_speaker_desc)
    )
    AudioEffectsSwitchRow(
        title = stringResource(CoreCommonR.string.audio_effects_speaker_enabled),
        description = if (speaker.enabled && route != AudioOutputRoute.SPEAKER) {
            stringResource(CoreCommonR.string.audio_effects_speaker_inactive)
        } else {
            null
        },
        checked = speaker.enabled,
        onCheckedChange = { enabled -> editor.speaker { it.copy(enabled = enabled) } }
    )
    if (!speaker.enabled) return
    val sizes = SpeakerSize.entries
    AudioEffectsNote(stringResource(CoreCommonR.string.audio_effects_speaker_size))
    MiuixSettingsSegmentedTabs(
        labels = sizes.map { stringResource(it.labelRes()) },
        selectedIndex = sizes.indexOf(SpeakerSize.fromStorageValue(speaker.size)),
        onSelectedIndexChange = { index -> editor.speaker { it.copy(size = sizes[index].storageValue) } },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_speaker_bass),
        description = stringResource(CoreCommonR.string.audio_effects_speaker_bass_desc),
        value = speaker.bassHarmonics,
        valueRange = 0f..1f,
        step = 0.05f,
        formatValue = ::formatPercent,
        onValueChange = { value -> editor.speaker { it.copy(bassHarmonics = value) } }
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_speaker_loudness),
        description = stringResource(CoreCommonR.string.audio_effects_speaker_loudness_desc),
        value = speaker.loudness,
        valueRange = 0f..1f,
        step = 0.05f,
        formatValue = ::formatPercent,
        onValueChange = { value -> editor.speaker { it.copy(loudness = value) } }
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_speaker_clarity),
        value = speaker.clarity,
        valueRange = 0f..1f,
        step = 0.05f,
        formatValue = ::formatPercent,
        onValueChange = { value -> editor.speaker { it.copy(clarity = value) } }
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_speaker_expand),
        value = speaker.stereoExpand,
        valueRange = 0f..1f,
        step = 0.05f,
        formatValue = ::formatPercent,
        onValueChange = { value -> editor.speaker { it.copy(stereoExpand = value) } }
    )
    val auto = stringResource(CoreCommonR.string.audio_effects_auto)
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_speaker_protection),
        description = stringResource(CoreCommonR.string.audio_effects_speaker_protection_desc),
        value = if (speaker.protectionHz >= 40f) speaker.protectionHz else 0f,
        valueRange = 0f..400f,
        step = 10f,
        formatValue = { value ->
            if (value < 40f) "$auto · ${formatFrequency(speaker.copy(protectionHz = 0f).effectiveProtectionHz())}"
            else formatFrequency(value)
        },
        onValueChange = { value -> editor.speaker { it.copy(protectionHz = if (value < 40f) 0f else value) } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AudioEffectsSpeedCard(
    soundState: PlaybackSoundState,
    settings: AudioEffectsSettings,
    usbExclusive: Boolean,
    editor: AudioEffectsEditor
) {
    val linked = settings.speedPitchLinked
    val applySpeed: (Float) -> Unit = { speed ->
        PlayerManager.setPlaybackSpeed(speed)
        if (linked) PlayerManager.setPlaybackPitch(speed.coerceIn(MIN_PLAYBACK_PITCH, MAX_PLAYBACK_PITCH))
    }
    AudioEffectsCardIntro(
        title = stringResource(CoreCommonR.string.audio_effects_speed_title),
        description = stringResource(CoreCommonR.string.audio_effects_speed_desc)
    )
    if (usbExclusive) AudioEffectsNote(stringResource(CoreCommonR.string.audio_effects_speed_usb_note))
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_speed),
        value = soundState.speed,
        valueRange = MIN_SPEED_SLIDER..MAX_SPEED_SLIDER,
        step = 0.05f,
        formatValue = ::formatMultiplier,
        onValueChange = applySpeed
    )
    QuickChips(
        values = SpeedQuickValues,
        selected = { kotlin.math.abs(soundState.speed - it) < 0.001f },
        label = ::formatMultiplier,
        onClick = applySpeed
    )
    val semitones = pitchToSemitoneOffset(soundState.pitch)
    val resources = LocalResources.current
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_pitch),
        value = semitones,
        valueRange = -12f..12f,
        step = 0.5f,
        enabled = !linked,
        formatValue = { resources.getString(CoreCommonR.string.audio_effects_pitch_semitones, formatSemitones(it)) },
        onValueChange = { value -> PlayerManager.setPlaybackPitch(semitoneOffsetToPitch(value)) }
    )
    if (!linked) {
        QuickChips(
            values = PitchQuickSemitones,
            selected = { kotlin.math.abs(semitones - it) < 0.25f },
            label = ::formatSemitones,
            onClick = { PlayerManager.setPlaybackPitch(semitoneOffsetToPitch(it)) }
        )
    }
    AudioEffectsSwitchRow(
        title = stringResource(CoreCommonR.string.audio_effects_speed_pitch_linked),
        description = stringResource(CoreCommonR.string.audio_effects_speed_pitch_linked_desc),
        checked = linked,
        onCheckedChange = { enabled ->
            editor.settings { it.copy(speedPitchLinked = enabled) }
            val pitch = if (enabled) soundState.speed.coerceIn(MIN_PLAYBACK_PITCH, MAX_PLAYBACK_PITCH) else 1f
            PlayerManager.setPlaybackPitch(pitch)
        }
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End
    ) {
        MiuixSettingsOutlinedButton(onClick = { PlayerManager.resetPlaybackSpeedAndPitch() }) {
            Text(stringResource(CoreCommonR.string.audio_effects_speed_reset))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickChips(
    values: List<Float>,
    selected: (Float) -> Boolean,
    label: (Float) -> String,
    onClick: (Float) -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        values.forEach { value ->
            FilterChip(
                selected = selected(value),
                onClick = { onClick(value) },
                label = { Text(label(value)) }
            )
        }
    }
}

@Composable
internal fun AudioEffectsAdvancedCard(settings: AudioEffectsSettings, route: AudioOutputRoute, editor: AudioEffectsEditor) {
    var confirmReset by remember { mutableStateOf(false) }
    val modes = AudioEffectsPowerMode.entries
    val mode = AudioEffectsPowerMode.fromStorageValue(settings.powerMode)
    AudioEffectsCardIntro(
        title = stringResource(CoreCommonR.string.audio_effects_advanced_title),
        description = stringResource(CoreCommonR.string.audio_effects_power_note)
    )
    AudioEffectsNote(stringResource(CoreCommonR.string.audio_effects_power_mode))
    MiuixSettingsSegmentedTabs(
        labels = modes.map { stringResource(it.labelRes()) },
        selectedIndex = modes.indexOf(mode),
        onSelectedIndexChange = { index -> editor.settings { it.copy(powerMode = modes[index].storageValue) } },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
    AudioEffectsNote(stringResource(mode.descriptionRes()))
    AudioEffectsSwitchRow(
        title = stringResource(CoreCommonR.string.audio_effects_per_output),
        description = stringResource(CoreCommonR.string.audio_effects_per_output_desc),
        checked = settings.perOutputEnabled,
        onCheckedChange = { enabled -> editor.settings { it.withPerOutput(enabled, route) } }
    )
    AudioEffectsSwitchRow(
        title = stringResource(CoreCommonR.string.audio_effects_usb_apply),
        description = stringResource(CoreCommonR.string.audio_effects_usb_apply_desc),
        checked = settings.applyInUsbExclusive,
        onCheckedChange = { enabled -> editor.settings { it.copy(applyInUsbExclusive = enabled) } }
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End
    ) {
        MiuixSettingsOutlinedButton(onClick = { confirmReset = true }) {
            Text(stringResource(CoreCommonR.string.audio_effects_reset_all))
        }
    }
    if (confirmReset) {
        MiuixSettingsDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(CoreCommonR.string.audio_effects_reset_all)) },
            text = { Text(stringResource(CoreCommonR.string.audio_effects_reset_all_confirm)) },
            confirmButton = {
                MiuixSettingsTextButton(onClick = {
                    editor.profile { AudioEffectsProfile() }
                    confirmReset = false
                }) {
                    Text(stringResource(CoreCommonR.string.action_confirm))
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }
}

/** 首次开启分设备保存时，用当前声音作为当前设备的起点，避免一开就变回原声 */
internal fun AudioEffectsSettings.withPerOutput(enabled: Boolean, route: AudioOutputRoute): AudioEffectsSettings {
    if (!enabled) return copy(perOutputEnabled = false)
    val seeded = if (outputProfiles.containsKey(route.storageValue)) {
        outputProfiles
    } else {
        outputProfiles + (route.storageValue to main)
    }
    return copy(perOutputEnabled = true, outputProfiles = seeded)
}
