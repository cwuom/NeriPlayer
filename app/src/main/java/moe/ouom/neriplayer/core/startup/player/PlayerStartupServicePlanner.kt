package moe.ouom.neriplayer.core.startup.player

internal object PlayerStartupServicePlanner {
    const val APP_BOOTSTRAP_SOURCE = "app_bootstrap"
    const val PREEMPT_AUDIO_FOCUS_BOOTSTRAP_SOURCE = "preempt_audio_focus_bootstrap"

    fun plan(
        hasItems: Boolean,
        shouldBootstrapPlaybackService: Boolean,
        preemptAudioFocus: Boolean,
        allowMixedPlayback: Boolean
    ): PlayerStartupServiceStart? {
        if (!hasItems) {
            return null
        }
        if (shouldBootstrapPlaybackService) {
            return PlayerStartupServiceStart(
                source = APP_BOOTSTRAP_SOURCE,
                forceForeground = true
            )
        }
        if (preemptAudioFocus && !allowMixedPlayback) {
            return PlayerStartupServiceStart(
                source = PREEMPT_AUDIO_FOCUS_BOOTSTRAP_SOURCE,
                forceForeground = false
            )
        }
        return null
    }
}
