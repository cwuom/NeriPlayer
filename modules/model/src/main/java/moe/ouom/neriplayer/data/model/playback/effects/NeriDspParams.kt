package moe.ouom.neriplayer.data.model.playback.effects

/** 与 native dsp/neri_dsp_params.h 逐项对应，改动下标时两边同步修改 */
object NeriDspParams {
    const val COUNT = 104
    const val GRAPHIC_BAND_COUNT = 10
    const val PARAMETRIC_BAND_COUNT = 10
    const val PARAMETRIC_BAND_STRIDE = 5

    const val MASTER_ENABLED = 0
    const val QUALITY_MODE = 1
    const val PREAMP_DB = 2
    const val OUTPUT_GAIN_DB = 3
    const val LIMITER_ENABLED = 4
    const val LIMITER_CEILING_DB = 5
    const val LIMITER_RELEASE_MS = 6
    const val DITHER_ENABLED = 7
    const val GRAPHIC_EQ_ENABLED = 8
    const val GRAPHIC_EQ_GAIN_0 = 9
    const val PARAMETRIC_EQ_ENABLED = GRAPHIC_EQ_GAIN_0 + GRAPHIC_BAND_COUNT
    const val PARAMETRIC_BAND_0 = PARAMETRIC_EQ_ENABLED + 1
    const val BAND_ENABLED = 0
    const val BAND_TYPE = 1
    const val BAND_FREQUENCY_HZ = 2
    const val BAND_GAIN_DB = 3
    const val BAND_Q = 4
    const val BASS_GAIN_DB = PARAMETRIC_BAND_0 + PARAMETRIC_BAND_COUNT * PARAMETRIC_BAND_STRIDE
    const val BASS_FREQUENCY_HZ = BASS_GAIN_DB + 1
    const val TREBLE_GAIN_DB = BASS_GAIN_DB + 2
    const val TREBLE_FREQUENCY_HZ = BASS_GAIN_DB + 3
    const val VIRTUAL_BASS_AMOUNT = BASS_GAIN_DB + 4
    const val VIRTUAL_BASS_FREQUENCY_HZ = BASS_GAIN_DB + 5
    const val WARMTH_AMOUNT = BASS_GAIN_DB + 6
    const val EXCITER_AMOUNT = BASS_GAIN_DB + 7
    const val EXCITER_FREQUENCY_HZ = BASS_GAIN_DB + 8
    const val VOCAL_CLARITY_AMOUNT = BASS_GAIN_DB + 9
    const val VOCAL_REMOVAL_AMOUNT = BASS_GAIN_DB + 10
    const val STEREO_WIDTH = BASS_GAIN_DB + 11
    const val MONO_BASS_FREQUENCY_HZ = BASS_GAIN_DB + 12
    const val MONO_MIX = BASS_GAIN_DB + 13
    const val CHANNEL_SWAP = BASS_GAIN_DB + 14
    const val CROSSFEED_AMOUNT = BASS_GAIN_DB + 15
    const val SURROUND_AMOUNT = BASS_GAIN_DB + 16
    const val REVERB_AMOUNT = BASS_GAIN_DB + 17
    const val REVERB_ROOM_SIZE = BASS_GAIN_DB + 18
    const val REVERB_DAMPING = BASS_GAIN_DB + 19
    const val REVERB_PRE_DELAY_MS = BASS_GAIN_DB + 20
    const val COMPRESSOR_ENABLED = BASS_GAIN_DB + 21
    const val COMPRESSOR_THRESHOLD_DB = BASS_GAIN_DB + 22
    const val COMPRESSOR_RATIO = BASS_GAIN_DB + 23
    const val COMPRESSOR_ATTACK_MS = BASS_GAIN_DB + 24
    const val COMPRESSOR_RELEASE_MS = BASS_GAIN_DB + 25
    const val COMPRESSOR_KNEE_DB = BASS_GAIN_DB + 26
    const val COMPRESSOR_MAKEUP_DB = BASS_GAIN_DB + 27
    const val SPEAKER_ENABLED = BASS_GAIN_DB + 28
    const val SPEAKER_HIGH_PASS_HZ = BASS_GAIN_DB + 29
    const val SPEAKER_BASS_HARMONICS = BASS_GAIN_DB + 30
    const val SPEAKER_LOUDNESS = BASS_GAIN_DB + 31
    const val SPEAKER_CLARITY = BASS_GAIN_DB + 32
    const val SPEAKER_STEREO_EXPAND = BASS_GAIN_DB + 33
}

data class AudioEffectsRuntimeContext(
    val route: AudioOutputRoute,
    val usbExclusiveNative: Boolean = false,
    val usbBitPerfect: Boolean = false
)

