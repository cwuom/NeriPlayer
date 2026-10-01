package moe.ouom.neriplayer.data.ltw.playback

import android.os.SystemClock
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.ltw.mapping.ListenTogetherSongMapper
import moe.ouom.neriplayer.data.ltw.playback.sync.ListenTogetherPlayerSyncPlan
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.playback.PlaybackCommandSource

internal class ListenTogetherPlayerStateApplier(
    private val playback: ListenTogetherPlaybackHost,
    private val songMapper: ListenTogetherSongMapper,
    private val config: ListenTogetherPlayerStateApplierConfig,
    private val roomStateProvider: () -> ListenTogetherRoomState?,
    private val isControllerProvider: () -> Boolean,
    private val serverClockOffsetProvider: () -> Long,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime
) : ListenTogetherSongMapper by songMapper {
    private var lastTrackSwitchAtElapsedMs: Long = 0L
    private val queueSync = ListenTogetherPlaybackQueueSync(playback, songMapper)
    private val streamSync = ListenTogetherAuthoritativeStreamSync(playback, songMapper, isControllerProvider, roomStateProvider)
    private val positionSync = ListenTogetherPlaybackPositionSync(playback, songMapper, config, serverClockOffsetProvider)

    fun apply(state: ListenTogetherRoomState, causeType: String?, expectedPositionMs: Long?): Boolean {
        if (!mayApply(state, causeType)) return false
        val songs = queueSync.songQueue(state)
        if (songs.isEmpty()) return applyEmptyQueue(causeType)
        val queue = queueSync.synchronize(state, songs, causeType)
        val stream = streamSync.inspect(state, queue.targetSong, queue.previousSong, causeType)
        val contextChanged = queue.queueChanged || stream.requiresReload
        val switchGraceActive = isTrackSwitchGracePeriodActive()
        if (contextChanged || queue.indexChanged) {
            streamSync.onReload(stream)
            lastTrackSwitchAtElapsedMs = elapsedRealtimeMs()
            playback.resetListenTogetherSyncPlaybackRate()
            playback.playPlaylist(songs, queue.targetIndex, commandSource = PlaybackCommandSource.REMOTE_SYNC)
        }
        playback.applyListenTogetherPlaybackMode(state.playback.repeatMode, state.playback.shuffleEnabled)
        val plan = positionSync.plan(state, causeType, expectedPositionMs, queue, contextChanged, switchGraceActive)
        NPLogger.d(config.tag, "applyRoomStateToPlayer(): roomId=${state.roomId}, version=${state.version}, causeType=$causeType, contextChanged=$contextChanged, indexChanged=${queue.indexChanged}, plan=$plan, stream=$stream")
        applySyncPlan(plan)
        return true
    }

    private fun mayApply(state: ListenTogetherRoomState, causeType: String?): Boolean {
        val latestVersion = roomStateProvider()?.version ?: state.version
        if (state.version < latestVersion) {
            NPLogger.d(config.tag, "applyRoomStateToPlayer(): skip stale version=${state.version}, latest=$latestVersion")
            return false
        }
        if (playback.shouldHoldListenTogetherPlaybackForSafetyPause(causeType)) {
            NPLogger.d(config.tag, "applyRoomStateToPlayer(): hold listener safety pause, causeType=$causeType")
            return false
        }
        return true
    }

    private fun applyEmptyQueue(causeType: String?): Boolean {
        if (!isListenTogetherQueueUpdateCause(causeType)) return false
        playback.applyRemoteQueueUpdate(emptyList(), -1)
        return true
    }

    private fun isTrackSwitchGracePeriodActive(): Boolean {
        val switchedAt = lastTrackSwitchAtElapsedMs
        if (switchedAt <= 0L) return false
        return elapsedRealtimeMs() - switchedAt < config.trackSwitchGracePeriodMs
    }

    private fun applySyncPlan(syncPlan: ListenTogetherPlayerSyncPlan) {
        if (syncPlan.desiredPlaying) {
            applyPlayingSyncPlan(syncPlan)
            return
        }
        playback.resetListenTogetherSyncPlaybackRate()
        if (syncPlan.shouldSeek) {
            playback.seekTo(
                syncPlan.effectiveExpectedPositionMs,
                commandSource = PlaybackCommandSource.REMOTE_SYNC
            )
        }
        if (syncPlan.shouldIssuePause) {
            playback.pauseImpl(
                forcePersist = true,
                commandSource = PlaybackCommandSource.REMOTE_SYNC,
                allowFadeOut = false,
                debugReason = "listen_together_remote_pause"
            )
        }
    }

    private fun applyPlayingSyncPlan(syncPlan: ListenTogetherPlayerSyncPlan) {
        if (syncPlan.shouldSeek) {
            playback.resetListenTogetherSyncPlaybackRate()
            playback.seekTo(
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
            playback.resetListenTogetherSyncPlaybackRate()
            playback.play(commandSource = PlaybackCommandSource.REMOTE_SYNC)
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
            playback.resetListenTogetherSyncPlaybackRate()
            return
        }
        NPLogger.d(
            config.tag,
            "applySoftDriftCorrection(): driftMs=$driftMs, signedDriftMs=$signedDriftMs, applyRate=$rate"
        )
        playback.setListenTogetherSyncPlaybackRate(rate)
    }

}
