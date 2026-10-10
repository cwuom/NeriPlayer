package moe.ouom.neriplayer.data.model.playback.effects

import moe.ouom.neriplayer.data.model.playback.DEFAULT_PLAYBACK_PITCH
import moe.ouom.neriplayer.data.model.playback.DEFAULT_PLAYBACK_SPEED
import moe.ouom.neriplayer.data.model.playback.MAX_PLAYBACK_PITCH
import moe.ouom.neriplayer.data.model.playback.MAX_PLAYBACK_SPEED
import moe.ouom.neriplayer.data.model.playback.MIN_PLAYBACK_PITCH
import moe.ouom.neriplayer.data.model.playback.MIN_PLAYBACK_SPEED
import kotlin.math.abs

const val AUDIO_EFFECTS_EQ_BAND_LIMIT_DB = 12f
const val AUDIO_EFFECTS_PREAMP_MIN_DB = -12f
const val AUDIO_EFFECTS_PREAMP_MAX_DB = 6f

private const val NEUTRAL_EPSILON = 0.001f

private fun Float.finiteIn(min: Float, max: Float, fallback: Float): Float =
    if (isFinite()) coerceIn(min, max) else fallback

fun ParametricEqBand.normalized(): ParametricEqBand = copy(
    type = ParametricEqBandType.fromStorageValue(type).storageValue,
    frequencyHz = frequencyHz.finiteIn(20f, 20_000f, 1_000f),
    gainDb = gainDb.finiteIn(-24f, 24f, 0f),
    q = q.finiteIn(0.1f, 20f, 0.707f)
)

fun AudioEffectsSound.normalized(): AudioEffectsSound = copy(
    preampDb = preampDb.finiteIn(AUDIO_EFFECTS_PREAMP_MIN_DB, AUDIO_EFFECTS_PREAMP_MAX_DB, 0f),
    equalizerBandsDb = List(AUDIO_EFFECTS_GRAPHIC_BAND_COUNT) { index ->
        equalizerBandsDb.getOrNull(index)
            ?.finiteIn(-AUDIO_EFFECTS_EQ_BAND_LIMIT_DB, AUDIO_EFFECTS_EQ_BAND_LIMIT_DB, 0f)
            ?: 0f
    },
    parametricBands = parametricBands.take(AUDIO_EFFECTS_PARAMETRIC_BAND_LIMIT).map { it.normalized() },
    bassDb = bassDb.finiteIn(-12f, 12f, 0f),
    bassFrequencyHz = bassFrequencyHz.finiteIn(40f, 250f, 100f),
    trebleDb = trebleDb.finiteIn(-12f, 12f, 0f),
    trebleFrequencyHz = trebleFrequencyHz.finiteIn(2_000f, 14_000f, 8_000f),
    virtualBass = virtualBass.finiteIn(0f, 1f, 0f),
    virtualBassFrequencyHz = virtualBassFrequencyHz.finiteIn(50f, 200f, 100f),
    warmth = warmth.finiteIn(0f, 1f, 0f),
    clarity = clarity.finiteIn(0f, 1f, 0f),
    clarityFrequencyHz = clarityFrequencyHz.finiteIn(2_000f, 10_000f, 5_000f),
    vocal = vocal.finiteIn(0f, 1f, 0f),
    vocalRemoval = vocalRemoval.finiteIn(0f, 1f, 0f),
    stereoWidth = stereoWidth.finiteIn(0f, 2f, 1f),
    monoBassHz = if (monoBassHz.isFinite() && monoBassHz >= 40f) monoBassHz.coerceAtMost(250f) else 0f,
    crossfeed = crossfeed.finiteIn(0f, 1f, 0f),
    surround = surround.finiteIn(0f, 1f, 0f),
    reverb = reverb.finiteIn(0f, 1f, 0f),
    reverbRoom = reverbRoom.finiteIn(0f, 1f, 0.5f),
    reverbDamping = reverbDamping.finiteIn(0f, 1f, 0.5f),
    reverbPreDelayMs = reverbPreDelayMs.finiteIn(0f, 100f, 10f),
    compressorThresholdDb = compressorThresholdDb.finiteIn(-50f, 0f, -18f),
    compressorRatio = compressorRatio.finiteIn(1f, 20f, 2f),
    compressorAttackMs = compressorAttackMs.finiteIn(0.5f, 100f, 10f),
    compressorReleaseMs = compressorReleaseMs.finiteIn(20f, 1_000f, 150f),
    compressorKneeDb = compressorKneeDb.finiteIn(0f, 18f, 6f),
    compressorMakeupDb = compressorMakeupDb.finiteIn(0f, 18f, 0f),
    outputGainDb = outputGainDb.finiteIn(-12f, 12f, 0f),
    limiterCeilingDb = limiterCeilingDb.finiteIn(-6f, 0f, -1f),
    limiterReleaseMs = limiterReleaseMs.finiteIn(20f, 500f, 80f)
)

