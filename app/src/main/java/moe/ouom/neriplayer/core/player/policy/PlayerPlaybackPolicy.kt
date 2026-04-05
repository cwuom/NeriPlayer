package moe.ouom.neriplayer.core.player.policy

import androidx.media3.common.Player
import moe.ouom.neriplayer.core.player.model.PlaybackSoundConfig
import moe.ouom.neriplayer.core.player.model.normalizePlaybackLoudnessGainMb
import moe.ouom.neriplayer.core.player.model.normalizePlaybackPitch
import moe.ouom.neriplayer.core.player.model.normalizePlaybackSpeed
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId

internal const val PLAYBACK_PROGRESS_UPDATE_INTERVAL_MS = 80L
internal const val MAX_CONSECUTIVE_PLAYBACK_FAILURES = 10
internal const val MEDIA_URL_STALE_INTERVAL_MS = 10 * 60 * 1000L
internal const val URL_REFRESH_COOLDOWN_MS = 10 * 1000L
internal const val STATE_PERSIST_INTERVAL_MS = 15 * 1000L

enum class PlaybackCommandSource {
    LOCAL,
    REMOTE_SYNC
}

data class PlaybackCommand(
    val type: String,
    val source: PlaybackCommandSource,
    val timestampMs: Long = System.currentTimeMillis(),
    val queue: List<SongItem>? = null,
    val currentIndex: Int? = null,
    val positionMs: Long? = null,
    val force: Boolean = false
)

internal data class PlaybackStartPlan(
    val useFadeIn: Boolean,
    val fadeDurationMs: Long,
    val initialVolume: Float
)

internal data class YouTubeWarmupTargets(
    val currentVideoId: String?,
    val nextVideoId: String?,
    val preferredQuality: String
) {
    val hasWork: Boolean
        get() = currentVideoId != null || nextVideoId != null
}

internal const val RESTORED_PLAYBACK_PROTECTION_FADE_DURATION_MS = 1000L

internal fun resolvePlaybackStartPlan(
    shouldFadeIn: Boolean,
    fadeDurationMs: Long
): PlaybackStartPlan {
    val normalizedDurationMs = fadeDurationMs.coerceAtLeast(0L)
    val useFadeIn = shouldFadeIn && normalizedDurationMs > 0L
    return PlaybackStartPlan(
        useFadeIn = useFadeIn,
        fadeDurationMs = normalizedDurationMs,
        initialVolume = if (useFadeIn) 0f else 1f
    )
}

internal fun resolveManagedPlaybackStartPlan(
    playbackFadeInEnabled: Boolean,
    playbackFadeInDurationMs: Long,
    playbackCrossfadeInDurationMs: Long,
    useTrackTransitionFade: Boolean = false,
    forceStartupProtectionFade: Boolean = false
): PlaybackStartPlan {
    val targetDurationMs = when {
        useTrackTransitionFade -> playbackCrossfadeInDurationMs
        forceStartupProtectionFade && playbackFadeInEnabled ->
            maxOf(
                playbackFadeInDurationMs,
                RESTORED_PLAYBACK_PROTECTION_FADE_DURATION_MS
            )
        forceStartupProtectionFade -> RESTORED_PLAYBACK_PROTECTION_FADE_DURATION_MS
        else -> playbackFadeInDurationMs
    }
    return resolvePlaybackStartPlan(
        shouldFadeIn = useTrackTransitionFade ||
            playbackFadeInEnabled ||
            forceStartupProtectionFade,
        fadeDurationMs = targetDurationMs
    )
}

internal fun shouldForceStartupProtectionFadeOnManualResume(
    isPlayerPrepared: Boolean,
    resumePositionMs: Long,
    currentMediaUrlResolvedAtMs: Long
): Boolean {
    return !isPlayerPrepared &&
        resumePositionMs > 0L &&
        currentMediaUrlResolvedAtMs <= 0L
}

internal fun shouldRunPlaybackServiceInForeground(
    hasCurrentSong: Boolean,
    resumePlaybackRequested: Boolean,
    playJobActive: Boolean,
    pendingPauseJobActive: Boolean,
    playWhenReady: Boolean,
    isPlaying: Boolean,
    playerPlaybackState: Int
): Boolean {
    if (!hasCurrentSong) return false
    return resumePlaybackRequested ||
        playJobActive ||
        pendingPauseJobActive ||
        playWhenReady ||
        isPlaying ||
        playerPlaybackState == Player.STATE_BUFFERING
}

internal fun shouldBootstrapPlaybackServiceOnAppLaunch(
    hasCurrentSong: Boolean,
    playJobActive: Boolean,
    pendingPauseJobActive: Boolean,
    playWhenReady: Boolean,
    isPlaying: Boolean,
    playerPlaybackState: Int
): Boolean {
    if (!hasCurrentSong) return false
    return playJobActive ||
        pendingPauseJobActive ||
        playWhenReady ||
        isPlaying ||
        playerPlaybackState == Player.STATE_BUFFERING
}

internal fun resolveYouTubeWarmupTargets(
    playlist: List<SongItem>,
    currentSongIndex: Int,
    preferredQuality: String
): YouTubeWarmupTargets {
    val currentVideoId = playlist.getOrNull(currentSongIndex)
        ?.let { extractYouTubeMusicVideoId(it.mediaUri) }
    val nextVideoId = playlist.getOrNull(currentSongIndex + 1)
        ?.let { extractYouTubeMusicVideoId(it.mediaUri) }
    return YouTubeWarmupTargets(
        currentVideoId = currentVideoId,
        nextVideoId = nextVideoId,
        preferredQuality = preferredQuality
    )
}

internal fun resolvePlaybackSoundConfigForEngine(
    baseConfig: PlaybackSoundConfig,
    listenTogetherSyncPlaybackRate: Float
): PlaybackSoundConfig {
    val normalizedBaseConfig = baseConfig.copy(
        speed = normalizePlaybackSpeed(baseConfig.speed),
        pitch = normalizePlaybackPitch(baseConfig.pitch),
        loudnessGainMb = normalizePlaybackLoudnessGainMb(baseConfig.loudnessGainMb)
    )
    val resolvedSyncRate = listenTogetherSyncPlaybackRate.coerceIn(0.95f, 1.05f)
    return normalizedBaseConfig.copy(
        speed = normalizePlaybackSpeed(normalizedBaseConfig.speed * resolvedSyncRate)
    )
}
