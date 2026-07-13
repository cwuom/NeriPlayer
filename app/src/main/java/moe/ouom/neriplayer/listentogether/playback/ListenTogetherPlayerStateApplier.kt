package moe.ouom.neriplayer.listentogether.playback

import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource
import moe.ouom.neriplayer.listentogether.mapping.toSongItem
import moe.ouom.neriplayer.listentogether.playback.sync.ListenTogetherPlayerSyncContext
import moe.ouom.neriplayer.listentogether.playback.sync.ListenTogetherPlayerSyncPlan
import moe.ouom.neriplayer.listentogether.playback.sync.resolveListenTogetherPlayerSyncPlan
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.session.normalized
import moe.ouom.neriplayer.data.model.SongItem

internal data class ListenTogetherPlayerStateApplierConfig(
    val tag: String,
    val trackSwitchForceSyncMs: Long,
    val heartbeatDriftForceSyncMs: Long,
    val playingDriftForceSyncMs: Long,
    val pausedDriftForceSyncMs: Long,
    val softSyncMinDriftMs: Long,
    val softSyncFastDriftMs: Long,
    val zeroPositionRollbackGuardMs: Long
)

internal class ListenTogetherPlayerStateApplier(
    private val config: ListenTogetherPlayerStateApplierConfig,
    private val roomStateProvider: () -> ListenTogetherRoomState?,
    private val isControllerProvider: () -> Boolean,
    private val serverClockOffsetProvider: () -> Long
) {
    fun apply(
        state: ListenTogetherRoomState,
        causeType: String?,
        expectedPositionMs: Long?
    ) {
        val latestRoomVersion = roomStateProvider()?.version ?: state.version
        if (state.version < latestRoomVersion) {
            NPLogger.d(
                config.tag,
                "applyRoomStateToPlayer(): skip stale state, roomId=${state.roomId}, version=${state.version}, latest=$latestRoomVersion, causeType=$causeType"
            )
            return
        }
        val queue = state.toSongQueue()
        if (queue.isEmpty()) {
            NPLogger.w(
                config.tag,
                "applyRoomStateToPlayer(): skip empty queue, roomId=${state.roomId}, version=${state.version}, causeType=$causeType"
            )
            return
        }
        NPLogger.d(
            config.tag,
            "applyRoomStateToPlayer(): roomId=${state.roomId}, version=${state.version}, queueSize=${queue.size}, currentIndex=${state.currentIndex}, playback=${state.playback.state}"
        )

        val targetIndex = state.currentIndex.coerceIn(0, queue.lastIndex)
        val targetSong = queue[targetIndex]
        val currentQueue = PlayerManager.currentQueueFlow.value
        val currentSong = PlayerManager.currentSongFlow.value
        val localCurrentIndex = currentQueue.indexOfTrack(currentSong)
        val needsAuthoritativeStreamReload = shouldReloadForAuthoritativeStreamUrl(
            targetSong = targetSong,
            currentSong = currentSong
        )
        val forcePlaybackStallReload =
            causeType == "WATCHDOG_STALL" &&
                state.playback.state == "playing" &&
                currentSong?.sameTrackAs(targetSong) == true
        val playbackContextChanged =
            !currentQueue.hasSameTrackSequenceAs(queue) ||
                needsAuthoritativeStreamReload ||
                forcePlaybackStallReload
        val targetIndexChanged = localCurrentIndex != targetIndex

        if (playbackContextChanged || targetIndexChanged) {
            PlayerManager.resetListenTogetherSyncPlaybackRate()
            PlayerManager.playPlaylist(queue, targetIndex, commandSource = PlaybackCommandSource.REMOTE_SYNC)
        }
        PlayerManager.applyListenTogetherPlaybackMode(
            repeatMode = state.playback.repeatMode,
            shuffleEnabled = state.playback.shuffleEnabled
        )

        val resolvedExpectedPositionMs = expectedPositionMs
            ?: state.playback.expectedPositionMs(serverClockOffsetMs = serverClockOffsetProvider())
        val localPositionMs = PlayerManager.playbackPositionFlow.value.coerceAtLeast(0L)
        val desiredPlaying = state.playback.state == "playing"
        val localPlaying = PlayerManager.isPlayingFlow.value
        val localPlaybackAlreadyStarting = PlayerManager.playWhenReadyFlow.value
        val awaitingAuthoritativeStream = shouldWaitForListenTogetherAuthoritativeStreamPlayback(
            playerWaitingForAuthoritativeStream = PlayerManager.shouldWaitForListenTogetherAuthoritativeStream(targetSong),
            localTrackMatchesTarget = currentSong?.sameTrackAs(targetSong) == true,
            localTrackStreamUrl = currentSong?.streamUrl,
            localResolvedStreamUrl = PlayerManager.currentMediaUrlFlow.value
        )
        val ignoreUnexpectedZeroPositionRollback = shouldIgnoreUnexpectedZeroPositionRollback(
            causeType = causeType,
            desiredPlaying = desiredPlaying,
            expectedPositionMs = resolvedExpectedPositionMs,
            localPositionMs = localPositionMs,
            playbackContextChanged = playbackContextChanged,
            targetIndexChanged = targetIndexChanged
        )
        val syncPlan = resolveListenTogetherPlayerSyncPlan(
            ListenTogetherPlayerSyncContext(
                playbackContextChanged = playbackContextChanged,
                targetIndexChanged = targetIndexChanged,
                desiredPlaying = desiredPlaying,
                localPlaying = localPlaying,
                localPlaybackAlreadyStarting = localPlaybackAlreadyStarting,
                awaitingAuthoritativeStream = awaitingAuthoritativeStream,
                expectedPositionMs = resolvedExpectedPositionMs,
                localPositionMs = localPositionMs,
                ignoreUnexpectedZeroPositionRollback = ignoreUnexpectedZeroPositionRollback,
                causeType = causeType,
                trackSwitchForceSyncMs = config.trackSwitchForceSyncMs,
                heartbeatDriftForceSyncMs = config.heartbeatDriftForceSyncMs,
                playingDriftForceSyncMs = config.playingDriftForceSyncMs,
                pausedDriftForceSyncMs = config.pausedDriftForceSyncMs
            )
        )
        NPLogger.d(
            config.tag,
            "applyRoomStateToPlayer(): causeType=$causeType, desiredPlaying=$desiredPlaying, localPlaying=$localPlaying, localPlaybackAlreadyStarting=$localPlaybackAlreadyStarting, awaitingAuthoritativeStream=$awaitingAuthoritativeStream, localCurrentIndex=$localCurrentIndex, targetIndex=$targetIndex, playbackContextChanged=$playbackContextChanged, targetIndexChanged=$targetIndexChanged, shouldReloadPlaylist=${syncPlan.shouldReloadPlaylist}, effectiveExpectedPositionMs=${syncPlan.effectiveExpectedPositionMs}, driftMs=${syncPlan.driftMs}, signedDriftMs=${syncPlan.signedDriftMs}, shouldSeek=${syncPlan.shouldSeek}, shouldIssuePlay=${syncPlan.shouldIssuePlay}, shouldIssuePause=${syncPlan.shouldIssuePause}, needsAuthoritativeStreamReload=$needsAuthoritativeStreamReload, forcePlaybackStallReload=$forcePlaybackStallReload, ignoreUnexpectedZeroPositionRollback=$ignoreUnexpectedZeroPositionRollback, shouldForcePauseAfterRemoteLoad=${syncPlan.shouldForcePauseAfterRemoteLoad}"
        )
        applySyncPlan(syncPlan)
    }

    private fun ListenTogetherRoomState.toSongQueue(): List<SongItem> {
        return when {
            queue.isNotEmpty() -> queue
                .mergeCurrentTrack(currentIndex, track)
                .map { it.toSongItem() }
            track != null -> listOf(track.toSongItem())
            else -> emptyList()
        }
    }

    private fun applySyncPlan(syncPlan: ListenTogetherPlayerSyncPlan) {
        if (syncPlan.desiredPlaying) {
            applyPlayingSyncPlan(syncPlan)
            return
        }
        PlayerManager.resetListenTogetherSyncPlaybackRate()
        if (syncPlan.shouldSeek) {
            PlayerManager.seekTo(
                syncPlan.effectiveExpectedPositionMs,
                commandSource = PlaybackCommandSource.REMOTE_SYNC
            )
        }
        if (syncPlan.shouldIssuePause) {
            PlayerManager.pause(forcePersist = false, commandSource = PlaybackCommandSource.REMOTE_SYNC)
        }
    }

    private fun applyPlayingSyncPlan(syncPlan: ListenTogetherPlayerSyncPlan) {
        if (syncPlan.shouldSeek) {
            PlayerManager.resetListenTogetherSyncPlaybackRate()
            PlayerManager.seekTo(
                syncPlan.effectiveExpectedPositionMs,
                commandSource = PlaybackCommandSource.REMOTE_SYNC
            )
        } else {
            applySoftDriftCorrection(
                driftMs = syncPlan.driftMs,
                signedDriftMs = syncPlan.signedDriftMs,
                allowSoftSync = true
            )
        }
        if (syncPlan.shouldIssuePlay) {
            PlayerManager.resetListenTogetherSyncPlaybackRate()
            PlayerManager.play(commandSource = PlaybackCommandSource.REMOTE_SYNC)
        }
    }

    private fun applySoftDriftCorrection(
        driftMs: Long,
        signedDriftMs: Long,
        allowSoftSync: Boolean
    ) {
        val rate = resolveListenTogetherSoftSyncPlaybackRate(
            driftMs = driftMs,
            signedDriftMs = signedDriftMs,
            allowSoftSync = allowSoftSync,
            isController = isControllerProvider(),
            softSyncMinDriftMs = config.softSyncMinDriftMs,
            softSyncFastDriftMs = config.softSyncFastDriftMs,
            playingDriftForceSyncMs = config.playingDriftForceSyncMs
        )
        if (rate == null) {
            NPLogger.d(
                config.tag,
                "applySoftDriftCorrection(): reset sync rate, allowSoftSync=$allowSoftSync, isController=${isControllerProvider()}, driftMs=$driftMs, signedDriftMs=$signedDriftMs"
            )
            PlayerManager.resetListenTogetherSyncPlaybackRate()
            return
        }
        NPLogger.d(
            config.tag,
            "applySoftDriftCorrection(): driftMs=$driftMs, signedDriftMs=$signedDriftMs, applyRate=$rate"
        )
        PlayerManager.setListenTogetherSyncPlaybackRate(rate)
    }

    private fun shouldIgnoreUnexpectedZeroPositionRollback(
        causeType: String?,
        desiredPlaying: Boolean,
        expectedPositionMs: Long,
        localPositionMs: Long,
        playbackContextChanged: Boolean,
        targetIndexChanged: Boolean
    ): Boolean {
        val shouldIgnore = shouldIgnoreListenTogetherUnexpectedZeroPositionRollback(
            causeType = causeType,
            desiredPlaying = desiredPlaying,
            expectedPositionMs = expectedPositionMs,
            localPositionMs = localPositionMs,
            playbackContextChanged = playbackContextChanged,
            targetIndexChanged = targetIndexChanged,
            zeroPositionRollbackGuardMs = config.zeroPositionRollbackGuardMs
        )
        if (!shouldIgnore) return false
        NPLogger.w(
            config.tag,
            "shouldIgnoreUnexpectedZeroPositionRollback(): ignore suspicious rollback, causeType=$causeType, expectedPositionMs=$expectedPositionMs, localPositionMs=$localPositionMs"
        )
        return true
    }

    private fun shouldReloadForAuthoritativeStreamUrl(
        targetSong: SongItem,
        currentSong: SongItem?
    ): Boolean {
        if (isControllerProvider()) return false
        if (!roomStateProvider()?.settings.normalized().shareAudioLinks) return false
        if (currentSong?.sameTrackAs(targetSong) != true) return false
        return shouldReloadListenTogetherAuthoritativeStream(
            remoteStreamUrl = targetSong.streamUrl,
            localResolvedStreamUrl = PlayerManager.currentMediaUrlFlow.value
        )
    }
}
