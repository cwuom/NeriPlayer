package moe.ouom.neriplayer.ui.screen.tab.settings.audio

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.playback.DEFAULT_PLAYBACK_PITCH
import moe.ouom.neriplayer.data.model.playback.DEFAULT_PLAYBACK_SPEED
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundState
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsPresetIds
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsProfile
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSettings
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSound
import moe.ouom.neriplayer.data.model.playback.effects.AudioEffectsSoundSection
import moe.ouom.neriplayer.data.model.playback.effects.SpeakerOptimizerSettings
import moe.ouom.neriplayer.data.model.playback.effects.isSectionEnabled
import moe.ouom.neriplayer.data.model.playback.effects.withSectionDefaults
import moe.ouom.neriplayer.data.model.playback.effects.withSectionEnabled
import kotlin.math.abs

/**
 * 音效页按分区切换。音效分区用“原声”预设即可还原，高级分区自带“全部恢复默认”；
 * 其余分区都能单独开关和重置
 */
internal enum class AudioEffectsSection(
    val labelRes: Int,
    val switchTitleRes: Int?,
    val soundSection: AudioEffectsSoundSection?
) {
    PRESETS(CoreCommonR.string.audio_effects_tab_presets, null, null),
    EQUALIZER(
        CoreCommonR.string.audio_effects_tab_equalizer,
        CoreCommonR.string.audio_effects_section_switch_equalizer,
        AudioEffectsSoundSection.EQUALIZER
    ),
    TONE(CoreCommonR.string.audio_effects_tab_tone, CoreCommonR.string.audio_effects_section_switch_tone, AudioEffectsSoundSection.TONE),
    SPACE(CoreCommonR.string.audio_effects_tab_space, CoreCommonR.string.audio_effects_section_switch_space, AudioEffectsSoundSection.SPACE),
    DYNAMICS(
        CoreCommonR.string.audio_effects_tab_dynamics,
        CoreCommonR.string.audio_effects_section_switch_dynamics,
        AudioEffectsSoundSection.DYNAMICS
    ),
    SPEAKER(CoreCommonR.string.audio_effects_tab_speaker, CoreCommonR.string.audio_effects_speaker_enabled, null),
    SPEED(CoreCommonR.string.audio_effects_tab_speed, CoreCommonR.string.audio_effects_section_switch_speed, null),
    ADVANCED(CoreCommonR.string.audio_effects_tab_advanced, null, null);

    val switchable: Boolean get() = switchTitleRes != null
    val resettable: Boolean get() = switchable
}

private val DefaultSound = AudioEffectsSound()
private const val PLAYBACK_PARAMETER_EPSILON = 0.001f

internal fun AudioEffectsSound.resetSection(section: AudioEffectsSection): AudioEffectsSound =
    section.soundSection?.let(::withSectionDefaults) ?: this

/** 重置分区不改变总开关；全部还原后标记为原声，否则标记为自定义 */
internal fun AudioEffectsProfile.resetSection(section: AudioEffectsSection): AudioEffectsProfile {
    val reset = sound.resetSection(section)
    val nextPreset = when (reset) {
        sound -> presetId
        DefaultSound -> AudioEffectsPresetIds.FLAT
        else -> AudioEffectsPresetIds.CUSTOM
    }
    return copy(presetId = nextPreset, sound = reset)
}

/** 打开某个分区时顺带打开音效总开关，否则用户会听不到刚打开的分区 */
internal fun AudioEffectsProfile.withSectionEnabled(section: AudioEffectsSoundSection, enabled: Boolean): AudioEffectsProfile =
    copy(enabled = this.enabled || enabled, sound = sound.withSectionEnabled(section, enabled))

internal fun SpeakerOptimizerSettings.resetTuning(): SpeakerOptimizerSettings = SpeakerOptimizerSettings(enabled = enabled)

internal fun AudioEffectsSection.isEnabled(profile: AudioEffectsProfile, settings: AudioEffectsSettings): Boolean {
    soundSection?.let { return profile.sound.isSectionEnabled(it) }
    return when (this) {
        AudioEffectsSection.SPEAKER -> settings.speaker.enabled
        AudioEffectsSection.SPEED -> settings.speedEnabled
        else -> true
    }
}

internal data class SpeedSectionChange(val settings: AudioEffectsSettings, val speed: Float, val pitch: Float)

/** 关闭倍速分区时记住当前倍速并恢复正常播放；重新打开时取回记住的倍速 */
internal fun AudioEffectsSettings.toggleSpeedSection(enabled: Boolean, current: PlaybackSoundState): SpeedSectionChange =
    if (enabled) {
        SpeedSectionChange(copy(speedEnabled = true), storedSpeed, storedPitch)
    } else {
        SpeedSectionChange(
            copy(speedEnabled = false, storedSpeed = current.speed, storedPitch = current.pitch),
            DEFAULT_PLAYBACK_SPEED,
            DEFAULT_PLAYBACK_PITCH
        )
    }

internal fun AudioEffectsSettings.withSpeedSectionDefaults(): AudioEffectsSettings =
    copy(speedEnabled = true, storedSpeed = DEFAULT_PLAYBACK_SPEED, storedPitch = DEFAULT_PLAYBACK_PITCH)

/** 标签上的小圆点：提示这个分区里有偏离默认的调节（包括被关掉） */
internal fun AudioEffectsSection.isModified(
    profile: AudioEffectsProfile,
    settings: AudioEffectsSettings,
    soundState: PlaybackSoundState
): Boolean = when (this) {
    AudioEffectsSection.PRESETS -> profile.presetId != AudioEffectsPresetIds.FLAT
    AudioEffectsSection.EQUALIZER,
    AudioEffectsSection.TONE,
    AudioEffectsSection.SPACE,
    AudioEffectsSection.DYNAMICS -> profile.sound.resetSection(this) != profile.sound
    AudioEffectsSection.SPEAKER -> settings.speaker.enabled
    AudioEffectsSection.SPEED -> !settings.speedEnabled ||
        abs(soundState.speed - 1f) > PLAYBACK_PARAMETER_EPSILON ||
        abs(soundState.pitch - 1f) > PLAYBACK_PARAMETER_EPSILON
    AudioEffectsSection.ADVANCED -> false
}