/** active=false 时播放链可直接旁路 native，params 仍携带关闭状态用于平滑淡出 */
class AudioEffectsResolution(
    val active: Boolean,
    val params: FloatArray,
    val inactiveReason: AudioEffectsInactiveReason?
)

fun AudioEffectsSettings.resolveDsp(context: AudioEffectsRuntimeContext): AudioEffectsResolution {
    val settings = normalized()
    val profile = settings.profileFor(context.route)
    if (context.usbExclusiveNative && (!settings.applyInUsbExclusive || context.usbBitPerfect)) {
        return inactive(AudioEffectsInactiveReason.USB_EXCLUSIVE)
    }
    val soundActive = profile.enabled && !profile.sound.isNeutral()
    val speakerActive = settings.speaker.enabled && context.route == AudioOutputRoute.SPEAKER
    if (!soundActive && !speakerActive) {
        val reason = if (profile.enabled || settings.speaker.enabled) {
            AudioEffectsInactiveReason.NEUTRAL
        } else {
            AudioEffectsInactiveReason.DISABLED
        }
        return inactive(reason)
    }
    val sound = if (soundActive) profile.sound.effective() else AudioEffectsSound()
    val params = neutralDspParams()
    params[NeriDspParams.MASTER_ENABLED] = 1f
    val powerMode = AudioEffectsPowerMode.fromStorageValue(settings.powerMode)
    params[NeriDspParams.QUALITY_MODE] = powerMode.nativeValue.toFloat()
    params[NeriDspParams.DITHER_ENABLED] = if (powerMode == AudioEffectsPowerMode.ECO) 0f else 1f
    val headroom = if (settings.autoHeadroom) sound.estimatedHeadroomDb() else 0f
    params.writeSound(sound, headroom)
    if (speakerActive) params.writeSpeaker(settings.speaker)
    return AudioEffectsResolution(active = true, params = params, inactiveReason = null)
}

private fun inactive(reason: AudioEffectsInactiveReason): AudioEffectsResolution =
    AudioEffectsResolution(active = false, params = neutralDspParams(), inactiveReason = reason)

fun neutralDspParams(): FloatArray = FloatArray(NeriDspParams.COUNT).also { params ->
    params[NeriDspParams.QUALITY_MODE] = AudioEffectsPowerMode.BALANCED.nativeValue.toFloat()
    params[NeriDspParams.LIMITER_ENABLED] = 1f
    params[NeriDspParams.LIMITER_CEILING_DB] = -1f
    params[NeriDspParams.LIMITER_RELEASE_MS] = 80f
    repeat(NeriDspParams.PARAMETRIC_BAND_COUNT) { band ->
        val base = NeriDspParams.PARAMETRIC_BAND_0 + band * NeriDspParams.PARAMETRIC_BAND_STRIDE
        params[base + NeriDspParams.BAND_FREQUENCY_HZ] = 1_000f
        params[base + NeriDspParams.BAND_Q] = 0.707f
    }
    params[NeriDspParams.BASS_FREQUENCY_HZ] = 100f
    params[NeriDspParams.TREBLE_FREQUENCY_HZ] = 8_000f
    params[NeriDspParams.VIRTUAL_BASS_FREQUENCY_HZ] = 100f
    params[NeriDspParams.EXCITER_FREQUENCY_HZ] = 5_000f
    params[NeriDspParams.STEREO_WIDTH] = 1f
    params[NeriDspParams.REVERB_ROOM_SIZE] = 0.5f
    params[NeriDspParams.REVERB_DAMPING] = 0.5f
    params[NeriDspParams.COMPRESSOR_THRESHOLD_DB] = -18f
    params[NeriDspParams.COMPRESSOR_RATIO] = 2f
    params[NeriDspParams.COMPRESSOR_ATTACK_MS] = 10f
    params[NeriDspParams.COMPRESSOR_RELEASE_MS] = 150f
    params[NeriDspParams.COMPRESSOR_KNEE_DB] = 6f
    params[NeriDspParams.SPEAKER_HIGH_PASS_HZ] = SpeakerSize.PHONE.protectionHz
}

private fun FloatArray.flag(index: Int, value: Boolean) {
    this[index] = if (value) 1f else 0f
}

