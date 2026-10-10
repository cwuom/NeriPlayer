package moe.ouom.neriplayer.data.model.playback.effects

object AudioEffectsPresetIds {
    const val FLAT = "flat"
    const val CUSTOM = "custom"
    const val USER_PREFIX = "user_"
}

enum class AudioEffectsPresetCategory {
    STYLE,
    TONE,
    SCENE,
    DEVICE
}

data class AudioEffectsPreset(
    val id: String,
    val category: AudioEffectsPresetCategory,
    val sound: AudioEffectsSound
)

private fun eq(vararg gains: Float): List<Float> = gains.toList()

private val Neutral = AudioEffectsSound()

val AudioEffectsBuiltInPresets: List<AudioEffectsPreset> = listOf(
    AudioEffectsPreset(AudioEffectsPresetIds.FLAT, AudioEffectsPresetCategory.STYLE, Neutral),
    AudioEffectsPreset(
        "pop", AudioEffectsPresetCategory.STYLE,
        Neutral.copy(equalizerBandsDb = eq(0f, 1f, 2f, 1f, 0f, -0.5f, 1f, 2f, 2.5f, 2f), vocal = 0.2f)
    ),
    AudioEffectsPreset(
        "rock", AudioEffectsPresetCategory.STYLE,
        Neutral.copy(equalizerBandsDb = eq(3f, 3f, 2f, 0f, -1f, -0.5f, 1f, 2.5f, 3f, 3f), warmth = 0.15f)
    ),
    AudioEffectsPreset(
        "classical", AudioEffectsPresetCategory.STYLE,
        Neutral.copy(
            equalizerBandsDb = eq(1f, 1f, 0.5f, 0f, 0f, 0f, -0.5f, 0.5f, 1.5f, 2f),
            stereoWidth = 1.1f, reverb = 0.12f, reverbRoom = 0.75f, reverbDamping = 0.45f, reverbPreDelayMs = 20f
        )
    ),
    AudioEffectsPreset(
        "jazz", AudioEffectsPresetCategory.STYLE,
        Neutral.copy(equalizerBandsDb = eq(1.5f, 1f, 1f, 1.5f, 0f, -0.5f, 0.5f, 1.5f, 2f, 1.5f), warmth = 0.25f)
    ),
    AudioEffectsPreset(
        "electronic", AudioEffectsPresetCategory.STYLE,
        Neutral.copy(
            equalizerBandsDb = eq(4f, 3.5f, 2f, 0f, -1f, 0f, 1f, 2f, 3f, 3f),
            virtualBass = 0.25f, stereoWidth = 1.15f
        )
    ),
    AudioEffectsPreset(
        "hip_hop", AudioEffectsPresetCategory.STYLE,
        Neutral.copy(equalizerBandsDb = eq(4.5f, 4f, 2.5f, 1f, -0.5f, -0.5f, 1f, 1f, 1.5f, 1f), virtualBass = 0.3f)
    ),
    AudioEffectsPreset(
        "rnb", AudioEffectsPresetCategory.STYLE,
        Neutral.copy(equalizerBandsDb = eq(3f, 3f, 2f, 1f, -0.5f, 0f, 1f, 1.5f, 1.5f, 1f), warmth = 0.2f)
    ),
    AudioEffectsPreset(
        "folk", AudioEffectsPresetCategory.STYLE,
        Neutral.copy(equalizerBandsDb = eq(0.5f, 1f, 1.5f, 1f, 0f, 0f, 1f, 2f, 2f, 1.5f), warmth = 0.15f)
    ),
    AudioEffectsPreset(
        "metal", AudioEffectsPresetCategory.STYLE,
        Neutral.copy(equalizerBandsDb = eq(3f, 2.5f, 1f, -1f, -2f, -1f, 1f, 3f, 3.5f, 3f))
    ),
    AudioEffectsPreset(
        "acg", AudioEffectsPresetCategory.STYLE,
        Neutral.copy(
            equalizerBandsDb = eq(1f, 1f, 0.5f, 0f, 0f, 0.5f, 1.5f, 2.5f, 3f, 2.5f),
            vocal = 0.3f, clarity = 0.2f
        )
    ),
    AudioEffectsPreset(
        "piano", AudioEffectsPresetCategory.STYLE,
        Neutral.copy(equalizerBandsDb = eq(0f, 0f, 0.5f, 1f, 0.5f, 0f, 0.5f, 1f, 1.5f, 1f), reverb = 0.08f)
    ),
    AudioEffectsPreset(
        "vocal_boost", AudioEffectsPresetCategory.TONE,
        Neutral.copy(equalizerBandsDb = eq(-2f, -1.5f, -1f, 0f, 1f, 2.5f, 3f, 2.5f, 1f, 0f), vocal = 0.4f)
    ),
    AudioEffectsPreset(
        "bass_boost", AudioEffectsPresetCategory.TONE,
        Neutral.copy(equalizerBandsDb = eq(2f, 2f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), bassDb = 6f, virtualBass = 0.2f)
    ),
    AudioEffectsPreset(
        "deep_bass", AudioEffectsPresetCategory.TONE,
        Neutral.copy(
            equalizerBandsDb = eq(5f, 4f, 2f, 0f, -0.5f, 0f, 0f, 0f, 0f, 0f),
            bassDb = 6f, bassFrequencyHz = 80f, virtualBass = 0.5f, monoBassHz = 120f
        )
    ),
    AudioEffectsPreset(
        "treble_boost", AudioEffectsPresetCategory.TONE,
        Neutral.copy(trebleDb = 5f, trebleFrequencyHz = 6_000f, clarity = 0.2f)
    ),
    AudioEffectsPreset(
        "warm", AudioEffectsPresetCategory.TONE,
        Neutral.copy(warmth = 0.6f, trebleDb = -3f, trebleFrequencyHz = 9_000f, crossfeed = 0.1f)
    ),
    AudioEffectsPreset(
        "live", AudioEffectsPresetCategory.SCENE,
        Neutral.copy(
            stereoWidth = 1.3f, surround = 0.2f,
            reverb = 0.35f, reverbRoom = 0.85f, reverbDamping = 0.4f, reverbPreDelayMs = 25f
        )
    ),
    AudioEffectsPreset(
        "concert_hall", AudioEffectsPresetCategory.SCENE,
        Neutral.copy(
            stereoWidth = 1.2f,
            reverb = 0.4f, reverbRoom = 0.95f, reverbDamping = 0.3f, reverbPreDelayMs = 40f
        )
    ),
    AudioEffectsPreset(
        "cinema", AudioEffectsPresetCategory.SCENE,
        Neutral.copy(
            bassDb = 4f, virtualBass = 0.3f, surround = 0.6f,
            reverb = 0.15f, reverbRoom = 0.7f
        ).withCompressorAmount(0.25f)
    ),
    AudioEffectsPreset(
        "surround_3d", AudioEffectsPresetCategory.SCENE,
        Neutral.copy(surround = 0.8f, stereoWidth = 1.5f)
    ),
    AudioEffectsPreset(
        "karaoke", AudioEffectsPresetCategory.SCENE,
        Neutral.copy(vocalRemoval = 0.9f, reverb = 0.25f, reverbRoom = 0.6f)
    ),
    AudioEffectsPreset(
        "night", AudioEffectsPresetCategory.SCENE,
        Neutral.copy(bassDb = -2f, trebleDb = -1f).withCompressorAmount(0.7f)
    ),
    AudioEffectsPreset(
        "spoken", AudioEffectsPresetCategory.SCENE,
        Neutral.copy(
            equalizerBandsDb = eq(-6f, -4f, -2f, 0f, 1f, 2f, 3f, 2f, 0f, -1f),
            mono = true, vocal = 0.5f
        ).withCompressorAmount(0.5f)
    ),
    AudioEffectsPreset(
        "loudness_max", AudioEffectsPresetCategory.SCENE,
        Neutral.copy(limiterCeilingDb = -0.5f).withCompressorAmount(0.85f)
    ),
    AudioEffectsPreset(
        "headphone_hifi", AudioEffectsPresetCategory.DEVICE,
        Neutral.copy(equalizerBandsDb = eq(1f, 0.5f, 0f, 0f, 0f, 0f, 0.5f, 1f, 1f, 0.5f), crossfeed = 0.35f, clarity = 0.1f)
    ),
    AudioEffectsPreset(
        "studio", AudioEffectsPresetCategory.DEVICE,
        Neutral.copy(crossfeed = 0.3f, reverb = 0.05f, reverbRoom = 0.25f)
    ),
    AudioEffectsPreset(
        "car", AudioEffectsPresetCategory.DEVICE,
        Neutral.copy(bassDb = 3f, trebleDb = 2f, vocal = 0.2f).withCompressorAmount(0.3f)
    ),
    AudioEffectsPreset(
        "small_speaker", AudioEffectsPresetCategory.DEVICE,
        Neutral.copy(
            equalizerBandsDb = eq(0f, 0f, 1f, 1f, 0f, 0f, 1f, 2f, 2f, 1f),
            virtualBass = 0.5f, virtualBassFrequencyHz = 140f, monoBassHz = 150f
        ).withCompressorAmount(0.35f)
    )
)

