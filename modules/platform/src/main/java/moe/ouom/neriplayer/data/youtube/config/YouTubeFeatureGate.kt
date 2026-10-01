package moe.ouom.neriplayer.data.youtube.config

import java.io.IOException

object YouTubeFeatureGate {
    @Volatile
    private var enabled = true

    fun isEnabled(): Boolean = enabled

    fun update(enabled: Boolean) {
        this.enabled = enabled
    }
}

class YouTubeFeatureDisabledException : IOException(
    "YouTube is disabled in settings"
)
