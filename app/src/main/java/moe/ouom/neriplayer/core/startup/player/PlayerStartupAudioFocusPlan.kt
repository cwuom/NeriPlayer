package moe.ouom.neriplayer.core.startup.player

internal data class PlayerStartupAudioFocusPlan(
    val shouldUpdate: Boolean,
    val enabled: Boolean,
    val allowMixedPlayback: Boolean,
    val usbExclusivePlayback: Boolean,
    val usbExclusiveNativeActive: Boolean,
    val transportActive: Boolean,
    val reason: String,
    val skipReason: PlayerStartupAudioFocusSkipReason?
)

internal enum class PlayerStartupAudioFocusSkipReason {
    LifecycleNotResumed,
    PlayerNotInitialized,
    StableUsbNativePlayback
}

internal object PlayerStartupAudioFocusPlanner {
    const val APP_BOOTSTRAP_REASON = "app_bootstrap"

    fun planForegroundRefresh(
        lifecycleResumed: Boolean,
        playerInitialized: Boolean,
        reason: String,
        usbExclusiveNativePlaybackStable: Boolean,
        preemptAudioFocus: Boolean,
        allowMixedPlayback: Boolean,
        usbExclusivePlayback: Boolean,
        usbExclusiveNativeActive: Boolean,
        transportActive: Boolean
    ): PlayerStartupAudioFocusPlan {
        if (!lifecycleResumed) {
            return skipped(
                reason = reason,
                skipReason = PlayerStartupAudioFocusSkipReason.LifecycleNotResumed
            )
        }
        if (!playerInitialized) {
            return skipped(
                reason = reason,
                skipReason = PlayerStartupAudioFocusSkipReason.PlayerNotInitialized
            )
        }
        if (reason == LIFECYCLE_RESUME_REASON && usbExclusiveNativePlaybackStable) {
            return skipped(
                reason = reason,
                skipReason = PlayerStartupAudioFocusSkipReason.StableUsbNativePlayback
            )
        }

        return foregroundPlan(
            preemptAudioFocus = preemptAudioFocus,
            allowMixedPlayback = allowMixedPlayback,
            usbExclusivePlayback = usbExclusivePlayback,
            usbExclusiveNativeActive = usbExclusiveNativeActive,
            transportActive = transportActive,
            reason = reason
        )
    }

    fun planBootstrap(
        preemptAudioFocus: Boolean,
        allowMixedPlayback: Boolean,
        usbExclusivePlayback: Boolean,
        usbExclusiveNativeActive: Boolean,
        transportActive: Boolean,
        reason: String
    ): PlayerStartupAudioFocusPlan {
        return foregroundPlan(
            preemptAudioFocus = preemptAudioFocus,
            allowMixedPlayback = allowMixedPlayback,
            usbExclusivePlayback = usbExclusivePlayback,
            usbExclusiveNativeActive = usbExclusiveNativeActive,
            transportActive = transportActive,
            reason = reason
        )
    }

    fun shouldReleaseWhenSettingsChangeInactive(
        preemptAudioFocus: Boolean,
        usbExclusivePlayback: Boolean,
        allowMixedPlayback: Boolean
    ): Boolean {
        return (!preemptAudioFocus && !usbExclusivePlayback) || allowMixedPlayback
    }

    private fun foregroundPlan(
        preemptAudioFocus: Boolean,
        allowMixedPlayback: Boolean,
        usbExclusivePlayback: Boolean,
        usbExclusiveNativeActive: Boolean,
        transportActive: Boolean,
        reason: String
    ): PlayerStartupAudioFocusPlan {
        return PlayerStartupAudioFocusPlan(
            shouldUpdate = true,
            enabled = preemptAudioFocus || usbExclusiveNativeActive,
            allowMixedPlayback = allowMixedPlayback,
            usbExclusivePlayback = usbExclusivePlayback,
            usbExclusiveNativeActive = usbExclusiveNativeActive,
            transportActive = transportActive,
            reason = reason,
            skipReason = null
        )
    }

    private fun skipped(
        reason: String,
        skipReason: PlayerStartupAudioFocusSkipReason
    ): PlayerStartupAudioFocusPlan {
        return PlayerStartupAudioFocusPlan(
            shouldUpdate = false,
            enabled = false,
            allowMixedPlayback = true,
            usbExclusivePlayback = false,
            usbExclusiveNativeActive = false,
            transportActive = false,
            reason = reason,
            skipReason = skipReason
        )
    }

    private const val LIFECYCLE_RESUME_REASON = "lifecycle_resume"
}
