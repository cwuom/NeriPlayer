package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsInactiveReason
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsPowerMode
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsPresetCategory
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsPresetIds
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsRuntimeStats
import moe.ouom.neriplayer.data.model.playback.effects.AudioOutputRoute
import moe.ouom.neriplayer.data.model.playback.effects.ParametricEqBandType
import moe.ouom.neriplayer.data.model.playback.effects.SpeakerSize
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import moe.ouom.neriplayer.R as AppR

internal val AudioEffectsPresetTabs: List<AudioEffectsPresetCategory?> = listOf(
    AudioEffectsPresetCategory.STYLE,
    AudioEffectsPresetCategory.TONE,
    AudioEffectsPresetCategory.SCENE,
    AudioEffectsPresetCategory.DEVICE,
    null
)

private val PresetNameRes = mapOf(
    AudioEffectsPresetIds.FLAT to AppR.string.audio_effects_preset_flat,
    AudioEffectsPresetIds.CUSTOM to AppR.string.audio_effects_preset_custom,
    "pop" to AppR.string.audio_effects_preset_pop,
    "rock" to AppR.string.audio_effects_preset_rock,
    "classical" to AppR.string.audio_effects_preset_classical,
    "jazz" to AppR.string.audio_effects_preset_jazz,
    "electronic" to AppR.string.audio_effects_preset_electronic,
    "hip_hop" to AppR.string.audio_effects_preset_hip_hop,
    "rnb" to AppR.string.audio_effects_preset_rnb,
    "folk" to AppR.string.audio_effects_preset_folk,
    "metal" to AppR.string.audio_effects_preset_metal,
    "acg" to AppR.string.audio_effects_preset_acg,
    "piano" to AppR.string.audio_effects_preset_piano,
    "vocal_boost" to AppR.string.audio_effects_preset_vocal_boost,
    "bass_boost" to AppR.string.audio_effects_preset_bass_boost,
    "deep_bass" to AppR.string.audio_effects_preset_deep_bass,
    "treble_boost" to AppR.string.audio_effects_preset_treble_boost,
    "warm" to AppR.string.audio_effects_preset_warm,
    "live" to AppR.string.audio_effects_preset_live,
    "concert_hall" to AppR.string.audio_effects_preset_concert_hall,
    "cinema" to AppR.string.audio_effects_preset_cinema,
    "surround_3d" to AppR.string.audio_effects_preset_surround_3d,
    "karaoke" to AppR.string.audio_effects_preset_karaoke,
    "night" to AppR.string.audio_effects_preset_night,
    "spoken" to AppR.string.audio_effects_preset_spoken,
    "loudness_max" to AppR.string.audio_effects_preset_loudness_max,
    "headphone_hifi" to AppR.string.audio_effects_preset_headphone_hifi,
    "studio" to AppR.string.audio_effects_preset_studio,
    "car" to AppR.string.audio_effects_preset_car,
    "small_speaker" to AppR.string.audio_effects_preset_small_speaker
)

internal fun audioEffectsPresetNameRes(id: String): Int? = PresetNameRes[id]

internal fun AudioEffectsPresetCategory?.tabLabelRes(): Int = when (this) {
    AudioEffectsPresetCategory.STYLE -> AppR.string.audio_effects_preset_tab_style
    AudioEffectsPresetCategory.TONE -> AppR.string.audio_effects_preset_tab_tone
    AudioEffectsPresetCategory.SCENE -> AppR.string.audio_effects_preset_tab_scene
    AudioEffectsPresetCategory.DEVICE -> AppR.string.audio_effects_preset_tab_device
    null -> AppR.string.audio_effects_preset_tab_mine
}

internal fun AudioOutputRoute.labelRes(): Int = when (this) {
    AudioOutputRoute.SPEAKER -> AppR.string.audio_effects_route_speaker
    AudioOutputRoute.WIRED -> AppR.string.audio_effects_route_wired
    AudioOutputRoute.BLUETOOTH -> AppR.string.audio_effects_route_bluetooth
    AudioOutputRoute.USB -> AppR.string.audio_effects_route_usb
    AudioOutputRoute.OTHER -> AppR.string.audio_effects_route_other
}

internal fun AudioEffectsPowerMode.labelRes(): Int = when (this) {
    AudioEffectsPowerMode.ECO -> AppR.string.audio_effects_power_eco
    AudioEffectsPowerMode.BALANCED -> AppR.string.audio_effects_power_balanced
    AudioEffectsPowerMode.HIGH -> AppR.string.audio_effects_power_high
}

internal fun AudioEffectsPowerMode.descriptionRes(): Int = when (this) {
    AudioEffectsPowerMode.ECO -> AppR.string.audio_effects_power_eco_desc
    AudioEffectsPowerMode.BALANCED -> AppR.string.audio_effects_power_balanced_desc
    AudioEffectsPowerMode.HIGH -> AppR.string.audio_effects_power_high_desc
}

