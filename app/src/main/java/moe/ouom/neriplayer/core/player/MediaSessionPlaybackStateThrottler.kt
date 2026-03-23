package moe.ouom.neriplayer.core.player

import android.support.v4.media.session.PlaybackStateCompat
import kotlin.math.abs

private data class MediaSessionPlaybackStateSnapshot(
    val playbackState: Int,
    val positionMs: Long,
    val speed: Float,
    val elapsedRealtimeMs: Long,
)

internal class MediaSessionPlaybackStateThrottler(
    private val minUpdateIntervalMs: Long = 1_000L,
    private val positionDriftThresholdMs: Long = 1_500L,
) {

    private var lastSnapshot: MediaSessionPlaybackStateSnapshot? = null

    fun shouldDispatch(
        playbackState: Int,
        positionMs: Long,
        speed: Float,
        nowElapsedRealtimeMs: Long,
        force: Boolean = false,
    ): Boolean {
        val snapshot = lastSnapshot ?: return true
        if (force) return true
        if (snapshot.playbackState != playbackState) return true
        if (snapshot.speed != speed) return true

        if (playbackState == PlaybackStateCompat.STATE_PLAYING) {
            val expectedPositionMs = snapshot.positionMs +
                    ((nowElapsedRealtimeMs - snapshot.elapsedRealtimeMs) * snapshot.speed).toLong()
            val positionDriftMs = abs(positionMs - expectedPositionMs)
            if (positionDriftMs >= positionDriftThresholdMs) {
                return true
            }
            return nowElapsedRealtimeMs - snapshot.elapsedRealtimeMs >= minUpdateIntervalMs
        }

        return positionMs != snapshot.positionMs
    }

    fun recordDispatch(
        playbackState: Int,
        positionMs: Long,
        speed: Float,
        nowElapsedRealtimeMs: Long,
    ) {
        lastSnapshot = MediaSessionPlaybackStateSnapshot(
            playbackState = playbackState,
            positionMs = positionMs,
            speed = speed,
            elapsedRealtimeMs = nowElapsedRealtimeMs,
        )
    }
}
