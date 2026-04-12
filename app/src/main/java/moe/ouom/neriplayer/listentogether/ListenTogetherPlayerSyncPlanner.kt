package moe.ouom.neriplayer.listentogether

import kotlin.math.abs

internal data class ListenTogetherPlayerSyncContext(
    val playbackContextChanged: Boolean,
    val targetIndexChanged: Boolean,
    val desiredPlaying: Boolean,
    val localPlaying: Boolean,
    val localPlaybackAlreadyStarting: Boolean,
    val awaitingAuthoritativeStream: Boolean,
    val expectedPositionMs: Long,
    val localPositionMs: Long,
    val ignoreUnexpectedZeroPositionRollback: Boolean,
    val causeType: String?,
    val trackSwitchForceSyncMs: Long,
    val heartbeatDriftForceSyncMs: Long,
    val playingDriftForceSyncMs: Long,
    val pausedDriftForceSyncMs: Long
)

internal data class ListenTogetherPlayerSyncPlan(
    val shouldReloadPlaylist: Boolean,
    val effectiveExpectedPositionMs: Long,
    val signedDriftMs: Long,
    val driftMs: Long,
    val shouldSeek: Boolean,
    val shouldIssuePlay: Boolean,
    val shouldForcePauseAfterRemoteLoad: Boolean,
    val desiredPlaying: Boolean,
    val localPlaying: Boolean
)

internal fun resolveListenTogetherPlayerSyncPlan(
    context: ListenTogetherPlayerSyncContext
): ListenTogetherPlayerSyncPlan {
    val shouldReloadPlaylist = context.playbackContextChanged || context.targetIndexChanged
    val effectiveExpectedPositionMs = if (context.ignoreUnexpectedZeroPositionRollback) {
        context.localPositionMs
    } else {
        context.expectedPositionMs
    }
    val signedDriftMs = effectiveExpectedPositionMs - context.localPositionMs
    val driftMs = abs(signedDriftMs)
    val isHeartbeatUpdate = context.causeType == "HEARTBEAT"
    val shouldSeek = when {
        shouldReloadPlaylist -> {
            effectiveExpectedPositionMs > 0L || driftMs > context.trackSwitchForceSyncMs
        }

        isHeartbeatUpdate && context.desiredPlaying -> driftMs > context.heartbeatDriftForceSyncMs
        context.desiredPlaying -> driftMs > context.playingDriftForceSyncMs
        else -> driftMs > context.pausedDriftForceSyncMs
    }
    return ListenTogetherPlayerSyncPlan(
        shouldReloadPlaylist = shouldReloadPlaylist,
        effectiveExpectedPositionMs = effectiveExpectedPositionMs,
        signedDriftMs = signedDriftMs,
        driftMs = driftMs,
        shouldSeek = shouldSeek,
        shouldIssuePlay = context.desiredPlaying &&
            !context.localPlaying &&
            !shouldReloadPlaylist &&
            !context.localPlaybackAlreadyStarting &&
            !context.awaitingAuthoritativeStream,
        shouldForcePauseAfterRemoteLoad = !context.desiredPlaying && shouldReloadPlaylist,
        desiredPlaying = context.desiredPlaying,
        localPlaying = context.localPlaying
    )
}
