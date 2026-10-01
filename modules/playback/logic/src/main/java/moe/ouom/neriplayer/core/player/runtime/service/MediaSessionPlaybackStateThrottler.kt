package moe.ouom.neriplayer.core.player.runtime.service

import android.media.session.PlaybackState
import kotlin.math.abs

private data class MediaSessionPlaybackStateSnapshot(
    val playbackState: Int,
    val positionMs: Long,
    val speed: Float,
    val controlFingerprint: Int,
    val elapsedRealtimeMs: Long,
)

private fun MediaSessionPlaybackStateSnapshot.hasSamePlaybackSettings(
    playbackState: Int,
    speed: Float,
    controlFingerprint: Int
): Boolean = this.playbackState == playbackState && this.speed == speed &&
    this.controlFingerprint == controlFingerprint

private fun MediaSessionPlaybackStateSnapshot.hasSamePresentation(
    playbackState: Int,
    positionMs: Long,
    speed: Float,
    controlFingerprint: Int
): Boolean = this.positionMs == positionMs && hasSamePlaybackSettings(playbackState, speed, controlFingerprint)

fun buildMediaSessionControlFingerprint(
    favoriteControlFingerprint: Int,
    floatingLyricsEnabled: Boolean,
): Int {
    return favoriteControlFingerprint * 2 + if (floatingLyricsEnabled) 1 else 0
}

class MediaSessionPlaybackStateThrottler(
    private val minUpdateIntervalMs: Long = 1_000L,
    private val positionDriftThresholdMs: Long = 1_500L,
) {

    private var lastSnapshot: MediaSessionPlaybackStateSnapshot? = null

    fun shouldDispatch(
        playbackState: Int,
        positionMs: Long,
        speed: Float,
        controlFingerprint: Int,
        nowElapsedRealtimeMs: Long,
        force: Boolean = false,
    ): Boolean {
        val snapshot = lastSnapshot ?: return true
        if (snapshot.hasSamePresentation(playbackState, positionMs, speed, controlFingerprint)) {
            return false
        }
        if (!snapshot.hasSamePlaybackSettings(playbackState, speed, controlFingerprint)) return true
        if (force) return true

        if (playbackState == PlaybackState.STATE_PLAYING) {
            return shouldDispatchPlayingPosition(snapshot, positionMs, nowElapsedRealtimeMs)
        }

        return positionMs != snapshot.positionMs
    }

    private fun shouldDispatchPlayingPosition(
        snapshot: MediaSessionPlaybackStateSnapshot,
        positionMs: Long,
        nowElapsedRealtimeMs: Long
    ): Boolean {
        val expectedPositionMs = snapshot.positionMs +
            ((nowElapsedRealtimeMs - snapshot.elapsedRealtimeMs) * snapshot.speed).toLong()
        val positionDriftMs = abs(positionMs - expectedPositionMs)
        return positionDriftMs >= positionDriftThresholdMs ||
            nowElapsedRealtimeMs - snapshot.elapsedRealtimeMs >= minUpdateIntervalMs
    }

    fun recordDispatch(
        playbackState: Int,
        positionMs: Long,
        speed: Float,
        controlFingerprint: Int,
        nowElapsedRealtimeMs: Long,
    ) {
        lastSnapshot = MediaSessionPlaybackStateSnapshot(
            playbackState = playbackState,
            positionMs = positionMs,
            speed = speed,
            controlFingerprint = controlFingerprint,
            elapsedRealtimeMs = nowElapsedRealtimeMs,
        )
    }
}