private fun FloatArray.writeSound(sound: AudioEffectsSound, headroomDb: Float) {
    this[NeriDspParams.PREAMP_DB] = sound.preampDb - headroomDb
    this[NeriDspParams.OUTPUT_GAIN_DB] = sound.outputGainDb
    flag(NeriDspParams.LIMITER_ENABLED, sound.limiterEnabled)
    this[NeriDspParams.LIMITER_CEILING_DB] = sound.limiterCeilingDb
    this[NeriDspParams.LIMITER_RELEASE_MS] = sound.limiterReleaseMs
    flag(NeriDspParams.GRAPHIC_EQ_ENABLED, sound.equalizerEnabled)
    sound.equalizerBandsDb.take(NeriDspParams.GRAPHIC_BAND_COUNT).forEachIndexed { index, gain ->
        this[NeriDspParams.GRAPHIC_EQ_GAIN_0 + index] = gain
    }
    flag(NeriDspParams.PARAMETRIC_EQ_ENABLED, sound.parametricEnabled)
    sound.parametricBands.take(NeriDspParams.PARAMETRIC_BAND_COUNT).forEachIndexed { index, band ->
        val base = NeriDspParams.PARAMETRIC_BAND_0 + index * NeriDspParams.PARAMETRIC_BAND_STRIDE
        flag(base + NeriDspParams.BAND_ENABLED, band.enabled)
        this[base + NeriDspParams.BAND_TYPE] = ParametricEqBandType.fromStorageValue(band.type).nativeValue.toFloat()
        this[base + NeriDspParams.BAND_FREQUENCY_HZ] = band.frequencyHz
        this[base + NeriDspParams.BAND_GAIN_DB] = band.gainDb
        this[base + NeriDspParams.BAND_Q] = band.q
    }
    writeCharacter(sound)
    writeSpace(sound)
    writeDynamics(sound)
}

private fun FloatArray.writeCharacter(sound: AudioEffectsSound) {
    this[NeriDspParams.BASS_GAIN_DB] = sound.bassDb
    this[NeriDspParams.BASS_FREQUENCY_HZ] = sound.bassFrequencyHz
    this[NeriDspParams.TREBLE_GAIN_DB] = sound.trebleDb
    this[NeriDspParams.TREBLE_FREQUENCY_HZ] = sound.trebleFrequencyHz
    this[NeriDspParams.VIRTUAL_BASS_AMOUNT] = sound.virtualBass
    this[NeriDspParams.VIRTUAL_BASS_FREQUENCY_HZ] = sound.virtualBassFrequencyHz
    this[NeriDspParams.WARMTH_AMOUNT] = sound.warmth
    this[NeriDspParams.EXCITER_AMOUNT] = sound.clarity
    this[NeriDspParams.EXCITER_FREQUENCY_HZ] = sound.clarityFrequencyHz
    this[NeriDspParams.VOCAL_CLARITY_AMOUNT] = sound.vocal
}

private fun FloatArray.writeSpace(sound: AudioEffectsSound) {
    this[NeriDspParams.VOCAL_REMOVAL_AMOUNT] = sound.vocalRemoval
    this[NeriDspParams.STEREO_WIDTH] = sound.stereoWidth
    this[NeriDspParams.MONO_BASS_FREQUENCY_HZ] = sound.monoBassHz
    flag(NeriDspParams.MONO_MIX, sound.mono)
    flag(NeriDspParams.CHANNEL_SWAP, sound.swapChannels)
    this[NeriDspParams.CROSSFEED_AMOUNT] = sound.crossfeed
    this[NeriDspParams.SURROUND_AMOUNT] = sound.surround
    this[NeriDspParams.REVERB_AMOUNT] = sound.reverb
    this[NeriDspParams.REVERB_ROOM_SIZE] = sound.reverbRoom
    this[NeriDspParams.REVERB_DAMPING] = sound.reverbDamping
    this[NeriDspParams.REVERB_PRE_DELAY_MS] = sound.reverbPreDelayMs
}

private fun FloatArray.writeDynamics(sound: AudioEffectsSound) {
    flag(NeriDspParams.COMPRESSOR_ENABLED, sound.compressorEnabled)
    this[NeriDspParams.COMPRESSOR_THRESHOLD_DB] = sound.compressorThresholdDb
    this[NeriDspParams.COMPRESSOR_RATIO] = sound.compressorRatio
    this[NeriDspParams.COMPRESSOR_ATTACK_MS] = sound.compressorAttackMs
    this[NeriDspParams.COMPRESSOR_RELEASE_MS] = sound.compressorReleaseMs
    this[NeriDspParams.COMPRESSOR_KNEE_DB] = sound.compressorKneeDb
    this[NeriDspParams.COMPRESSOR_MAKEUP_DB] = sound.compressorMakeupDb
}

private fun FloatArray.writeSpeaker(speaker: SpeakerOptimizerSettings) {
    this[NeriDspParams.SPEAKER_ENABLED] = 1f
    this[NeriDspParams.SPEAKER_HIGH_PASS_HZ] = speaker.effectiveProtectionHz()
    this[NeriDspParams.SPEAKER_BASS_HARMONICS] = speaker.bassHarmonics
    this[NeriDspParams.SPEAKER_LOUDNESS] = speaker.loudness
    this[NeriDspParams.SPEAKER_CLARITY] = speaker.clarity
    this[NeriDspParams.SPEAKER_STEREO_EXPAND] = speaker.stereoExpand
}