internal fun SpeakerSize.labelRes(): Int = when (this) {
    SpeakerSize.PHONE -> AppR.string.audio_effects_speaker_size_phone
    SpeakerSize.TABLET -> AppR.string.audio_effects_speaker_size_tablet
    SpeakerSize.LARGE -> AppR.string.audio_effects_speaker_size_large
}

internal fun ParametricEqBandType.labelRes(): Int = when (this) {
    ParametricEqBandType.PEAK -> AppR.string.audio_effects_filter_peak
    ParametricEqBandType.LOW_SHELF -> AppR.string.audio_effects_filter_low_shelf
    ParametricEqBandType.HIGH_SHELF -> AppR.string.audio_effects_filter_high_shelf
    ParametricEqBandType.LOW_PASS -> AppR.string.audio_effects_filter_low_pass
    ParametricEqBandType.HIGH_PASS -> AppR.string.audio_effects_filter_high_pass
    ParametricEqBandType.NOTCH -> AppR.string.audio_effects_filter_notch
    ParametricEqBandType.BAND_PASS -> AppR.string.audio_effects_filter_band_pass
}

internal sealed interface AudioEffectsStatus {
    data class Processing(val sampleRateKhz: String?, val cpuLoad: String) : AudioEffectsStatus
    data class Bypassed(val messageRes: Int) : AudioEffectsStatus
}

/** 把运行时状态翻译成一句用户看得懂的话 */
internal fun resolveAudioEffectsStatus(stats: AudioEffectsRuntimeStats): AudioEffectsStatus {
    if (stats.active) {
        val rate = stats.sampleRate.takeIf { it > 0 }?.let { formatSampleRateKhz(it) }
        return AudioEffectsStatus.Processing(rate, formatCpuLoad(stats.cpuLoadPercent))
    }
    val res = when (stats.inactiveReason) {
        AudioEffectsInactiveReason.NEUTRAL -> AppR.string.audio_effects_status_neutral
        AudioEffectsInactiveReason.USB_EXCLUSIVE -> AppR.string.audio_effects_status_usb
        AudioEffectsInactiveReason.UNSUPPORTED_FORMAT -> AppR.string.audio_effects_status_unsupported
        AudioEffectsInactiveReason.NATIVE_UNAVAILABLE -> AppR.string.audio_effects_status_native_unavailable
        AudioEffectsInactiveReason.DISABLED, null -> AppR.string.audio_effects_status_disabled
    }
    return AudioEffectsStatus.Bypassed(res)
}

internal fun shouldShowLimiterNotice(stats: AudioEffectsRuntimeStats): Boolean =
    stats.active && stats.limiterReductionDb >= 0.5f

internal fun formatSampleRateKhz(sampleRate: Int): String {
    val khz = sampleRate / 1000f
    return if (abs(khz - khz.roundToInt()) < 0.05f) {
        "${khz.roundToInt()} kHz"
    } else {
        String.format(Locale.US, "%.1f kHz", khz)
    }
}

internal fun formatCpuLoad(percent: Float): String = when {
    percent <= 0f -> "<0.1%"
    percent < 0.1f -> "<0.1%"
    else -> String.format(Locale.US, "%.1f%%", percent)
}

internal fun formatSignedDb(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    return when {
        rounded > 0f -> String.format(Locale.US, "+%.1f dB", rounded)
        rounded < 0f -> String.format(Locale.US, "%.1f dB", rounded)
        else -> "0 dB"
    }
}

internal fun formatPercent(value: Float): String = "${(value * 100f).roundToInt()}%"

internal fun formatFrequency(hz: Float): String = when {
    hz >= 1_000f -> {
        val khz = hz / 1_000f
        if (abs(khz - khz.roundToInt()) < 0.05f) "${khz.roundToInt()} kHz" else String.format(Locale.US, "%.1f kHz", khz)
    }
    else -> "${hz.roundToInt()} Hz"
}

internal fun formatMilliseconds(ms: Float): String =
    if (ms < 10f) String.format(Locale.US, "%.1f ms", ms) else "${ms.roundToInt()} ms"

internal fun formatRatio(value: Float): String = String.format(Locale.US, "%.1f:1", value)

internal fun formatMultiplier(value: Float): String = String.format(Locale.US, "%.2fx", value)

internal fun formatSemitones(value: Float): String {
    val rounded = (value * 10f).roundToInt() / 10f
    return if (rounded > 0f) String.format(Locale.US, "+%.1f", rounded) else String.format(Locale.US, "%.1f", rounded)
}

/** 滑块拖动值按步长吸附，并在零点附近吸到 0，方便回到原声 */
internal fun snapToStep(value: Float, step: Float, zeroSnap: Float = step / 2f): Float {
    if (abs(value) < zeroSnap) return 0f
    return (value / step).roundToInt() * step
}

internal fun newUserPresetId(nowMillis: Long): String = "${AudioEffectsPresetIds.USER_PREFIX}$nowMillis"
