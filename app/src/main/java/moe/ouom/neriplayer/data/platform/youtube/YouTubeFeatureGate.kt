package moe.ouom.neriplayer.data.platform.youtube

import java.io.IOException

internal object YouTubeFeatureGate {
    @Volatile
    private var enabled = true

    fun isEnabled(): Boolean = enabled

    fun update(enabled: Boolean) {
        this.enabled = enabled
    }
}

internal class YouTubeFeatureDisabledException : IOException(
    "YouTube is disabled in settings"
)
