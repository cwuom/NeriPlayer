package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSound
import moe.ouom.neriplayer.data.model.playback.effects.compressorAmount
import moe.ouom.neriplayer.data.model.playback.effects.withCompressorAmount
import kotlin.math.roundToInt

@Composable
internal fun AudioEffectsToneCard(sound: AudioEffectsSound, editor: AudioEffectsEditor) {
    AudioEffectsCardIntro(
        title = stringResource(CoreCommonR.string.audio_effects_tone_title),
        description = stringResource(CoreCommonR.string.audio_effects_tone_desc)
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_bass),
        value = sound.bassDb,
        valueRange = -12f..12f,
        step = 0.5f,
        formatValue = ::formatSignedDb,
        input = AudioEffectsInputs.Db,
        onValueChange = { value -> editor.sound { it.copy(bassDb = value) } }
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_bass_frequency),
        description = stringResource(CoreCommonR.string.audio_effects_bass_frequency_desc),
        value = sound.bassFrequencyHz,
        valueRange = 40f..250f,
        step = 5f,
        formatValue = ::formatFrequency,
        input = AudioEffectsInputs.Hz,
        onValueChange = { value -> editor.sound { it.copy(bassFrequencyHz = value) } }
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_treble),
        value = sound.trebleDb,
        valueRange = -12f..12f,
        step = 0.5f,
        formatValue = ::formatSignedDb,
        input = AudioEffectsInputs.Db,
        onValueChange = { value -> editor.sound { it.copy(trebleDb = value) } }
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_treble_frequency),
        value = sound.trebleFrequencyHz,
        valueRange = 2_000f..14_000f,
        step = 250f,
        formatValue = ::formatFrequency,
        input = AudioEffectsInputs.Hz,
        onValueChange = { value -> editor.sound { it.copy(trebleFrequencyHz = value) } }
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_virtual_bass),
        description = stringResource(CoreCommonR.string.audio_effects_virtual_bass_desc),
        value = sound.virtualBass,
        valueRange = 0f..1f,
        step = 0.05f,
        formatValue = ::formatPercent,
        input = AudioEffectsInputs.Percent,
        onValueChange = { value -> editor.sound { it.copy(virtualBass = value) } }
    )
    if (sound.virtualBass > 0f) {
        AudioEffectsSliderRow(
            title = stringResource(CoreCommonR.string.audio_effects_virtual_bass_frequency),
            value = sound.virtualBassFrequencyHz,
            valueRange = 50f..200f,
            step = 5f,
            formatValue = ::formatFrequency,
            input = AudioEffectsInputs.Hz,
            onValueChange = { value -> editor.sound { it.copy(virtualBassFrequencyHz = value) } }
        )
    }
}

@Composable
internal fun AudioEffectsCharacterCard(sound: AudioEffectsSound, editor: AudioEffectsEditor) {
    AudioEffectsCardIntro(
        title = stringResource(CoreCommonR.string.audio_effects_color_title),
        description = stringResource(CoreCommonR.string.audio_effects_color_desc)
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_warmth),
        description = stringResource(CoreCommonR.string.audio_effects_warmth_desc),
        value = sound.warmth,
        valueRange = 0f..1f,
        step = 0.05f,
        formatValue = ::formatPercent,
        input = AudioEffectsInputs.Percent,
        onValueChange = { value -> editor.sound { it.copy(warmth = value) } }
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_clarity),
        description = stringResource(CoreCommonR.string.audio_effects_clarity_desc),
        value = sound.clarity,
        valueRange = 0f..1f,
        step = 0.05f,
        formatValue = ::formatPercent,
        input = AudioEffectsInputs.Percent,
        onValueChange = { value -> editor.sound { it.copy(clarity = value) } }
    )
    if (sound.clarity > 0f) {
        AudioEffectsSliderRow(
            title = stringResource(CoreCommonR.string.audio_effects_clarity_frequency),
            value = sound.clarityFrequencyHz,
            valueRange = 2_000f..10_000f,
            step = 250f,
            formatValue = ::formatFrequency,
            input = AudioEffectsInputs.Hz,
            onValueChange = { value -> editor.sound { it.copy(clarityFrequencyHz = value) } }
        )
    }
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_vocal),
        description = stringResource(CoreCommonR.string.audio_effects_vocal_desc),
        value = sound.vocal,
        valueRange = 0f..1f,
        step = 0.05f,
        formatValue = ::formatPercent,
        input = AudioEffectsInputs.Percent,
        onValueChange = { value -> editor.sound { it.copy(vocal = value) } }
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_vocal_removal),
        description = stringResource(CoreCommonR.string.audio_effects_vocal_removal_desc),
        value = sound.vocalRemoval,
        valueRange = 0f..1f,
        step = 0.05f,
        formatValue = ::formatPercent,
        input = AudioEffectsInputs.Percent,
        onValueChange = { value -> editor.sound { it.copy(vocalRemoval = value) } }
    )
}

