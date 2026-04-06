package moe.ouom.neriplayer.core.player.policy

import androidx.media3.common.Player
import moe.ouom.neriplayer.core.player.model.PlaybackSoundConfig
import moe.ouom.neriplayer.core.player.model.normalizePlaybackLoudnessGainMb
import moe.ouom.neriplayer.core.player.model.normalizePlaybackPitch
import moe.ouom.neriplayer.core.player.model.normalizePlaybackSpeed
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.data.platform.youtube.extractYouTubeMusicVideoId

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

internal data class ManualResumePlaybackDecision(
    val resumePositionMs: Long,
    val forceStartupProtectionFade: Boolean
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

internal fun resolvePlaybackContinuationStartPlan(
    plan: PlaybackStartPlan,
    currentVolume: Float?
): PlaybackStartPlan {
    if (!plan.useFadeIn) return plan
    val resumedVolume = currentVolume?.coerceIn(0f, 1f) ?: return plan
    if (resumedVolume <= plan.initialVolume) return plan

    val remainingFraction = (1f - resumedVolume).coerceIn(0f, 1f)
    val adjustedDurationMs = when {
        remainingFraction <= 0f -> 0L
        plan.fadeDurationMs <= 0L -> 0L
        else -> maxOf(
            1L,
            (plan.fadeDurationMs * remainingFraction).toLong()
        )
    }
    return plan.copy(
        initialVolume = resumedVolume,
        fadeDurationMs = adjustedDurationMs
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

internal fun resolveManualResumePlaybackDecision(
    keepLastPlaybackProgressEnabled: Boolean,
    restoredResumePositionMs: Long,
    persistedPlaybackPositionMs: Long,
    isPlayerPrepared: Boolean,
    currentMediaUrlResolvedAtMs: Long
): ManualResumePlaybackDecision {
    val resumePositionMs = if (keepLastPlaybackProgressEnabled) {
        maxOf(restoredResumePositionMs, persistedPlaybackPositionMs).coerceAtLeast(0L)
    } else {
        0L
    }
    return ManualResumePlaybackDecision(
        resumePositionMs = resumePositionMs,
        forceStartupProtectionFade = shouldForceStartupProtectionFadeOnManualResume(
            isPlayerPrepared = isPlayerPrepared,
            resumePositionMs = resumePositionMs,
            currentMediaUrlResolvedAtMs = currentMediaUrlResolvedAtMs
        )
    )
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
    return hasCurrentSong
    // 只要恢复出了当前歌曲，就应该尽快恢复服务里的 MediaSession，
    // 这样暂停中的旧队列在进程重建后也还能继续出现在系统媒体控制里
}

internal fun shouldShowPauseButtonForPlaybackControls(
    resumePlaybackRequested: Boolean,
    pendingPauseJobActive: Boolean
): Boolean {
    return resumePlaybackRequested && !pendingPauseJobActive
}

internal fun shouldPausePlaybackWhenToggling(
    resumePlaybackRequested: Boolean,
    pendingPauseJobActive: Boolean,
    playerIsPlaying: Boolean,
    playerPlayWhenReady: Boolean,
    playJobActive: Boolean
): Boolean {
    if (pendingPauseJobActive) return false
    return resumePlaybackRequested ||
        playerIsPlaying ||
        playerPlayWhenReady ||
        playJobActive
}

internal fun shouldSyncPlaybackServiceForLocalPlaybackCommand(type: String): Boolean {
    return when (type) {
        "PLAY",
        "PLAY_PLAYLIST",
        "PLAY_FROM_QUEUE",
        "NEXT",
        "PREVIOUS" -> true
        else -> false
    }
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
