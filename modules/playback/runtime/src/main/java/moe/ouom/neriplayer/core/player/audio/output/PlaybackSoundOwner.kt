package moe.ouom.neriplayer.core.player.audio.output

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.effects.PlaybackEffectsController
import moe.ouom.neriplayer.data.model.playback.DEFAULT_PLAYBACK_PITCH
import moe.ouom.neriplayer.data.model.playback.DEFAULT_PLAYBACK_SPEED
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundConfig
import moe.ouom.neriplayer.data.model.playback.PlaybackSoundState
import moe.ouom.neriplayer.data.model.playback.normalizePlaybackPitch
import moe.ouom.neriplayer.data.model.playback.normalizePlaybackSpeed
import moe.ouom.neriplayer.data.model.playback.normalizePlaybackVolumeBalance
import moe.ouom.neriplayer.core.player.policy.command.resolvePlaybackSoundConfigForEngine
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

internal interface PlaybackSoundPort {
    fun lyriconEnabled(): Boolean
    fun usbExclusiveEnabled(): Boolean
    fun updateLyriconSpeed(speed: Float)
    fun updateAudioOffloadPreferences(reason: String)
    fun reconfigureUsbSinkForHighResolution()
    fun debugStackHint(): String
    suspend fun persistConfig(config: PlaybackSoundConfig)
    suspend fun persistHighResolution(enabled: Boolean)
}

internal interface PlaybackSoundEngine {
    fun attachPlayer(player: ExoPlayer?): PlaybackSoundState
    fun updateConfig(config: PlaybackSoundConfig): PlaybackSoundState
    fun release(): PlaybackSoundState
}

internal class AndroidPlaybackSoundEngine(
    private val effects: PlaybackEffectsController = PlaybackEffectsController()
) : PlaybackSoundEngine {
    override fun attachPlayer(player: ExoPlayer?): PlaybackSoundState = effects.attachPlayer(player)
    override fun updateConfig(config: PlaybackSoundConfig): PlaybackSoundState = effects.updateConfig(config)
    override fun release(): PlaybackSoundState = effects.release()
}

internal class PlaybackSoundOwner(
    private var mainScope: CoroutineScope,
    private var ioScope: CoroutineScope,
    private val port: PlaybackSoundPort,
    private val engine: PlaybackSoundEngine = AndroidPlaybackSoundEngine()
) {
    @Volatile
    var config = PlaybackSoundConfig()
        private set
    var highResolutionEnabled = false
        private set
    @Volatile
    var listenTogetherSyncRate = 1f
        private set

    private val mutableState = MutableStateFlow(PlaybackSoundState())
    val state: StateFlow<PlaybackSoundState> = mutableState
    private var pendingConfig: PlaybackSoundConfig? = null
    private var applyJob: Job? = null
    private var persistJob: Job? = null

    fun rebindScopes(mainScope: CoroutineScope, ioScope: CoroutineScope) {
        applyJob?.cancel()
        persistJob?.cancel()
        applyJob = null
        persistJob = null
        this.mainScope = mainScope
        this.ioScope = ioScope
    }

    fun restoreInitialPreferences(config: PlaybackSoundConfig, highResolutionEnabled: Boolean) {
        this.config = config
        this.highResolutionEnabled = highResolutionEnabled
    }

    fun attachPlayer(player: ExoPlayer?) {
        mutableState.value = engine.attachPlayer(player)
    }

    fun releaseEngine() {
        mutableState.value = engine.release()
    }

    fun cancelPersistenceForRelease() {
        persistJob?.cancel()
        persistJob = null
    }

    fun setSpeed(speed: Float, persist: Boolean) = applyConfig(
        config.copy(speed = normalizePlaybackSpeed(speed)), persist
    )

    fun setPitch(pitch: Float, persist: Boolean) = applyConfig(
        config.copy(pitch = normalizePlaybackPitch(pitch)), persist
    )

    /** 倍速与音调同时变化时只提交一次，避免 Sonic 连续两次排空重建 */
    fun setSpeedAndPitch(speed: Float, pitch: Float, persist: Boolean) = applyConfig(
        config.copy(speed = normalizePlaybackSpeed(speed), pitch = normalizePlaybackPitch(pitch)), persist
    )

    fun setVolumeBalance(balance: Float, persist: Boolean) = applyConfig(
        config.copy(volumeBalance = normalizePlaybackVolumeBalance(balance)), persist
    )

    fun setVolumeNormalizationEnabled(enabled: Boolean, persist: Boolean) =
        applyConfig(config.copy(volumeNormalizationEnabled = enabled), persist)

    fun resetSpeedAndPitch(persist: Boolean) = applyConfig(
        config.copy(speed = DEFAULT_PLAYBACK_SPEED, pitch = DEFAULT_PLAYBACK_PITCH),
        persist
    )

    fun setHighResolutionEnabled(enabled: Boolean, persist: Boolean) {
        if (highResolutionEnabled == enabled) return
        highResolutionEnabled = enabled
        port.updateAudioOffloadPreferences("playback_high_resolution_output")
        if (persist) ioScope.launch { port.persistHighResolution(enabled) }
        if (port.usbExclusiveEnabled()) port.reconfigureUsbSinkForHighResolution()
    }

    fun setListenTogetherSyncRate(rate: Float) {
        val resolved = rate.coerceIn(0.95f, 1.05f)
        if (abs(listenTogetherSyncRate - resolved) < 0.001f) return
        NPLogger.d(
            "NERI-PlayerManager",
            "setListenTogetherSyncPlaybackRate(): old=$listenTogetherSyncRate, new=$resolved, stack=[${port.debugStackHint()}]"
        )
        listenTogetherSyncRate = resolved
        scheduleApply(config, config)
    }

    fun applyConfig(newConfig: PlaybackSoundConfig, persist: Boolean) {
        val previous = config
        config = normalize(newConfig)
        if (port.lyriconEnabled() && previous.speed != config.speed) {
            port.updateLyriconSpeed(config.speed)
        }
        scheduleApply(previous, config)
        if (persist) persistConfig(config)
    }

    fun applyConfigIfChanged(newConfig: PlaybackSoundConfig) {
        val normalized = normalize(newConfig)
        if (normalized != config) applyConfig(normalized, persist = false)
    }

    fun scheduleApply(previous: PlaybackSoundConfig, next: PlaybackSoundConfig) {
        pendingConfig = resolvePlaybackSoundConfigForEngine(
            baseConfig = next,
            listenTogetherSyncPlaybackRate = listenTogetherSyncRate,
            usbExclusivePlaybackEnabled = port.usbExclusiveEnabled()
        )
        applyJob?.cancel()
        applyJob = mainScope.launch {
            val latest = pendingConfig ?: return@launch
            pendingConfig = null
            mutableState.value = engine.updateConfig(latest)
            port.updateAudioOffloadPreferences("playback_sound_config")
        }
    }

    fun persistConfig(config: PlaybackSoundConfig) {
        persistJob?.cancel()
        persistJob = ioScope.launch {
            delay(150L.milliseconds)
            port.persistConfig(config)
        }
    }

    private fun normalize(config: PlaybackSoundConfig): PlaybackSoundConfig = config.copy(
        speed = normalizePlaybackSpeed(config.speed),
        pitch = normalizePlaybackPitch(config.pitch),
        volumeBalance = normalizePlaybackVolumeBalance(config.volumeBalance)
    )
}