@Composable
internal fun AudioEffectsSpaceCard(sound: AudioEffectsSound, editor: AudioEffectsEditor) {
    AudioEffectsCardIntro(
        title = stringResource(CoreCommonR.string.audio_effects_space_title),
        description = stringResource(CoreCommonR.string.audio_effects_space_desc)
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_width),
        description = stringResource(CoreCommonR.string.audio_effects_width_desc),
        value = sound.stereoWidth,
        valueRange = 0f..2f,
        step = 0.05f,
        formatValue = ::formatPercent,
        input = AudioEffectsInputs.Percent,
        onValueChange = { value -> editor.sound { it.copy(stereoWidth = value) } }
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_surround),
        description = stringResource(CoreCommonR.string.audio_effects_surround_desc),
        value = sound.surround,
        valueRange = 0f..1f,
        step = 0.05f,
        formatValue = ::formatPercent,
        input = AudioEffectsInputs.Percent,
        onValueChange = { value -> editor.sound { it.copy(surround = value) } }
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_crossfeed),
        description = stringResource(CoreCommonR.string.audio_effects_crossfeed_desc),
        value = sound.crossfeed,
        valueRange = 0f..1f,
        step = 0.05f,
        formatValue = ::formatPercent,
        input = AudioEffectsInputs.Percent,
        onValueChange = { value -> editor.sound { it.copy(crossfeed = value) } }
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_reverb),
        description = stringResource(CoreCommonR.string.audio_effects_reverb_desc),
        value = sound.reverb,
        valueRange = 0f..1f,
        step = 0.05f,
        formatValue = ::formatPercent,
        input = AudioEffectsInputs.Percent,
        onValueChange = { value -> editor.sound { it.copy(reverb = value) } }
    )
    AnimatedVisibility(visible = sound.reverb > 0f) {
        Column {
            AudioEffectsSliderRow(
                title = stringResource(CoreCommonR.string.audio_effects_reverb_room),
                value = sound.reverbRoom,
                valueRange = 0f..1f,
                step = 0.05f,
                formatValue = ::formatPercent,
                input = AudioEffectsInputs.Percent,
                onValueChange = { value -> editor.sound { it.copy(reverbRoom = value) } }
            )
            AudioEffectsSliderRow(
                title = stringResource(CoreCommonR.string.audio_effects_reverb_brightness),
                value = 1f - sound.reverbDamping,
                valueRange = 0f..1f,
                step = 0.05f,
                formatValue = ::formatPercent,
                input = AudioEffectsInputs.Percent,
                onValueChange = { value -> editor.sound { it.copy(reverbDamping = 1f - value) } }
            )
            AudioEffectsSliderRow(
                title = stringResource(CoreCommonR.string.audio_effects_reverb_predelay),
                value = sound.reverbPreDelayMs,
                valueRange = 0f..100f,
                step = 1f,
                formatValue = ::formatMilliseconds,
                input = AudioEffectsInputs.Ms,
                onValueChange = { value -> editor.sound { it.copy(reverbPreDelayMs = value) } }
            )
        }
    }
    val monoBassOff = stringResource(CoreCommonR.string.audio_effects_off)
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_mono_bass),
        description = stringResource(CoreCommonR.string.audio_effects_mono_bass_desc),
        value = sound.monoBassHz,
        valueRange = 0f..250f,
        step = 10f,
        formatValue = { if (it < 40f) monoBassOff else formatFrequency(it) },
        input = AudioEffectsInputs.Hz,
        onValueChange = { value -> editor.sound { it.copy(monoBassHz = if (value < 40f) 0f else value) } }
    )
    AudioEffectsSwitchRow(
        title = stringResource(CoreCommonR.string.audio_effects_mono),
        description = stringResource(CoreCommonR.string.audio_effects_mono_desc),
        checked = sound.mono,
        onCheckedChange = { enabled -> editor.sound { it.copy(mono = enabled) } }
    )
    AudioEffectsSwitchRow(
        title = stringResource(CoreCommonR.string.audio_effects_swap),
        description = null,
        checked = sound.swapChannels,
        onCheckedChange = { enabled -> editor.sound { it.copy(swapChannels = enabled) } }
    )
}

