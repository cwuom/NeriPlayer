package moe.ouom.neriplayer.core.player.runtime.transport

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.policy.command.shouldBootstrapPlaybackServiceOnAppLaunch
import moe.ouom.neriplayer.core.player.policy.command.shouldRunPlaybackServiceInForeground
import moe.ouom.neriplayer.core.player.policy.wake.DEFAULT_PLAYBACK_WAKE_MODE
import moe.ouom.neriplayer.core.player.policy.wake.resolvePlaybackWakeMode

data class PlaybackTransportSnapshot(
    val initialized: Boolean,
    val hasCurrentSong: Boolean,
    val resumeRequested: Boolean,
    val playJobActive: Boolean,
    val pendingPauseJobActive: Boolean,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val playerPlaybackState: Int,
    val currentPositionMs: Long,
    val restoredPlaybackCanAutoResume: Boolean,
    val playerInitialized: Boolean
)

data class UsbFocusSnapshot(
    val enabled: Boolean,
    val mixedPlayback: Boolean,
    val effectiveNativePath: Boolean,
    val requestedNativePath: Boolean,
    val sinkPlaying: Boolean,
    val fallbackReason: String?,
    val nativeSource: String,
    val nativeOpened: Boolean,
    val nativeStreaming: Boolean,
    val nativeTransitioning: Boolean,
    val nativeTransportHealthy: Boolean,
    val openGateReason: String?
)

interface PlaybackTransportPort {
    fun ensureInitialized()
    fun transportSnapshot(includePosition: Boolean = false, includeRestore: Boolean = false): PlaybackTransportSnapshot
    fun usbFocusSnapshot(includeHealth: Boolean = false, includeOpenGate: Boolean = false): UsbFocusSnapshot
    fun isMainThread(): Boolean
    fun logFocusPolicy(usbEnabled: Boolean, mixedPlayback: Boolean, handleFocus: Boolean)
    fun setAudioAttributes(handleFocus: Boolean)
    fun updateForegroundFocusGuard(enabled: Boolean, usbEnabled: Boolean, mixedPlayback: Boolean,
                                   transportActive: Boolean)
    fun pauseForUsbFocusLoss(change: Int)
    fun setWakeMode(wakeMode: Int)
}

