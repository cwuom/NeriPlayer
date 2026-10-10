package moe.ouom.neriplayer.data.model.playback.effects

/** 可以单独开关、单独恢复默认的音色分区 */
enum class AudioEffectsSoundSection {
    EQUALIZER,
    TONE,
    SPACE,
    DYNAMICS
}

private val SoundDefaults = AudioEffectsSound()

fun AudioEffectsSound.isSectionEnabled(section: AudioEffectsSoundSection): Boolean = when (section) {
    AudioEffectsSoundSection.EQUALIZER -> equalizerEnabled
    AudioEffectsSoundSection.TONE -> toneEnabled
    AudioEffectsSoundSection.SPACE -> spaceEnabled
    AudioEffectsSoundSection.DYNAMICS -> dynamicsEnabled
}

fun AudioEffectsSound.withSectionEnabled(section: AudioEffectsSoundSection, enabled: Boolean): AudioEffectsSound =
    when (section) {
        AudioEffectsSoundSection.EQUALIZER -> copy(equalizerEnabled = enabled)
        AudioEffectsSoundSection.TONE -> copy(toneEnabled = enabled)
        AudioEffectsSoundSection.SPACE -> copy(spaceEnabled = enabled)
        AudioEffectsSoundSection.DYNAMICS -> copy(dynamicsEnabled = enabled)
    }

/** 把一个分区的全部调节恢复默认，并重新打开这个分区 */
fun AudioEffectsSound.withSectionDefaults(section: AudioEffectsSoundSection): AudioEffectsSound = when (section) {
    AudioEffectsSoundSection.EQUALIZER -> copy(
        preampDb = SoundDefaults.preampDb,
        equalizerEnabled = SoundDefaults.equalizerEnabled,
        equalizerBandsDb = SoundDefaults.equalizerBandsDb,
        parametricEnabled = SoundDefaults.parametricEnabled,
        parametricBands = SoundDefaults.parametricBands
    )
    AudioEffectsSoundSection.TONE -> copy(
        toneEnabled = SoundDefaults.toneEnabled,
        bassDb = SoundDefaults.bassDb,
        bassFrequencyHz = SoundDefaults.bassFrequencyHz,
        trebleDb = SoundDefaults.trebleDb,
        trebleFrequencyHz = SoundDefaults.trebleFrequencyHz,
        virtualBass = SoundDefaults.virtualBass,
        virtualBassFrequencyHz = SoundDefaults.virtualBassFrequencyHz,
        warmth = SoundDefaults.warmth,
        clarity = SoundDefaults.clarity,
        clarityFrequencyHz = SoundDefaults.clarityFrequencyHz,
        vocal = SoundDefaults.vocal,
        vocalRemoval = SoundDefaults.vocalRemoval
    )
    AudioEffectsSoundSection.SPACE -> copy(
        spaceEnabled = SoundDefaults.spaceEnabled,
        stereoWidth = SoundDefaults.stereoWidth,
        monoBassHz = SoundDefaults.monoBassHz,
        mono = SoundDefaults.mono,
        swapChannels = SoundDefaults.swapChannels,
        crossfeed = SoundDefaults.crossfeed,
        surround = SoundDefaults.surround,
        reverb = SoundDefaults.reverb,
        reverbRoom = SoundDefaults.reverbRoom,
        reverbDamping = SoundDefaults.reverbDamping,
        reverbPreDelayMs = SoundDefaults.reverbPreDelayMs
    )
    AudioEffectsSoundSection.DYNAMICS -> copy(
        dynamicsEnabled = SoundDefaults.dynamicsEnabled,
        compressorEnabled = SoundDefaults.compressorEnabled,
        compressorThresholdDb = SoundDefaults.compressorThresholdDb,
        compressorRatio = SoundDefaults.compressorRatio,
        compressorAttackMs = SoundDefaults.compressorAttackMs,
        compressorReleaseMs = SoundDefaults.compressorReleaseMs,
        compressorKneeDb = SoundDefaults.compressorKneeDb,
        compressorMakeupDb = SoundDefaults.compressorMakeupDb,
        outputGainDb = SoundDefaults.outputGainDb,
        limiterEnabled = SoundDefaults.limiterEnabled,
        limiterCeilingDb = SoundDefaults.limiterCeilingDb,
        limiterReleaseMs = SoundDefaults.limiterReleaseMs
    )
}

/**
 * 实际送进 DSP 的音色：关闭的分区按默认值处理，用户的调节仍保存在设置里。
 * 动态分区的默认值包含防破音保护，关掉分区也不会失去削波保护
 */
fun AudioEffectsSound.effective(): AudioEffectsSound =
    AudioEffectsSoundSection.entries.fold(this) { sound, section ->
        if (sound.isSectionEnabled(section)) sound else sound.withSectionDefaults(section)
    }
