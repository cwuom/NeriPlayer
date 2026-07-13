package moe.ouom.neriplayer.core.startup.player

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.audio.focus.StartupAudioFocusController
import moe.ouom.neriplayer.core.player.persistence.preloadRestoredStateSnapshot
import moe.ouom.neriplayer.data.settings.PlaybackPreferenceSnapshot
import moe.ouom.neriplayer.data.settings.readPlaybackPreferenceSnapshot

internal class PlayerStartupBootstrapper(
    private val app: Application,
    private val context: Context = app,
    private val awaitUiFrameBeforePlayerInit: suspend () -> Unit = {}
) {
    suspend fun bootstrap(): PlayerStartupBootstrapResult {
        val playbackPreferences = withContext(Dispatchers.IO) {
            readPlaybackPreferenceSnapshot(app)
        }
        val restoredStateSnapshot = preloadRestoredStateSnapshot(
            app = app,
            keepLastPlaybackProgressEnabled = playbackPreferences.keepLastPlaybackProgress,
            keepPlaybackModeStateEnabled = playbackPreferences.keepPlaybackModeState
        )
        awaitUiFrameBeforePlayerInit()
        PlayerManager.initializePreloaded(
            app = app,
            startupPlaybackPreferences = playbackPreferences,
            restoredStateSnapshot = restoredStateSnapshot
        )
        NPLogger.d("NERI-App", "PlayerManager.initialize called")
        NPLogger.d(
            "NERI-App",
            "Player bootstrap state hasItems=${PlayerManager.hasItems()} " +
                "transportActive=${PlayerManager.isTransportActive()} " +
                "isPlaying=${PlayerManager.isPlayingFlow.value}"
        )

        val hasItems = PlayerManager.hasItems()
        val shouldBootstrapPlaybackService =
            hasItems && PlayerManager.shouldBootstrapPlaybackServiceOnAppLaunch()
        val useUsbExclusiveFocusGuard = PlayerManager.shouldUseUsbExclusiveFocusGuard()
        updateStartupAudioFocus(
            playbackPreferences = playbackPreferences,
            shouldBootstrapPlaybackService = shouldBootstrapPlaybackService,
            usbExclusiveNativeActive = useUsbExclusiveFocusGuard
        )

        val serviceStart = PlayerStartupServicePlanner.plan(
            hasItems = hasItems,
            shouldBootstrapPlaybackService = shouldBootstrapPlaybackService,
            preemptAudioFocus = playbackPreferences.preemptAudioFocus,
            allowMixedPlayback = playbackPreferences.allowMixedPlayback
        )
        logServiceStartPlan(serviceStart, hasItems)
        return PlayerStartupBootstrapResult(serviceStart = serviceStart)
    }

    private fun updateStartupAudioFocus(
        playbackPreferences: PlaybackPreferenceSnapshot,
        shouldBootstrapPlaybackService: Boolean,
        usbExclusiveNativeActive: Boolean
    ) {
        val plan = PlayerStartupAudioFocusPlanner.planBootstrap(
            preemptAudioFocus = playbackPreferences.preemptAudioFocus,
            allowMixedPlayback = playbackPreferences.allowMixedPlayback,
            usbExclusivePlayback = playbackPreferences.usbExclusivePlayback,
            usbExclusiveNativeActive = usbExclusiveNativeActive,
            transportActive = PlayerManager.isTransportActive() || shouldBootstrapPlaybackService,
            reason = PlayerStartupAudioFocusPlanner.APP_BOOTSTRAP_REASON
        )
        StartupAudioFocusController.updateForForeground(
            context = context,
            enabled = plan.enabled,
            allowMixedPlayback = plan.allowMixedPlayback,
            usbExclusivePlayback = plan.usbExclusivePlayback,
            usbExclusiveNativeActive = plan.usbExclusiveNativeActive,
            transportActive = plan.transportActive,
            reason = plan.reason
        )
    }

    private fun logServiceStartPlan(
        serviceStart: PlayerStartupServiceStart?,
        hasItems: Boolean
    ) {
        when (serviceStart?.source) {
            PlayerStartupServicePlanner.APP_BOOTSTRAP_SOURCE ->
                NPLogger.d("NERI-App", "Starting audio service from app bootstrap")
            PlayerStartupServicePlanner.PREEMPT_AUDIO_FOCUS_BOOTSTRAP_SOURCE ->
                NPLogger.d("NERI-App", "Starting audio service for preempt audio focus session")
            else -> NPLogger.d(
                "NERI-App",
                "Skip audio service bootstrap: hasItems=$hasItems " +
                    "currentSong=${PlayerManager.currentSongFlow.value != null}"
            )
        }
    }
}
