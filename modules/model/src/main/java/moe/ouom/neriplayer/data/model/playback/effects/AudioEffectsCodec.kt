package moe.ouom.neriplayer.data.model.playback.effects

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import moe.ouom.neriplayer.data.model.playback.PlaybackEqualizerPresetId
import moe.ouom.neriplayer.data.model.playback.findPlaybackEqualizerPreset
import kotlin.math.ln
import kotlin.math.roundToInt

private val LegacyEqualizerAnchorsHz = listOf(60f, 230f, 910f, 3_600f, 14_000f)
private const val LEGACY_LOUDNESS_MAX_DB = 12f

object AudioEffectsSettingsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    fun encode(settings: AudioEffectsSettings): String =
        json.encodeToString(AudioEffectsSettings.serializer(), settings.normalized())

    fun decodeOrNull(raw: String?): AudioEffectsSettings? {
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString(AudioEffectsSettings.serializer(), raw).normalized()
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun encodeUserPreset(preset: AudioEffectsUserPreset): String =
        json.encodeToString(AudioEffectsUserPreset.serializer(), preset)

    fun decodeUserPresetOrNull(raw: String?): AudioEffectsUserPreset? {
        if (raw.isNullOrBlank()) return null
        return try {
            json.decodeFromString(AudioEffectsUserPreset.serializer(), raw.trim()).normalizedOrNull()
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/**
 * 读取持久化音效设置；新键缺失时用旧版系统均衡器和响度增强设置推导，
 * 让升级用户保留原来的听感
 */
fun resolveAudioEffectsSettings(
    raw: String?,
    legacyEqualizerEnabled: Boolean,
    legacyPresetId: String,
    legacyCustomBandLevelsMb: List<Int>,
    legacyLoudnessGainMb: Int
): AudioEffectsSettings {
    AudioEffectsSettingsCodec.decodeOrNull(raw)?.let { return it }
    return migrateLegacyAudioEffects(
        equalizerEnabled = legacyEqualizerEnabled,
        presetId = legacyPresetId,
        customBandLevelsMb = legacyCustomBandLevelsMb,
        loudnessGainMb = legacyLoudnessGainMb
    )
}

fun migrateLegacyAudioEffects(
    equalizerEnabled: Boolean,
    presetId: String,
    customBandLevelsMb: List<Int>,
    loudnessGainMb: Int
): AudioEffectsSettings {
    val loudnessDb = (loudnessGainMb.coerceAtLeast(0) / 100f).coerceAtMost(LEGACY_LOUDNESS_MAX_DB)
    if (!equalizerEnabled && loudnessDb <= 0f) return AudioEffectsSettings()
    val bands = if (equalizerEnabled) legacyGraphicBands(presetId, customBandLevelsMb) else List(10) { 0f }
    val flatEqualizer = bands.all { it == 0f }
    val sound = AudioEffectsSound(
        equalizerBandsDb = bands,
        outputGainDb = loudnessDb,
        limiterEnabled = true
    ).normalized()
    val migratedPresetId = if (flatEqualizer && loudnessDb <= 0f) {
        AudioEffectsPresetIds.FLAT
    } else {
        AudioEffectsPresetIds.CUSTOM
    }
    return AudioEffectsSettings(
        main = AudioEffectsProfile(enabled = true, presetId = migratedPresetId, sound = sound)
    ).normalized()
}

private fun legacyGraphicBands(presetId: String, customBandLevelsMb: List<Int>): List<Float> {
    val centers = AudioEffectsGraphicBandFrequenciesHz
    if (presetId == PlaybackEqualizerPresetId.CUSTOM) {
        if (customBandLevelsMb.isEmpty()) return List(centers.size) { 0f }
        val anchors = legacyCustomAnchorsHz(customBandLevelsMb.size)
        val anchorDb = customBandLevelsMb.map { it / 100f }
        return centers.map { interpolateLogFrequency(it, anchors, anchorDb) }
    }
    val preset = findPlaybackEqualizerPreset(presetId) ?: return List(centers.size) { 0f }
    return preset.resolveBandLevelsMb(
        bandCentersHz = centers.map { it.roundToInt() },
        bandLevelRangeMb = -1_500..1_500
    ).map { it / 100f }
}

private fun legacyCustomAnchorsHz(count: Int): List<Float> {
    if (count == LegacyEqualizerAnchorsHz.size) return LegacyEqualizerAnchorsHz
    if (count == 1) return listOf(1_000f)
    val low = ln(LegacyEqualizerAnchorsHz.first())
    val high = ln(LegacyEqualizerAnchorsHz.last())
    return List(count) { index -> kotlin.math.exp(low + (high - low) * index / (count - 1)) }
}

internal fun interpolateLogFrequency(frequencyHz: Float, anchorsHz: List<Float>, anchorValues: List<Float>): Float {
    if (anchorsHz.isEmpty() || anchorsHz.size != anchorValues.size) return 0f
    if (frequencyHz <= anchorsHz.first()) return anchorValues.first()
    if (frequencyHz >= anchorsHz.last()) return anchorValues.last()
    val index = anchorsHz.indexOfFirst { it >= frequencyHz }.coerceAtLeast(1)
    val leftHz = anchorsHz[index - 1]
    val rightHz = anchorsHz[index]
    val progress = ((ln(frequencyHz) - ln(leftHz)) / (ln(rightHz) - ln(leftHz))).coerceIn(0f, 1f)
    return anchorValues[index - 1] + (anchorValues[index] - anchorValues[index - 1]) * progress
}
