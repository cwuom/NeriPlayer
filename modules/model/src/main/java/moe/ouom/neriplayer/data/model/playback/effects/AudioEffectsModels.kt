package moe.ouom.neriplayer.data.model.playback.effects

import kotlinx.serialization.Serializable

const val AUDIO_EFFECTS_SETTINGS_VERSION = 1
const val AUDIO_EFFECTS_GRAPHIC_BAND_COUNT = 10
const val AUDIO_EFFECTS_PARAMETRIC_BAND_LIMIT = 10
const val AUDIO_EFFECTS_USER_PRESET_LIMIT = 50
const val AUDIO_EFFECTS_USER_PRESET_NAME_LIMIT = 24

val AudioEffectsGraphicBandFrequenciesHz: List<Float> = listOf(
    31.25f, 62.5f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f
)

enum class AudioOutputRoute(val storageValue: String) {
    SPEAKER("speaker"),
    WIRED("wired"),
    BLUETOOTH("bluetooth"),
    USB("usb"),
    OTHER("other");

    companion object {
        fun fromStorageValue(value: String?): AudioOutputRoute? = entries.firstOrNull { it.storageValue == value }
    }
}

enum class AudioEffectsPowerMode(val storageValue: String, val nativeValue: Int) {
    ECO("eco", 0),
    BALANCED("balanced", 1),
    HIGH("high", 2);

    companion object {
        fun fromStorageValue(value: String?): AudioEffectsPowerMode =
            entries.firstOrNull { it.storageValue == value } ?: BALANCED
    }
}

enum class ParametricEqBandType(val storageValue: String, val nativeValue: Int, val usesGain: Boolean) {
    PEAK("peak", 0, true),
    LOW_SHELF("low_shelf", 1, true),
    HIGH_SHELF("high_shelf", 2, true),
    LOW_PASS("low_pass", 3, false),
    HIGH_PASS("high_pass", 4, false),
    NOTCH("notch", 5, false),
    BAND_PASS("band_pass", 6, false);

    companion object {
        fun fromStorageValue(value: String?): ParametricEqBandType =
            entries.firstOrNull { it.storageValue == value } ?: PEAK
    }
}

enum class SpeakerSize(val storageValue: String, val protectionHz: Float) {
    PHONE("phone", 180f),
    TABLET("tablet", 120f),
    LARGE("large", 70f);

    companion object {
        fun fromStorageValue(value: String?): SpeakerSize = entries.firstOrNull { it.storageValue == value } ?: PHONE
    }
}

@Serializable
data class ParametricEqBand(
    val enabled: Boolean = true,
    val type: String = ParametricEqBandType.PEAK.storageValue,
    val frequencyHz: Float = 1_000f,
    val gainDb: Float = 0f,
    val q: Float = 0.707f
)

/** 用户能感知的音色参数，所有数值都是界面上的直观单位 */
@Serializable
data class AudioEffectsSound(
    val preampDb: Float = 0f,
    val equalizerEnabled: Boolean = true,
    val equalizerBandsDb: List<Float> = List(AUDIO_EFFECTS_GRAPHIC_BAND_COUNT) { 0f },
    val parametricEnabled: Boolean = false,
    val parametricBands: List<ParametricEqBand> = emptyList(),
    val bassDb: Float = 0f,
    val bassFrequencyHz: Float = 100f,
    val trebleDb: Float = 0f,
    val trebleFrequencyHz: Float = 8_000f,
    val virtualBass: Float = 0f,
    val virtualBassFrequencyHz: Float = 100f,
    val warmth: Float = 0f,
    val clarity: Float = 0f,
    val clarityFrequencyHz: Float = 5_000f,
    val vocal: Float = 0f,
    val vocalRemoval: Float = 0f,
    val stereoWidth: Float = 1f,
    val monoBassHz: Float = 0f,
    val mono: Boolean = false,
    val swapChannels: Boolean = false,
    val crossfeed: Float = 0f,
    val surround: Float = 0f,
    val reverb: Float = 0f,
    val reverbRoom: Float = 0.5f,
    val reverbDamping: Float = 0.5f,
    val reverbPreDelayMs: Float = 10f,
    val compressorEnabled: Boolean = false,
    val compressorThresholdDb: Float = -18f,
    val compressorRatio: Float = 2f,
    val compressorAttackMs: Float = 10f,
    val compressorReleaseMs: Float = 150f,
    val compressorKneeDb: Float = 6f,
    val compressorMakeupDb: Float = 0f,
    val outputGainDb: Float = 0f,
    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Float = -1f,
    val limiterReleaseMs: Float = 80f
)

@Serializable
data class AudioEffectsProfile(
    val enabled: Boolean = false,
    val presetId: String = AudioEffectsPresetIds.FLAT,
    val sound: AudioEffectsSound = AudioEffectsSound()
)

@Serializable
data class AudioEffectsUserPreset(
    val id: String,
    val name: String,
    val sound: AudioEffectsSound
)

@Serializable
data class SpeakerOptimizerSettings(
    val enabled: Boolean = false,
    val size: String = SpeakerSize.PHONE.storageValue,
    val protectionHz: Float = 0f,
    val bassHarmonics: Float = 0.5f,
    val loudness: Float = 0.4f,
    val clarity: Float = 0.3f,
    val stereoExpand: Float = 0.3f
)

@Serializable
data class AudioEffectsSettings(
    val version: Int = AUDIO_EFFECTS_SETTINGS_VERSION,
    val main: AudioEffectsProfile = AudioEffectsProfile(),
    val perOutputEnabled: Boolean = false,
    val outputProfiles: Map<String, AudioEffectsProfile> = emptyMap(),
    val userPresets: List<AudioEffectsUserPreset> = emptyList(),
    val speaker: SpeakerOptimizerSettings = SpeakerOptimizerSettings(),
    val powerMode: String = AudioEffectsPowerMode.BALANCED.storageValue,
    val autoHeadroom: Boolean = true,
    val applyInUsbExclusive: Boolean = false,
    val speedPitchLinked: Boolean = false
)

/** 运行时 DSP 的实时负载与保护状态，由播放线程按秒刷新 */
data class AudioEffectsRuntimeStats(
    val active: Boolean = false,
    val route: AudioOutputRoute = AudioOutputRoute.SPEAKER,
    val inactiveReason: AudioEffectsInactiveReason? = AudioEffectsInactiveReason.DISABLED,
    val cpuLoadPercent: Float = 0f,
    val limiterReductionDb: Float = 0f,
    val compressorReductionDb: Float = 0f,
    val sampleRate: Int = 0,
    val nativeAvailable: Boolean = true
)

enum class AudioEffectsInactiveReason {
    DISABLED,
    NEUTRAL,
    USB_EXCLUSIVE,
    UNSUPPORTED_FORMAT,
    NATIVE_UNAVAILABLE
}