fun findAudioEffectsBuiltInPreset(id: String): AudioEffectsPreset? =
    AudioEffectsBuiltInPresets.firstOrNull { it.id == id }

/** 内置预设只替换音色，耳机校正（参数均衡）属于设备补偿，切换风格时保留 */
fun AudioEffectsProfile.applyBuiltInPreset(preset: AudioEffectsPreset): AudioEffectsProfile = copy(
    enabled = true,
    presetId = preset.id,
    sound = preset.sound.copy(
        parametricEnabled = sound.parametricEnabled,
        parametricBands = sound.parametricBands
    )
)

fun AudioEffectsProfile.applyUserPreset(preset: AudioEffectsUserPreset): AudioEffectsProfile = copy(
    enabled = true,
    presetId = preset.id,
    sound = preset.sound.normalized()
)

fun AudioEffectsSettings.saveUserPreset(
    id: String,
    name: String,
    sound: AudioEffectsSound
): AudioEffectsSettings {
    val preset = AudioEffectsUserPreset(id = id, name = name, sound = sound).normalizedOrNull() ?: return this
    val remaining = userPresets.filterNot { it.id == preset.id || it.name == preset.name }
    return copy(userPresets = (listOf(preset) + remaining).take(AUDIO_EFFECTS_USER_PRESET_LIMIT))
}

fun AudioEffectsSettings.deleteUserPreset(id: String): AudioEffectsSettings =
    copy(userPresets = userPresets.filterNot { it.id == id })
