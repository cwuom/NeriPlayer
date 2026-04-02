package moe.ouom.neriplayer.core.player

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import moe.ouom.neriplayer.core.player.model.DEFAULT_EQUALIZER_BAND_LEVEL_RANGE_MB
import moe.ouom.neriplayer.core.player.model.PlaybackEqualizerBand
import moe.ouom.neriplayer.core.player.model.PlaybackSoundConfig
import moe.ouom.neriplayer.core.player.model.PlaybackSoundState
import moe.ouom.neriplayer.core.player.model.defaultPlaybackEqualizerBands
import moe.ouom.neriplayer.core.player.model.normalizePlaybackLoudnessGainMb
import moe.ouom.neriplayer.core.player.model.normalizePlaybackPitch
import moe.ouom.neriplayer.core.player.model.normalizePlaybackSpeed
import moe.ouom.neriplayer.core.player.model.resolvePlaybackEqualizerBandLevelsMb
import moe.ouom.neriplayer.util.NPLogger

/**
 * 统一管理倍速、音调和均衡器，避免这些逻辑散在 PlayerManager 里
 */
class PlaybackEffectsController {
    companion object {
        private const val TAG = "PlaybackEffects"
    }

    private var player: ExoPlayer? = null
    private var equalizer: Equalizer? = null
    private var equalizerSessionId: Int? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessEnhancerSessionId: Int? = null
    private var config = PlaybackSoundConfig()
    private var currentAudioSessionId: Int? = null
    private var lastKnownBandCentersHz = defaultPlaybackEqualizerBands().map { it.centerFreqHz }
    private var lastKnownBandLevelRangeMb = DEFAULT_EQUALIZER_BAND_LEVEL_RANGE_MB
    private var lastEqualizerAvailable = false
    private var lastLoudnessEnhancerAvailable = false
    private var lastAppliedEqualizerLevels: List<Int> = emptyList()
    private var lastAppliedEqualizerEnabled = false

    fun attachPlayer(player: ExoPlayer?): PlaybackSoundState {
        this.player = player
        applyPlaybackParameters()
        val sessionId = player?.audioSessionId
        return onAudioSessionIdChanged(sessionId)
    }

    fun updateConfig(newConfig: PlaybackSoundConfig): PlaybackSoundState {
        val previousConfig = config
        config = newConfig.copy(
            speed = normalizePlaybackSpeed(newConfig.speed),
            pitch = normalizePlaybackPitch(newConfig.pitch),
            loudnessGainMb = normalizePlaybackLoudnessGainMb(newConfig.loudnessGainMb)
        )
        if (
            previousConfig.speed != config.speed ||
            previousConfig.pitch != config.pitch
        ) {
            applyPlaybackParameters()
        }
        if (
            previousConfig.equalizerEnabled != config.equalizerEnabled ||
            previousConfig.presetId != config.presetId ||
            previousConfig.customBandLevelsMb != config.customBandLevelsMb
        ) {
            applyEqualizer()
        }
        if (previousConfig.loudnessGainMb != config.loudnessGainMb) {
            applyLoudnessEnhancer()
        }
        return buildState()
    }

    fun onAudioSessionIdChanged(audioSessionId: Int?): PlaybackSoundState {
        val normalizedSessionId = audioSessionId
            ?.takeIf { it != C.AUDIO_SESSION_ID_UNSET && it > 0 }
        if (currentAudioSessionId != normalizedSessionId) {
            currentAudioSessionId = normalizedSessionId
            if (equalizerSessionId != normalizedSessionId) {
                releaseEqualizer()
            }
            if (loudnessEnhancerSessionId != normalizedSessionId) {
                releaseLoudnessEnhancer()
            }
        }
        applyEqualizer()
        applyLoudnessEnhancer()
        return buildState()
    }

    fun release(): PlaybackSoundState {
        releaseEqualizer()
        releaseLoudnessEnhancer()
        player = null
        currentAudioSessionId = null
        return buildState()
    }

    private fun applyPlaybackParameters() {
        val currentPlayer = player ?: return
        runCatching {
            currentPlayer.playbackParameters = PlaybackParameters(
                config.speed,
                config.pitch
            )
        }
    }