fun SpeakerOptimizerSettings.normalized(): SpeakerOptimizerSettings = copy(
    size = SpeakerSize.fromStorageValue(size).storageValue,
    protectionHz = if (protectionHz.isFinite() && protectionHz >= 40f) protectionHz.coerceAtMost(400f) else 0f,
    bassHarmonics = bassHarmonics.finiteIn(0f, 1f, 0.5f),
    loudness = loudness.finiteIn(0f, 1f, 0.4f),
    clarity = clarity.finiteIn(0f, 1f, 0.3f),
    stereoExpand = stereoExpand.finiteIn(0f, 1f, 0.3f)
)

fun SpeakerOptimizerSettings.effectiveProtectionHz(): Float =
    if (protectionHz >= 40f) protectionHz else SpeakerSize.fromStorageValue(size).protectionHz

fun AudioEffectsProfile.normalized(): AudioEffectsProfile = copy(
    presetId = presetId.trim().ifBlank { AudioEffectsPresetIds.FLAT },
    sound = sound.normalized()
)

fun AudioEffectsUserPreset.normalizedOrNull(): AudioEffectsUserPreset? {
    val trimmedId = id.trim()
    val trimmedName = name.trim().take(AUDIO_EFFECTS_USER_PRESET_NAME_LIMIT)
    if (trimmedId.isEmpty() || trimmedName.isEmpty()) return null
    return copy(id = trimmedId, name = trimmedName, sound = sound.normalized())
}

fun AudioEffectsSettings.normalized(): AudioEffectsSettings = copy(
    version = AUDIO_EFFECTS_SETTINGS_VERSION,
    main = main.normalized(),
    outputProfiles = outputProfiles
        .filterKeys { AudioOutputRoute.fromStorageValue(it) != null }
        .mapValues { it.value.normalized() },
    userPresets = userPresets.mapNotNull { it.normalizedOrNull() }
        .distinctBy { it.id }
        .take(AUDIO_EFFECTS_USER_PRESET_LIMIT),
    speaker = speaker.normalized(),
    powerMode = AudioEffectsPowerMode.fromStorageValue(powerMode).storageValue,
    speedPitchLinked = speedPitchLinked || version < AUDIO_EFFECTS_LINKED_PITCH_VERSION,
    storedSpeed = storedSpeed.finiteIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED),
    storedPitch = storedPitch.finiteIn(MIN_PLAYBACK_PITCH, MAX_PLAYBACK_PITCH, DEFAULT_PLAYBACK_PITCH)
)

private fun Float.isZero(): Boolean = abs(this) < NEUTRAL_EPSILON

private fun ParametricEqBand.isNeutral(): Boolean {
    if (!enabled) return true
    return ParametricEqBandType.fromStorageValue(type).usesGain && gainDb.isZero()
}

/** 所有处理都等于原声时返回 true，此时播放链可以完全旁路 DSP */
fun AudioEffectsSound.isNeutral(): Boolean = effective().allProcessingNeutral()

private fun AudioEffectsSound.allProcessingNeutral(): Boolean {
    val equalizerNeutral = !equalizerEnabled || equalizerBandsDb.all { it.isZero() }
    val parametricNeutral = !parametricEnabled || parametricBands.all { it.isNeutral() }
    val toneNeutral = bassDb.isZero() && trebleDb.isZero() && preampDb.isZero() && outputGainDb.isZero()
    val colorNeutral = virtualBass.isZero() && warmth.isZero() && clarity.isZero() && vocal.isZero()
    val stereoNeutral = vocalRemoval.isZero() && (stereoWidth - 1f).isZero() && monoBassHz.isZero() &&
        !mono && !swapChannels && crossfeed.isZero()
    val spaceNeutral = surround.isZero() && reverb.isZero()
    return equalizerNeutral && parametricNeutral && toneNeutral && colorNeutral &&
        stereoNeutral && spaceNeutral && !compressorEnabled
}

fun AudioEffectsSettings.profileFor(route: AudioOutputRoute): AudioEffectsProfile =
    if (perOutputEnabled) outputProfiles[route.storageValue] ?: main else main

fun AudioEffectsSettings.updateProfileFor(
    route: AudioOutputRoute,
    transform: (AudioEffectsProfile) -> AudioEffectsProfile
): AudioEffectsSettings {
    if (!perOutputEnabled) return copy(main = transform(main).normalized())
    val current = outputProfiles[route.storageValue] ?: main
    return copy(outputProfiles = outputProfiles + (route.storageValue to transform(current).normalized()))
}

/** 把易用的“压缩力度”映射成完整的压缩器参数 */
fun AudioEffectsSound.withCompressorAmount(amount: Float): AudioEffectsSound {
    val strength = amount.finiteIn(0f, 1f, 0f)
    if (strength <= 0f) return copy(compressorEnabled = false)
    val threshold = -10f - 26f * strength
    val ratio = 1.5f + 4.5f * strength
    return copy(
        compressorEnabled = true,
        compressorThresholdDb = threshold,
        compressorRatio = ratio,
        compressorAttackMs = 12f - 6f * strength,
        compressorReleaseMs = 180f,
        compressorKneeDb = 8f,
        compressorMakeupDb = 0.5f * -threshold * (1f - 1f / ratio)
    )
}

fun AudioEffectsSound.compressorAmount(): Float {
    if (!compressorEnabled) return 0f
    return ((-compressorThresholdDb - 10f) / 26f).coerceIn(0f, 1f)
}
