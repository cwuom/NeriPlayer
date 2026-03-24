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

/**
 * 统一管理倍速、音调和均衡器，避免这些逻辑散在 PlayerManager 里
 */
class PlaybackEffectsController {
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

        centersHz.forEachIndexed { index, _ ->
            runCatching {
                eq.setBandLevel(index.toShort(), resolvedLevels[index].toShort())
            }
        }

        runCatching {
            if (!eq.enabled) {
                eq.enabled = true
            }
        }

        lastKnownBandCentersHz = centersHz
        lastKnownBandLevelRangeMb = bandRange
        lastEqualizerAvailable = true
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
        }.onFailure {
            lastLoudnessEnhancerAvailable = false
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

        equalizer = created
        equalizerSessionId = sessionId
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
        loudnessEnhancer = created
        loudnessEnhancerSessionId = sessionId
        return created
    }

    private fun releaseEqualizer() {
        runCatching { equalizer?.enabled = false }
        runCatching { equalizer?.release() }
        equalizer = null
        equalizerSessionId = null
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
