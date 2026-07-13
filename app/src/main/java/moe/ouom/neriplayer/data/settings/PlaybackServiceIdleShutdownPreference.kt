package moe.ouom.neriplayer.data.settings

const val DEFAULT_PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES = 60

val PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTE_OPTIONS = listOf(0, 15, 30, 60, 120, 240)

object PlaybackServiceIdleShutdownPreference {
    fun normalize(minutes: Int): Int {
        return minutes.takeIf(PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTE_OPTIONS::contains)
            ?: DEFAULT_PLAYBACK_SERVICE_IDLE_SHUTDOWN_MINUTES
    }

    fun delayMs(minutes: Int): Long {
        return normalize(minutes).toLong() * 60_000L
    }
}