    private fun applyEqualizer() {
        val sessionId = currentAudioSessionId ?: run {
            releaseEqualizer()
            return
        }

        val eq = ensureEqualizer(sessionId) ?: run {
            lastEqualizerAvailable = false
            return
        }

        val bandRange = runCatching { eq.bandLevelRange }
            .getOrNull()
            ?.takeIf { it.size >= 2 }
            ?.let { range -> range[0].toInt()..range[1].toInt() }
            ?: lastKnownBandLevelRangeMb
        val centersHz = (0 until eq.numberOfBands.toInt()).map { index ->
            runCatching { eq.getCenterFreq(index.toShort()) / 1000 }
                .getOrDefault(lastKnownBandCentersHz.getOrElse(index) { 1000 })
        }

        lastKnownBandCentersHz = centersHz
        lastKnownBandLevelRangeMb = bandRange
        lastEqualizerAvailable = true

        NPLogger.d(
            TAG,
            "applyEqualizer(): sessionId=$sessionId, enabled=${config.equalizerEnabled}, preset=${config.presetId}, bands=${centersHz.size}, range=${bandRange.first}..${bandRange.last}"
        )

        val resolvedLevels = if (config.equalizerEnabled) {
            resolvePlaybackEqualizerBandLevelsMb(
                presetId = config.presetId,
                customBandLevelsMb = config.customBandLevelsMb,
                bandCentersHz = centersHz,
                bandLevelRangeMb = bandRange
            )
        } else {
            List(centersHz.size) { 0 }
        }
        val equalizerHeadroomMb = resolvedLevels.maxOrNull()?.coerceAtLeast(0) ?: 0
        val appliedLevels = if (config.equalizerEnabled && equalizerHeadroomMb > 0) {
            resolvedLevels.map { it - equalizerHeadroomMb }
        } else {
            resolvedLevels
        }

        if (
            lastAppliedEqualizerEnabled == config.equalizerEnabled &&
            lastAppliedEqualizerLevels == appliedLevels &&
            runCatching { eq.enabled }.getOrDefault(false)
        ) {
            return
        }

        val updatedAppliedLevels = MutableList(appliedLevels.size) { index ->
            lastAppliedEqualizerLevels.getOrNull(index) ?: Int.MIN_VALUE
        }
        centersHz.forEachIndexed { index, _ ->
            val targetLevel = appliedLevels.getOrElse(index) { 0 }
            val previousLevel = lastAppliedEqualizerLevels.getOrNull(index)
            if (previousLevel == targetLevel) {
                updatedAppliedLevels[index] = targetLevel
                return@forEachIndexed
            }
            runCatching {
                eq.setBandLevel(index.toShort(), targetLevel.toShort())
                updatedAppliedLevels[index] = targetLevel
            }.onFailure {
                NPLogger.w(TAG, "applyEqualizer(): failed to set band[$index]=$targetLevel: ${it.message}")
            }
        }

        runCatching {
            if (!eq.enabled) {
                eq.enabled = true
            }
        }.onFailure {
            NPLogger.e(TAG, "applyEqualizer(): failed to enable equalizer", it)
        }

        lastAppliedEqualizerEnabled =
            config.equalizerEnabled && runCatching { eq.enabled }.getOrDefault(false)
        lastAppliedEqualizerLevels = updatedAppliedLevels

        if (!config.equalizerEnabled) {
            NPLogger.d(TAG, "applyEqualizer(): flattened equalizer for sessionId=$sessionId instead of disabling effect")
            return
        }

        NPLogger.d(
            TAG,
            "applyEqualizer(): applied preset=${config.presetId}, rawMin=${resolvedLevels.minOrNull()}, rawMax=${resolvedLevels.maxOrNull()}, headroomMb=$equalizerHeadroomMb, appliedMin=${appliedLevels.minOrNull()}, appliedMax=${appliedLevels.maxOrNull()}, loudnessGainMb=${config.loudnessGainMb}"
        )
    }

