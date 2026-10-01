package moe.ouom.neriplayer.data.ltw.playback.sync

import moe.ouom.neriplayer.data.ltw.control.passivePositionUpdateTypes
import kotlin.math.abs

data class ListenTogetherPlayerSyncContext(
    val playbackContextChanged: Boolean,
    val targetIndexChanged: Boolean,
    val desiredPlaying: Boolean,
    val localPlaying: Boolean,
    val localPlaybackAlreadyStarting: Boolean,
    val awaitingAuthoritativeStream: Boolean,
    val expectedPositionMs: Long,
    val localPositionMs: Long,
    val ignoreUnexpectedZeroPositionRollback: Boolean,
    val trackSwitchGracePeriodActive: Boolean = false,
    val forcePositionSync: Boolean = false,
    val causeType: String?,
    val trackSwitchForceSyncMs: Long,
    val heartbeatDriftForceSyncMs: Long,
    val playingDriftForceSyncMs: Long,
    val pausedDriftForceSyncMs: Long
)

data class ListenTogetherPlayerSyncPlan(
    val shouldReloadPlaylist: Boolean,
    val effectiveExpectedPositionMs: Long,
    val signedDriftMs: Long,
    val driftMs: Long,
    val shouldSeek: Boolean,
    val shouldIssuePlay: Boolean,
    val shouldIssuePause: Boolean,
    val shouldForcePauseAfterRemoteLoad: Boolean,
    val desiredPlaying: Boolean,
    val localPlaying: Boolean
)

fun resolveListenTogetherPlayerSyncPlan(
    context: ListenTogetherPlayerSyncContext
): ListenTogetherPlayerSyncPlan {
    val shouldReloadPlaylist = context.playbackContextChanged || context.targetIndexChanged
    val shouldDeferPassiveTrackSwitchSync =
        context.trackSwitchGracePeriodActive &&
            context.causeType in passivePositionUpdateTypes
    val effectiveExpectedPositionMs = resolveExpectedPosition(context, shouldDeferPassiveTrackSwitchSync)
    val signedDriftMs = effectiveExpectedPositionMs - context.localPositionMs
    val driftMs = abs(signedDriftMs)
    val shouldSeek = shouldSeek(context, shouldReloadPlaylist, shouldDeferPassiveTrackSwitchSync, effectiveExpectedPositionMs, driftMs)
    return ListenTogetherPlayerSyncPlan(
        shouldReloadPlaylist = shouldReloadPlaylist,
        effectiveExpectedPositionMs = effectiveExpectedPositionMs,
        signedDriftMs = signedDriftMs,
        driftMs = driftMs,
        shouldSeek = shouldSeek,
        shouldIssuePlay = shouldIssuePlay(context, shouldReloadPlaylist),
        shouldIssuePause = shouldIssuePause(context, shouldReloadPlaylist),
        shouldForcePauseAfterRemoteLoad = !context.desiredPlaying && shouldReloadPlaylist,
        desiredPlaying = context.desiredPlaying,
        localPlaying = context.localPlaying
    )
}

private fun resolveExpectedPosition(context: ListenTogetherPlayerSyncContext, deferPassiveSync: Boolean): Long =
    if (context.ignoreUnexpectedZeroPositionRollback || deferPassiveSync) context.localPositionMs else context.expectedPositionMs

private fun shouldSeek(
    context: ListenTogetherPlayerSyncContext,
    reloadPlaylist: Boolean,
    deferPassiveSync: Boolean,
    expectedPositionMs: Long,
    driftMs: Long
): Boolean {
    if (context.forcePositionSync) return true
    if (deferPassiveSync) return false
    if (reloadPlaylist) return expectedPositionMs > 0L || driftMs > context.trackSwitchForceSyncMs
    return driftMs > driftThreshold(context)
}

private fun driftThreshold(context: ListenTogetherPlayerSyncContext): Long {
    if (context.causeType == "HEARTBEAT" && context.desiredPlaying) return context.heartbeatDriftForceSyncMs
    return if (context.desiredPlaying) context.playingDriftForceSyncMs else context.pausedDriftForceSyncMs
}

private fun shouldIssuePlay(context: ListenTogetherPlayerSyncContext, reloadPlaylist: Boolean): Boolean =
    context.desiredPlaying && !context.localPlaying &&
        (!reloadPlaylist || shouldResumeAfterReload(context)) && !context.awaitingAuthoritativeStream

private fun shouldResumeAfterReload(context: ListenTogetherPlayerSyncContext): Boolean =
    context.causeType == "LINK_READY" && !context.awaitingAuthoritativeStream

private fun shouldIssuePause(context: ListenTogetherPlayerSyncContext, reloadPlaylist: Boolean): Boolean =
    !context.desiredPlaying && (reloadPlaylist || context.localPlaying || context.localPlaybackAlreadyStarting)
