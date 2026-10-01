package moe.ouom.neriplayer.core.player.runtime.refresh

import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource

data class RefreshRequestSemantics(
    val songKey: String,
    val requestGeneration: Long,
    val resumePositionMs: Long,
    val positionGeneration: Long = 0L,
    val fallbackSeekPositionMs: Long?,
    val resumePlaybackAfterRefresh: Boolean,
    val allowFallback: Boolean,
    val reason: String,
    val resumedPlaybackCommandSource: PlaybackCommandSource?,
    val youtubeRecoveryStrategy: YouTubePlaybackRecoveryStrategy? = null,
    val cacheKeyToInvalidateBeforeResolve: String? = null
)

data class YouTubePlaybackRecoveryStrategy(
    val preferredQualityOverride: String,
    val requireDirect: Boolean,
    val preferM4a: Boolean,
    val allowUnverifiedDirectFallback: Boolean = true
)

enum class RefreshResultKind {
    SUCCESS,
    FALLBACK,
    FAILURE
}

data class RefreshApplyAction(
    val updateDuration: Boolean,
    val updateUrl: Boolean,
    val updateAudioInfo: Boolean,
    val persist: Boolean,
    val installMediaItem: Boolean,
    val clearPendingMediaLoad: Boolean,
    val resetFailureCounter: Boolean,
    val emitPlaybackCommand: Boolean,
    val clearPendingSeek: Boolean,
    val updateLoadedGeneration: Boolean,
    val fallbackSeek: Boolean,
    val fallbackPlayPause: Boolean,
    val emitFailureError: Boolean,
    val pauseAfterFailure: Boolean
)

data class RefreshInFlightStart<T>(
    val request: RefreshRequestHandle,
    val operation: T,
    val startedNew: Boolean
)

class RefreshRequestHandle internal constructor(val semantics: RefreshRequestSemantics)