    private fun applyLoudnessEnhancer() {
        val sessionId = currentAudioSessionId ?: run {
            releaseLoudnessEnhancer()
            return
        }

        val enhancer = ensureLoudnessEnhancer(sessionId) ?: run {
            lastLoudnessEnhancerAvailable = false
            return
        }

        runCatching {
            enhancer.setTargetGain(config.loudnessGainMb)
            enhancer.enabled = config.loudnessGainMb > 0
            lastLoudnessEnhancerAvailable = true
            NPLogger.d(
                TAG,
                "applyLoudnessEnhancer(): sessionId=$sessionId, gainMb=${config.loudnessGainMb}, enabled=${config.loudnessGainMb > 0}"
            )
        }.onFailure {
            lastLoudnessEnhancerAvailable = false
            NPLogger.e(TAG, "applyLoudnessEnhancer(): failed", it)
        }
    }

    private fun ensureEqualizer(sessionId: Int): Equalizer? {
        val existing = equalizer
        if (existing != null && equalizerSessionId == sessionId) {
            return existing
        }

        releaseEqualizer()
        val created = runCatching {
            Equalizer(0, sessionId).apply { enabled = false }
        }.getOrNull() ?: return null

        NPLogger.d(TAG, "ensureEqualizer(): created equalizer for sessionId=$sessionId")
        equalizer = created
        equalizerSessionId = sessionId
        lastAppliedEqualizerLevels = emptyList()
        lastAppliedEqualizerEnabled = false
        lastKnownBandLevelRangeMb = runCatching { created.bandLevelRange }
            .getOrNull()
            ?.takeIf { it.size >= 2 }
            ?.let { range -> range[0].toInt()..range[1].toInt() }
            ?: lastKnownBandLevelRangeMb
        lastKnownBandCentersHz = (0 until created.numberOfBands.toInt()).map { index ->
            runCatching { created.getCenterFreq(index.toShort()) / 1000 }
                .getOrDefault(lastKnownBandCentersHz.getOrElse(index) { 1000 })
        }
        return created
    }

    private fun ensureLoudnessEnhancer(sessionId: Int): LoudnessEnhancer? {
        val existing = loudnessEnhancer
        if (existing != null && loudnessEnhancerSessionId == sessionId) {
            return existing
        }

        releaseLoudnessEnhancer()
        val created = runCatching {
            LoudnessEnhancer(sessionId).apply { enabled = false }
        }.getOrNull() ?: return null
        NPLogger.d(TAG, "ensureLoudnessEnhancer(): created enhancer for sessionId=$sessionId")
        loudnessEnhancer = created
        loudnessEnhancerSessionId = sessionId
        return created
    }

    private fun releaseEqualizer() {
        runCatching { equalizer?.enabled = false }
        runCatching { equalizer?.release() }
        equalizer = null
        equalizerSessionId = null
        lastAppliedEqualizerLevels = emptyList()
        lastAppliedEqualizerEnabled = false
        lastEqualizerAvailable = false
    }

    private fun releaseLoudnessEnhancer() {
        runCatching { loudnessEnhancer?.enabled = false }
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        loudnessEnhancerSessionId = null
        lastLoudnessEnhancerAvailable = false
    }

    private fun buildState(): PlaybackSoundState {
        val bandLevels = resolvePlaybackEqualizerBandLevelsMb(
            presetId = config.presetId,
            customBandLevelsMb = config.customBandLevelsMb,
            bandCentersHz = lastKnownBandCentersHz,
            bandLevelRangeMb = lastKnownBandLevelRangeMb
        )

        val bands = lastKnownBandCentersHz.mapIndexed { index, centerFreqHz ->
            PlaybackEqualizerBand(
                index = index,
                centerFreqHz = centerFreqHz,
                levelMb = bandLevels.getOrElse(index) { 0 }
            )
        }

        return PlaybackSoundState(
            speed = config.speed,
            pitch = config.pitch,
            loudnessGainMb = config.loudnessGainMb,
            equalizerEnabled = config.equalizerEnabled,
            presetId = config.presetId,
            bands = bands,
            bandLevelRangeMb = lastKnownBandLevelRangeMb,
            audioSessionId = currentAudioSessionId,
            equalizerAvailable = lastEqualizerAvailable && currentAudioSessionId != null,
            loudnessEnhancerAvailable = lastLoudnessEnhancerAvailable && currentAudioSessionId != null
        )
    }
}
