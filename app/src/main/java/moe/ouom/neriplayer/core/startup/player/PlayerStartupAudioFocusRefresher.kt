package moe.ouom.neriplayer.core.startup.player

import android.content.Context
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.audio.focus.StartupAudioFocusController

internal class PlayerStartupAudioFocusRefresher(
    private val context: Context
) {
    fun refreshForeground(
        lifecycleResumed: Boolean,
        preemptAudioFocus: Boolean,
        allowMixedPlayback: Boolean,
        usbExclusivePlayback: Boolean,
        reason: String
    ) {
        val playerInitialized = if (lifecycleResumed) {
            PlayerManager.isPlayerInitialized()
        } else {
            false
        }
        val canReadPlayerRuntimeState = lifecycleResumed && playerInitialized
        val usbExclusiveNativeActive = if (canReadPlayerRuntimeState) {
            PlayerManager.shouldUseUsbExclusiveFocusGuard()
        } else {
            false
        }
        val transportActive = if (canReadPlayerRuntimeState) {
            runCatching { PlayerManager.isTransportActive() }.getOrDefault(false)
        } else {
            false
        }
        val usbExclusiveNativePlaybackStable = if (canReadPlayerRuntimeState) {
            PlayerManager.isUsbExclusiveNativePlaybackStable()
        } else {
            false
        }
        val plan = PlayerStartupAudioFocusPlanner.planForegroundRefresh(
            lifecycleResumed = lifecycleResumed,
            playerInitialized = playerInitialized,
            reason = reason,
            usbExclusiveNativePlaybackStable = usbExclusiveNativePlaybackStable,
            preemptAudioFocus = preemptAudioFocus,
            allowMixedPlayback = allowMixedPlayback,
            usbExclusivePlayback = usbExclusivePlayback,
            usbExclusiveNativeActive = usbExclusiveNativeActive,
            transportActive = transportActive
        )
        if (plan.skipReason == PlayerStartupAudioFocusSkipReason.StableUsbNativePlayback) {
            NPLogger.d(
                "NERI-App",
                "Skipping startup audio focus refresh during stable USB native playback"
            )
        }
        apply(plan)
    }

    fun releaseForInactiveSettingsChange(
        preemptAudioFocus: Boolean,
        usbExclusivePlayback: Boolean,
        allowMixedPlayback: Boolean
    ): Boolean {
        if (
            !PlayerStartupAudioFocusPlanner.shouldReleaseWhenSettingsChangeInactive(
                preemptAudioFocus = preemptAudioFocus,
                usbExclusivePlayback = usbExclusivePlayback,
                allowMixedPlayback = allowMixedPlayback
            )
        ) {
            return false
        }
        release("settings_changed_inactive")
        return true
    }

    fun release(reason: String) {
        StartupAudioFocusController.release(reason)
    }

    private fun apply(plan: PlayerStartupAudioFocusPlan) {
        if (!plan.shouldUpdate) {
            return
        }
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
}