@Composable
internal fun AudioEffectsDynamicsCard(sound: AudioEffectsSound, editor: AudioEffectsEditor) {
    var advanced by remember { mutableStateOf(false) }
    AudioEffectsCardIntro(
        title = stringResource(CoreCommonR.string.audio_effects_dynamics_title),
        description = stringResource(CoreCommonR.string.audio_effects_dynamics_desc)
    )
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_compressor),
        description = stringResource(CoreCommonR.string.audio_effects_compressor_desc),
        value = sound.compressorAmount(),
        valueRange = 0f..1f,
        step = 0.05f,
        formatValue = ::formatPercent,
        input = AudioEffectsInputs.Percent,
        onValueChange = { value -> editor.sound { it.withCompressorAmount(value) } }
    )
    if (sound.compressorEnabled) {
        AudioEffectsSwitchRow(
            title = stringResource(CoreCommonR.string.audio_effects_compressor_advanced),
            description = null,
            checked = advanced,
            onCheckedChange = { advanced = it }
        )
    }
    AnimatedVisibility(visible = sound.compressorEnabled && advanced) {
        CompressorAdvancedControls(sound, editor)
    }
    AudioEffectsSliderRow(
        title = stringResource(CoreCommonR.string.audio_effects_output_gain),
        description = stringResource(CoreCommonR.string.audio_effects_output_gain_desc),
        value = sound.outputGainDb,
        valueRange = -12f..12f,
        step = 0.5f,
        formatValue = ::formatSignedDb,
        input = AudioEffectsInputs.Db,
        onValueChange = { value -> editor.sound { it.copy(outputGainDb = value) } }
    )
    AudioEffectsSwitchRow(
        title = stringResource(CoreCommonR.string.audio_effects_limiter),
        description = stringResource(CoreCommonR.string.audio_effects_limiter_desc),
        checked = sound.limiterEnabled,
        onCheckedChange = { enabled -> editor.sound { it.copy(limiterEnabled = enabled) } }
    )
    if (sound.limiterEnabled) {
        AudioEffectsSliderRow(
            title = stringResource(CoreCommonR.string.audio_effects_limiter_ceiling),
            value = sound.limiterCeilingDb,
            valueRange = -6f..0f,
            step = 0.1f,
            formatValue = ::formatSignedDb,
            input = AudioEffectsInputs.Db,
            onValueChange = { value -> editor.sound { it.copy(limiterCeilingDb = value) } }
        )
        AudioEffectsSliderRow(
            title = stringResource(CoreCommonR.string.audio_effects_limiter_release),
            value = sound.limiterReleaseMs,
            valueRange = 20f..500f,
            step = 10f,
            formatValue = ::formatMilliseconds,
            input = AudioEffectsInputs.Ms,
            onValueChange = { value -> editor.sound { it.copy(limiterReleaseMs = value) } }
        )
    }
}

@Composable
private fun CompressorAdvancedControls(sound: AudioEffectsSound, editor: AudioEffectsEditor) {
    Column {
        AudioEffectsSliderRow(
            title = stringResource(CoreCommonR.string.audio_effects_compressor_threshold),
            value = sound.compressorThresholdDb,
            valueRange = -50f..0f,
            step = 0.5f,
            formatValue = ::formatSignedDb,
            input = AudioEffectsInputs.Db,
            onValueChange = { value -> editor.sound { it.copy(compressorThresholdDb = value) } }
        )
        AudioEffectsSliderRow(
            title = stringResource(CoreCommonR.string.audio_effects_compressor_ratio),
            value = sound.compressorRatio,
            valueRange = 1f..20f,
            step = 0.1f,
            formatValue = ::formatRatio,
            input = AudioEffectsInputs.Ratio,
            onValueChange = { value -> editor.sound { it.copy(compressorRatio = value) } }
        )
        AudioEffectsSliderRow(
            title = stringResource(CoreCommonR.string.audio_effects_compressor_attack),
            value = sound.compressorAttackMs,
            valueRange = 0.5f..100f,
            step = 0.5f,
            formatValue = ::formatMilliseconds,
            input = AudioEffectsInputs.Ms,
            onValueChange = { value -> editor.sound { it.copy(compressorAttackMs = value) } }
        )
        AudioEffectsSliderRow(
            title = stringResource(CoreCommonR.string.audio_effects_compressor_release),
            value = sound.compressorReleaseMs,
            valueRange = 20f..1_000f,
            step = 10f,
            formatValue = ::formatMilliseconds,
            input = AudioEffectsInputs.Ms,
            onValueChange = { value -> editor.sound { it.copy(compressorReleaseMs = value) } }
        )
        AudioEffectsSliderRow(
            title = stringResource(CoreCommonR.string.audio_effects_compressor_knee),
            value = sound.compressorKneeDb,
            valueRange = 0f..18f,
            step = 0.5f,
            formatValue = { "${(it * 10f).roundToInt() / 10f} dB" },
            input = AudioEffectsInputs.Db,
            onValueChange = { value -> editor.sound { it.copy(compressorKneeDb = value) } }
        )
        AudioEffectsSliderRow(
            title = stringResource(CoreCommonR.string.audio_effects_compressor_makeup),
            value = sound.compressorMakeupDb,
            valueRange = 0f..18f,
            step = 0.5f,
            formatValue = ::formatSignedDb,
            input = AudioEffectsInputs.Db,
            onValueChange = { value -> editor.sound { it.copy(compressorMakeupDb = value) } }
        )
    }
}