class PlaybackTransportOwner(
    private var mainScope: CoroutineScope,
    private val port: PlaybackTransportPort,
    private val nowElapsedMs: () -> Long
) {
    private var lastAutoTrackAdvanceAtMs = 0L
    @Volatile
    private var lastUsbFocusDisruptionAtMs = 0L
    private var currentWakeMode = DEFAULT_PLAYBACK_WAKE_MODE

    fun rebindScope(mainScope: CoroutineScope) {
        this.mainScope = mainScope
    }

    fun resetForRelease() {
        lastAutoTrackAdvanceAtMs = 0L
    }

    fun markAutoTrackAdvance() {
        lastAutoTrackAdvanceAtMs = nowElapsedMs()
    }

    fun isTransportActive(): Boolean {
        port.ensureInitialized()
        return isTransportActiveWithoutInitialization()
    }

    fun isTransportActiveWithoutInitialization(): Boolean =
        port.transportSnapshot().isActive()

    fun shouldRunForegroundService(): Boolean {
        port.ensureInitialized()
        val state = port.transportSnapshot()
        if (!state.initialized || !state.hasCurrentSong) return false
        return shouldRunPlaybackServiceInForeground(
            hasCurrentSong = state.hasCurrentSong,
            resumePlaybackRequested = state.resumeRequested,
            playJobActive = state.playJobActive,
            pendingPauseJobActive = state.pendingPauseJobActive,
            playWhenReady = state.playWhenReady,
            isPlaying = state.isPlaying,
            playerPlaybackState = state.playerPlaybackState
        )
    }

    fun shouldBootstrapService(): Boolean {
        port.ensureInitialized()
        val state = port.transportSnapshot(includeRestore = true)
        if (!state.initialized || !state.hasCurrentSong) return false
        return shouldBootstrapPlaybackServiceOnAppLaunch(
            hasCurrentSong = true,
            hasPendingRestoredPlaybackResume = state.restoredPlaybackCanAutoResume,
            resumePlaybackRequested = state.resumeRequested,
            playJobActive = state.playJobActive,
            pendingPauseJobActive = state.pendingPauseJobActive,
            playWhenReady = state.playWhenReady,
            isPlaying = state.isPlaying,
            playerPlaybackState = state.playerPlaybackState
        )
    }

    fun isBuffering(): Boolean {
        port.ensureInitialized()
        val state = port.transportSnapshot()
        if (!state.initialized || !state.isActive()) return false
        return state.hasBufferingIntent()
    }

    private fun PlaybackTransportSnapshot.hasBufferingIntent(): Boolean =
        playJobActive || playerPlaybackState == Player.STATE_BUFFERING

    fun shouldIgnoreExternalPause(source: String): Boolean {
        port.ensureInitialized()
        val state = port.transportSnapshot(includePosition = true)
        if (!state.initialized || !state.hasCurrentSong) return false
        if (source.isUserInitiatedExternalCommand()) return false
        if (shouldIgnoreUsbFocusPause(source, state)) return true
        return shouldGuardAutoTrackAdvancePause(state)
    }

    private fun shouldGuardAutoTrackAdvancePause(state: PlaybackTransportSnapshot): Boolean {
        if (!state.resumeRequested) return false
        val ageMs = nowElapsedMs() - lastAutoTrackAdvanceAtMs
        if (ageMs !in 0L..AUTO_TRANSITION_EXTERNAL_PAUSE_GUARD_MS) return false
        return state.isAutoAdvancePauseCandidate()
    }

    private fun PlaybackTransportSnapshot.isAutoAdvancePauseCandidate(): Boolean {
        if (playJobActive || playerPlaybackState == Player.STATE_ENDED) return true
        return playWhenReady && isAtStartOfReadyOrBufferingTrack()
    }

    private fun PlaybackTransportSnapshot.isAtStartOfReadyOrBufferingTrack(): Boolean {
        if (playerPlaybackState != Player.STATE_BUFFERING && playerPlaybackState != Player.STATE_READY) return false
        return currentPositionMs <= AUTO_TRANSITION_BUFFER_POSITION_GUARD_MS
    }

    fun markUsbFocusDisrupted(change: Int) {
        markUsbShortDisruption("audio_focus:$change")
    }

    fun markUsbShortDisruption(reason: String) {
        val usb = port.usbFocusSnapshot(includeOpenGate = true)
        if (!usb.enabled) return
        lastUsbFocusDisruptionAtMs = nowElapsedMs()
        val state = port.transportSnapshot()
        NPLogger.d(
            "NERI-PlayerManager",
            "USB exclusive short disruption noted: reason=$reason " +
                "enabled=${usb.enabled} allowMixed=${usb.mixedPlayback} " +
                "resumeRequested=${state.resumeRequested} playWhenReady=${state.playWhenReady} " +
                "isPlaying=${state.isPlaying} nativeSource=${usb.nativeSource} " +
                "nativeOpened=${usb.nativeOpened} nativeStreaming=${usb.nativeStreaming} " +
                "openGate=${usb.openGateReason ?: "open"}"
        )
    }

    fun isRecentUsbFocusDisruption(): Boolean {
        val usb = port.usbFocusSnapshot()
        if (!usb.isExclusive()) return false
        return isWithinUsbFocusPauseGuard()
    }

    private fun isWithinUsbFocusPauseGuard(): Boolean {
        val ageMs = nowElapsedMs() - lastUsbFocusDisruptionAtMs
        return ageMs in 0L..USB_EXCLUSIVE_FOCUS_PAUSE_GUARD_MS
    }

    fun pauseForUsbFocusLoss(change: Int) {
        val usb = port.usbFocusSnapshot()
        val state = port.transportSnapshot()
        if (!usb.isExclusive()) return
        if (!state.hasInitializedPlayer()) return
        if (!port.isMainThread()) {
            mainScope.launch { pauseForUsbFocusLoss(change) }
            return
        }
        if (!state.hasPlaybackIntent()) return
        NPLogger.w(
            "NERI-PlayerManager",
            "pause USB exclusive playback after audio focus loss: change=$change " +
                "playWhenReady=${state.playWhenReady} isPlaying=${state.isPlaying}"
        )
        port.pauseForUsbFocusLoss(change)
    }

    private fun UsbFocusSnapshot.isExclusive(): Boolean = enabled && !mixedPlayback

    private fun PlaybackTransportSnapshot.hasInitializedPlayer(): Boolean = initialized && playerInitialized

    private fun PlaybackTransportSnapshot.hasPlaybackIntent(): Boolean =
        resumeRequested || playWhenReady || isPlaying

    fun applyAudioFocusPolicy() {
        if (!port.transportSnapshot().playerInitialized) return
        if (port.isMainThread()) applyAudioFocusPolicyOnMainThread()
        else mainScope.launch { applyAudioFocusPolicyOnMainThread() }
    }

    fun applyAudioFocusPolicyOnMainThread() {
        if (!port.transportSnapshot().playerInitialized) return
        val usb = port.usbFocusSnapshot()
        val useGuard = usb.shouldUseFocusGuard()
        val handleFocus = !usb.mixedPlayback && !usb.shouldBypassPlatformFocus()
        port.logFocusPolicy(usb.enabled, usb.mixedPlayback, handleFocus)
        port.setAudioAttributes(handleFocus)
        port.updateForegroundFocusGuard(
            enabled = useGuard,
            usbEnabled = usb.enabled,
            mixedPlayback = usb.mixedPlayback,
            transportActive = isTransportActiveWithoutInitialization()
        )
    }

    fun shouldUseUsbFocusGuard(): Boolean = port.usbFocusSnapshot().shouldUseFocusGuard()

    fun shouldBypassPlatformFocus(): Boolean = port.usbFocusSnapshot().shouldBypassPlatformFocus()

    fun isUsbNativePlaybackStable(): Boolean = port.usbFocusSnapshot(includeHealth = true).isStable()

    fun isUsbPlaybackActiveForForegroundService(): Boolean {
        val state = port.transportSnapshot()
        if (!state.playerInitialized || !state.isActive()) return false
        return port.usbFocusSnapshot().hasNativePlaybackActivity()
    }

    private fun UsbFocusSnapshot.hasNativePlaybackActivity(): Boolean {
        if (!enabled) return false
        return hasOpenNativeStream() || hasRequestedNativePath()
    }

    private fun UsbFocusSnapshot.hasOpenNativeStream(): Boolean = nativeStreaming || nativeOpened

    private fun UsbFocusSnapshot.hasRequestedNativePath(): Boolean =
        effectiveNativePath || requestedNativePath

    fun applyWakeModeForUrl(url: String?) {
        val mode = resolvePlaybackWakeMode(url)
        if (mode == currentWakeMode) return
        port.setWakeMode(mode)
        currentWakeMode = mode
    }

    fun applyInitialWakeMode() {
        port.setWakeMode(DEFAULT_PLAYBACK_WAKE_MODE)
        currentWakeMode = DEFAULT_PLAYBACK_WAKE_MODE
    }

    private fun shouldIgnoreUsbFocusPause(source: String, state: PlaybackTransportSnapshot): Boolean {
        val usb = port.usbFocusSnapshot()
        if (!usb.isExclusive()) return false
        if (source.contains("stop", ignoreCase = true)) return false
        if (!state.hasPlaybackIntent()) return false
        return isRecentUsbFocusDisruption()
    }

    private fun PlaybackTransportSnapshot.isActive(): Boolean = initialized && hasCurrentSong &&
        hasTransportIntent()

    private fun PlaybackTransportSnapshot.hasTransportIntent(): Boolean =
        resumeRequested || playJobActive || pendingPauseJobActive || playWhenReady || isPlaying

    private fun UsbFocusSnapshot.shouldUseFocusGuard(): Boolean = enabled && !mixedPlayback &&
        effectiveNativePath && sinkPlaying && nativeSource == "player_pcm" && nativeStreaming

    private fun UsbFocusSnapshot.shouldBypassPlatformFocus(): Boolean {
        if (!enabled || mixedPlayback) return false
        if (nativePathRequiresFocusBypass()) return true
        val reason = fallbackReason ?: return true
        return reason.isTemporaryNativeFallback()
    }

    private fun UsbFocusSnapshot.nativePathRequiresFocusBypass(): Boolean =
        nativeTransitioning || hasOpenPlayerPcm() || effectiveNativePath

    private fun UsbFocusSnapshot.hasOpenPlayerPcm(): Boolean = nativeOpened && nativeSource == "player_pcm"

    private fun String.isTemporaryNativeFallback(): Boolean =
        TEMPORARY_FALLBACK_PREFIXES.any { startsWith(it) } ||
            TEMPORARY_FALLBACK_FRAGMENTS.any { contains(it, ignoreCase = true) }

    private fun UsbFocusSnapshot.isStable(): Boolean {
        if (!hasStableNativePath()) return false
        return nativeOpened && nativeStreaming && !nativeTransitioning && nativeTransportHealthy
    }

    private fun UsbFocusSnapshot.hasStableNativePath(): Boolean =
        enabled && !mixedPlayback && effectiveNativePath && sinkPlaying &&
            fallbackReason == null && nativeSource == "player_pcm"

    private fun String.isUserInitiatedExternalCommand(): Boolean =
        equals("intent_pause", ignoreCase = true) ||
            equals("intent_stop", ignoreCase = true) ||
            startsWith("media_session_", ignoreCase = true)

    private companion object {
        const val AUTO_TRANSITION_EXTERNAL_PAUSE_GUARD_MS = 2_000L
        const val AUTO_TRANSITION_BUFFER_POSITION_GUARD_MS = 1_500L
        const val USB_EXCLUSIVE_FOCUS_PAUSE_GUARD_MS = 3_000L
        val TEMPORARY_FALLBACK_PREFIXES = listOf("native_open_deferred", "native_reopen_cooling_down")
        val TEMPORARY_FALLBACK_FRAGMENTS = listOf("transport", "start", "play")
    }
}
