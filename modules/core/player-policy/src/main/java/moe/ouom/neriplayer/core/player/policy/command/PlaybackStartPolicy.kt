package moe.ouom.neriplayer.core.player.policy.command

data class PlaybackStartPlan(
    val useFadeIn: Boolean,
    val fadeDurationMs: Long,
    val initialVolume: Float,
    val allowUsbExclusiveFade: Boolean = false
)

data class ManualResumePlaybackDecision(
    val resumePositionMs: Long,
    val forceStartupProtectionFade: Boolean
)

const val RESTORED_PLAYBACK_PROTECTION_FADE_DURATION_MS = 1000L
const val USB_TRACK_TRANSITION_PROTECTION_FADE_DURATION_MS = 20L

fun resolvePlaybackStartPlan(
    shouldFadeIn: Boolean,
    fadeDurationMs: Long,
    allowUsbExclusiveFade: Boolean = false
): PlaybackStartPlan {
    val normalizedDurationMs = fadeDurationMs.coerceAtLeast(0L)
    val useFadeIn = shouldFadeIn && normalizedDurationMs > 0L
    return PlaybackStartPlan(
        useFadeIn = useFadeIn,
        fadeDurationMs = normalizedDurationMs,
        initialVolume = if (useFadeIn) 0f else 1f,
        allowUsbExclusiveFade = allowUsbExclusiveFade
    )
}

fun resolveNoFadePlaybackStartPlan(): PlaybackStartPlan {
    return resolvePlaybackStartPlan(
        shouldFadeIn = false,
        fadeDurationMs = 0L
    )
}

fun resolveManagedPlaybackStartPlan(
    playbackFadeInEnabled: Boolean,
    playbackFadeInDurationMs: Long,
    playbackCrossfadeInDurationMs: Long,
    useTrackTransitionFade: Boolean = false,
    useUsbTransitionProtection: Boolean = false,
    forceStartupProtectionFade: Boolean = false
): PlaybackStartPlan {
    val targetDurationMs = resolveManagedFadeDuration(
        playbackFadeInEnabled,
        playbackFadeInDurationMs,
        playbackCrossfadeInDurationMs,
        useTrackTransitionFade,
        useUsbTransitionProtection,
        forceStartupProtectionFade
    )
    return resolvePlaybackStartPlan(
        shouldFadeIn = useUsbTransitionProtection ||
            useTrackTransitionFade ||
            playbackFadeInEnabled ||
            forceStartupProtectionFade,
        fadeDurationMs = targetDurationMs,
        allowUsbExclusiveFade = useUsbTransitionProtection
    )
}

private fun resolveManagedFadeDuration(
    playbackFadeInEnabled: Boolean,
    playbackFadeInDurationMs: Long,
    playbackCrossfadeInDurationMs: Long,
    useTrackTransitionFade: Boolean,
    useUsbTransitionProtection: Boolean,
    forceStartupProtectionFade: Boolean
): Long {
    return when {
        useUsbTransitionProtection -> USB_TRACK_TRANSITION_PROTECTION_FADE_DURATION_MS
        useTrackTransitionFade -> playbackCrossfadeInDurationMs
        forceStartupProtectionFade && playbackFadeInEnabled ->
            maxOf(
                playbackFadeInDurationMs,
                RESTORED_PLAYBACK_PROTECTION_FADE_DURATION_MS
            )
        forceStartupProtectionFade -> RESTORED_PLAYBACK_PROTECTION_FADE_DURATION_MS
        else -> playbackFadeInDurationMs
    }
}

fun resolveEffectivePlaybackStartPlan(
    plan: PlaybackStartPlan,
    usbExclusivePlaybackEnabled: Boolean
): PlaybackStartPlan {
    return if (usbExclusivePlaybackEnabled && !plan.allowUsbExclusiveFade) {
        plan.copy(useFadeIn = false, fadeDurationMs = 0L, initialVolume = 1f)
    } else {
        plan
    }
}

fun resolvePlaybackContinuationStartPlan(
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

fun shouldForceStartupProtectionFadeOnManualResume(
    isPlayerPrepared: Boolean,
    resumePositionMs: Long,
    currentMediaUrlResolvedAtMs: Long
): Boolean {
    return !isPlayerPrepared &&
        resumePositionMs > 0L &&
        currentMediaUrlResolvedAtMs <= 0L
}

fun resolveManualResumePlaybackDecision(
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
