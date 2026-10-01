package moe.ouom.neriplayer.data.ltw.playback

import moe.ouom.neriplayer.data.ltw.mapping.ListenTogetherSongMapper
import moe.ouom.neriplayer.data.ltw.playback.sync.ListenTogetherPlayerSyncContext
import moe.ouom.neriplayer.data.ltw.playback.sync.ListenTogetherPlayerSyncPlan
import moe.ouom.neriplayer.data.ltw.playback.sync.resolveListenTogetherPlayerSyncPlan
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState

internal class ListenTogetherPlaybackPositionSync(
    private val playback: ListenTogetherPlaybackHost,
    songMapper: ListenTogetherSongMapper,
    private val config: ListenTogetherPlayerStateApplierConfig,
    private val serverClockOffset: () -> Long
) : ListenTogetherSongMapper by songMapper {
    fun plan(state: ListenTogetherRoomState, causeType: String?, expectedPositionMs: Long?, queue: ListenTogetherQueueSyncSnapshot, contextChanged: Boolean, switchGraceActive: Boolean): ListenTogetherPlayerSyncPlan {
        val expected = expectedPosition(state, queue.targetSong, expectedPositionMs)
        val localPosition = playback.playbackPositionFlow.value.coerceAtLeast(0L)
        val desiredPlaying = state.playback.state == "playing"
        return resolveListenTogetherPlayerSyncPlan(ListenTogetherPlayerSyncContext(
            playbackContextChanged = contextChanged,
            targetIndexChanged = queue.indexChanged,
            desiredPlaying = desiredPlaying,
            localPlaying = playback.isPlayingFlow.value,
            localPlaybackAlreadyStarting = playback.playWhenReadyFlow.value,
            awaitingAuthoritativeStream = isAwaitingStream(queue.targetSong, queue.previousSong),
            expectedPositionMs = expected,
            localPositionMs = localPosition,
            ignoreUnexpectedZeroPositionRollback = shouldIgnoreListenTogetherUnexpectedZeroPositionRollback(
                causeType, desiredPlaying, expected, localPosition, contextChanged, queue.indexChanged, config.zeroPositionRollbackGuardMs
            ),
            trackSwitchGracePeriodActive = switchGraceActive,
            causeType = causeType,
            trackSwitchForceSyncMs = config.trackSwitchForceSyncMs,
            heartbeatDriftForceSyncMs = config.heartbeatDriftForceSyncMs,
            playingDriftForceSyncMs = config.playingDriftForceSyncMs,
            pausedDriftForceSyncMs = config.pausedDriftForceSyncMs,
            forcePositionSync = causeType == LISTEN_TOGETHER_LISTENER_SAFETY_RESUME_CAUSE
        ))
    }

    private fun expectedPosition(state: ListenTogetherRoomState, song: SongItem, overridePosition: Long?): Long {
        val raw = overridePosition ?: state.playback.expectedPositionMs(serverClockOffsetMs = serverClockOffset(), durationMs = song.durationMs)
        val repeatAware = wrapListenTogetherSingleTrackRepeatPosition(raw, state.playback.repeatMode, song.durationMs)
        return clampListenTogetherPositionMs(repeatAware, song.durationMs)
    }

    private fun isAwaitingStream(targetSong: SongItem, previousSong: SongItem?): Boolean =
        shouldWaitForListenTogetherAuthoritativeStreamPlayback(
            playerWaitingForAuthoritativeStream = !playback.isListenTogetherLocalResolutionPendingFor(targetSong) && playback.shouldWaitForListenTogetherAuthoritativeStream(targetSong),
            localTrackMatchesTarget = previousSong?.sameTrackAs(targetSong) == true,
            localTrackStreamUrl = previousSong?.streamUrl,
            localResolvedStreamUrl = playback.currentMediaUrlFlow.value
        )
}
