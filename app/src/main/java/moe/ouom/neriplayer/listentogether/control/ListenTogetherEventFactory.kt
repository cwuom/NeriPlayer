package moe.ouom.neriplayer.listentogether.control

import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommand
import moe.ouom.neriplayer.listentogether.compat.resolveListenTogetherPlaybackCommandShouldPlay
import moe.ouom.neriplayer.listentogether.compat.resolveListenTogetherLinkReadyState
import moe.ouom.neriplayer.listentogether.mapping.toListenTogetherTrackOrNull
import moe.ouom.neriplayer.listentogether.mapping.withStreamUrl
import moe.ouom.neriplayer.listentogether.playback.indexOfTrack
import moe.ouom.neriplayer.listentogether.playback.mergeCurrentTrack
import moe.ouom.neriplayer.listentogether.playback.normalizedDirectStreamUrl
import moe.ouom.neriplayer.listentogether.playback.sameTrackAs
import moe.ouom.neriplayer.listentogether.playback.toShareableQueueSnapshot
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherEvent
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomState
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.SongItem
import java.util.UUID

internal fun nextListenTogetherEventId(): String {
    return "evt-${System.currentTimeMillis()}-${UUID.randomUUID()}"
}

internal class ListenTogetherEventFactory(
    private val roomStateProvider: () -> ListenTogetherRoomState?,
    private val isControllerProvider: () -> Boolean,
    private val eventIdFactory: () -> String,
    private val clientInstanceIdProvider: () -> String,
    private val clientSequenceFactory: () -> Long,
    private val localPlaybackStateNameProvider: () -> String,
    private val localTransportActiveProvider: () -> Boolean
) {
    fun buildSetTrackEvent(
        queue: List<SongItem>,
        currentIndex: Int,
        positionMs: Long,
        shouldPlay: Boolean
    ): ListenTogetherEvent {
        val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
            currentIndex = currentIndex,
            roomSettings = roomStateProvider()?.settings,
            includeResolvedStreamUrl = isControllerProvider()
        )
        return ListenTogetherEvent(
            type = "SET_TRACK",
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = resolvedCurrentIndex,
            track = shareableQueue.getOrNull(resolvedCurrentIndex),
            queue = shareableQueue,
            shouldPlay = shouldPlay
        )
    }

    fun buildPlayEvent(positionMs: Long): ListenTogetherEvent {
        return playbackSnapshotEvent("PLAY", positionMs)
    }

    fun buildPauseEvent(positionMs: Long): ListenTogetherEvent {
        return playbackSnapshotEvent("PAUSE", positionMs)
    }

    fun buildSeekEvent(positionMs: Long): ListenTogetherEvent {
        return playbackSnapshotEvent("SEEK", positionMs)
    }

    fun buildRequestPlayEvent(positionMs: Long): ListenTogetherEvent {
        return playbackSnapshotEvent("REQUEST_PLAY", positionMs)
    }

    fun buildRequestPauseEvent(positionMs: Long): ListenTogetherEvent {
        return playbackSnapshotEvent("REQUEST_PAUSE", positionMs)
    }

    fun buildRequestSeekEvent(positionMs: Long): ListenTogetherEvent {
        return playbackSnapshotEvent("REQUEST_SEEK", positionMs)
    }

    fun buildPlaybackModeEvent(
        repeatMode: Int,
        shuffleEnabled: Boolean
    ): ListenTogetherEvent {
        return playbackSnapshotEvent(
            type = if (isControllerProvider()) "PLAYBACK_MODE" else "REQUEST_PLAYBACK_MODE",
            positionMs = PlayerManager.playbackPositionFlow.value.coerceAtLeast(0L)
        ).copy(
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled
        )
    }

    fun buildHeartbeatEvent(
        state: String,
        positionMs: Long,
        includeQueue: Boolean = true
    ): ListenTogetherEvent {
        val queue = PlayerManager.currentQueueFlow.value
        val currentSong = PlayerManager.currentSongFlow.value
        val rawIndex = queue.indexOfFirst { song ->
            currentSong != null && song.sameTrackAs(currentSong)
        }
        val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
            currentIndex = rawIndex.takeIf { it >= 0 } ?: 0,
            roomSettings = roomStateProvider()?.settings,
            includeResolvedStreamUrl = isControllerProvider()
        )
        val shareableTrack = shareableQueue.getOrNull(resolvedCurrentIndex)
        return ListenTogetherEvent(
            type = "HEARTBEAT",
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            currentIndex = resolvedCurrentIndex,
            track = shareableTrack,
            queue = shareableQueue.takeIf { includeQueue },
            state = state,
            positionMs = positionMs.coerceAtLeast(0L)
        )
    }

    fun buildRequestLinkEvent(
        stableKey: String,
        currentIndex: Int? = null,
        track: ListenTogetherTrack? = null
    ): ListenTogetherEvent {
        return ListenTogetherEvent(
            type = "REQUEST_LINK",
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            currentIndex = currentIndex,
            track = track,
            requestTrackStableKey = stableKey
        )
    }

    fun buildLinkReadyEvent(
        stableKey: String,
        positionMs: Long,
        streamUrlOverride: String? = null
    ): ListenTogetherEvent? {
        val queue = PlayerManager.currentQueueFlow.value
        val currentSong = PlayerManager.currentSongFlow.value ?: run {
            NPLogger.w(TAG, "buildLinkReadyEvent(): currentSong missing, stableKey=$stableKey")
            return null
        }
        val rawIndex = queue.indexOfFirst { song -> song.sameTrackAs(currentSong) }
        val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
            currentIndex = rawIndex.takeIf { it >= 0 } ?: 0,
            roomSettings = roomStateProvider()?.settings,
            includeResolvedStreamUrl = true
        )
        val shareableTrack = shareableQueue.getOrNull(resolvedCurrentIndex) ?: run {
            NPLogger.w(
                TAG,
                "buildLinkReadyEvent(): shareableTrack missing, stableKey=$stableKey, resolvedCurrentIndex=$resolvedCurrentIndex, queueSize=${shareableQueue.size}"
            )
            return null
        }
        if (shareableTrack.stableKey != stableKey) {
            NPLogger.d(
                TAG,
                "buildLinkReadyEvent(): stableKey mismatch, expected=$stableKey, actual=${shareableTrack.stableKey}, resolvedCurrentIndex=$resolvedCurrentIndex"
            )
            return null
        }
        val resolvedStreamUrl = normalizedDirectStreamUrl(streamUrlOverride)
            ?: normalizedDirectStreamUrl(shareableTrack.streamUrl)
            ?: run {
                NPLogger.w(
                    TAG,
                    "buildLinkReadyEvent(): direct stream url missing, stableKey=$stableKey, track=${shareableTrack.name}"
                )
                return null
            }
        val trustedTrack = shareableTrack.withStreamUrl(resolvedStreamUrl)
        val trustedStreamUrl = normalizedDirectStreamUrl(trustedTrack.streamUrl) ?: run {
            NPLogger.w(
                TAG,
                "buildLinkReadyEvent(): rejected untrusted stream url, stableKey=$stableKey, track=${shareableTrack.name}, url=${resolvedStreamUrl.take(128)}"
            )
            return null
        }
        NPLogger.d(
            TAG,
            "buildLinkReadyEvent(): stableKey=$stableKey, resolvedCurrentIndex=$resolvedCurrentIndex, queueSize=${shareableQueue.size}, positionMs=$positionMs"
        )
        return ListenTogetherEvent(
            type = "LINK_READY",
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            currentIndex = resolvedCurrentIndex,
            track = trustedTrack,
            queue = shareableQueue.mergeCurrentTrack(
                currentIndex = resolvedCurrentIndex,
                currentTrack = trustedTrack.withStreamUrl(trustedStreamUrl)
            ),
            state = resolveListenTogetherLinkReadyState(
                roomPlaybackState = roomStateProvider()?.playback?.state,
                localTransportActive = localTransportActiveProvider(),
                localPlaying = PlayerManager.isPlayingFlow.value
            ),
            positionMs = positionMs.coerceAtLeast(0L),
            requestTrackStableKey = stableKey
        )
    }

    fun buildRequestSetTrackEvent(
        queue: List<SongItem>,
        currentIndex: Int,
        positionMs: Long,
        shouldPlay: Boolean
    ): ListenTogetherEvent {
        return buildSetTrackEvent(
            queue = queue,
            currentIndex = currentIndex,
            positionMs = positionMs,
            shouldPlay = shouldPlay
        ).copy(type = "REQUEST_SET_TRACK")
    }

    fun buildTrackFinishedEvent(
        command: PlaybackCommand,
        queue: List<SongItem>,
        currentSong: SongItem?,
        positionMs: Long
    ): ListenTogetherEvent? {
        if (queue.isEmpty() || currentSong == null) return null
        val finishedTrack = currentSong.toListenTogetherTrackOrNull() ?: return null
        val proposedNextIndex = command.currentIndex?.coerceIn(0, queue.lastIndex)
            ?: queue.indexOfTrack(currentSong).takeIf { it >= 0 }
            ?: 0
        val isController = isControllerProvider()
        val (shareableQueue, resolvedNextIndex) = queue.toShareableQueueSnapshot(
            currentIndex = proposedNextIndex,
            roomSettings = roomStateProvider()?.settings,
            includeResolvedStreamUrl = false
        )
        val shouldAdvance = command.shouldPlay == true
        return ListenTogetherEvent(
            type = "TRACK_FINISHED",
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = if (isController) resolvedNextIndex else null,
            nextIndex = if (isController) resolvedNextIndex else null,
            track = if (isController && shouldAdvance) shareableQueue.getOrNull(resolvedNextIndex) else null,
            queue = if (isController) shareableQueue else null,
            shouldPlay = if (isController) shouldAdvance else null,
            finishedTrackStableKey = finishedTrack.stableKey
        )
    }

    fun buildControllerCommitEventFromForwardedRequest(
        message: ListenTogetherSocketEnvelope
    ): ListenTogetherEvent? {
        val requestType = message.causedBy?.type ?: return null
        val commitType = requestType.removePrefix("REQUEST_")
        if (commitType == requestType) return null
        val positionMs = message.positionMs ?: message.expectedPositionMs ?: 0L
        return ListenTogetherEvent(
            type = commitType,
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = message.currentIndex,
            track = message.track,
            queue = message.queue,
            shouldPlay = message.shouldPlay,
            state = message.stateName,
            repeatMode = message.repeatMode,
            shuffleEnabled = message.shuffleEnabled,
            requestTrackStableKey = message.requestTrackStableKey
        )
    }

    fun buildEventForPlaybackCommand(
        command: PlaybackCommand
    ): ListenTogetherEvent? {
        val queue = PlayerManager.currentQueueFlow.value
        val currentSong = PlayerManager.currentSongFlow.value
        val currentIndex = command.currentIndex
            ?: queue.indexOfFirst { song ->
                currentSong != null && song.sameTrackAs(currentSong)
            }.takeIf { it >= 0 }
            ?: 0
        val positionMs = command.positionMs ?: PlayerManager.playbackPositionFlow.value.coerceAtLeast(0L)
        val shouldPlay = resolveListenTogetherPlaybackCommandShouldPlay(
            commandType = command.type,
            commandShouldPlay = command.shouldPlay,
            localTransportActive = localTransportActiveProvider(),
            localPlaying = PlayerManager.isPlayingFlow.value
        )

        return when (command.type) {
            "PLAY_PLAYLIST",
            "PLAY_FROM_QUEUE",
            "NEXT",
            "PREVIOUS" -> if (isControllerProvider()) {
                buildSetTrackEvent(
                    queue = queue,
                    currentIndex = currentIndex,
                    positionMs = positionMs,
                    shouldPlay = shouldPlay
                )
            } else {
                buildRequestSetTrackEvent(
                    queue = queue,
                    currentIndex = currentIndex,
                    positionMs = positionMs,
                    shouldPlay = shouldPlay
                )
            }

            "PLAY" -> if (isControllerProvider()) buildPlayEvent(positionMs) else buildRequestPlayEvent(positionMs)
            "PAUSE" -> if (isControllerProvider()) buildPauseEvent(positionMs) else buildRequestPauseEvent(positionMs)
            "PLAYBACK_MODE" -> buildPlaybackModeEvent(
                repeatMode = command.repeatMode ?: PlayerManager.repeatModeFlow.value,
                shuffleEnabled = command.shuffleEnabled ?: PlayerManager.shuffleModeFlow.value
            )
            "TRACK_FINISHED" -> buildTrackFinishedEvent(command, queue, currentSong, positionMs)
            "SEEK" -> {
                val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
                    currentIndex = currentIndex,
                    roomSettings = roomStateProvider()?.settings,
                    includeResolvedStreamUrl = isControllerProvider()
                )
                val shareableTrack = shareableQueue.getOrNull(resolvedCurrentIndex)
                val event = if (isControllerProvider()) buildSeekEvent(positionMs) else buildRequestSeekEvent(positionMs)
                event.copy(
                    currentIndex = resolvedCurrentIndex,
                    track = shareableTrack
                )
            }
            else -> null
        }
    }

    private fun playbackSnapshotEvent(type: String, positionMs: Long): ListenTogetherEvent {
        val queue = PlayerManager.currentQueueFlow.value
        val currentSong = PlayerManager.currentSongFlow.value
        val rawIndex = queue.indexOfFirst { song ->
            currentSong != null && song.sameTrackAs(currentSong)
        }
        val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
            currentIndex = rawIndex.takeIf { it >= 0 } ?: 0,
            roomSettings = roomStateProvider()?.settings,
            includeResolvedStreamUrl = isControllerProvider()
        )
        val shareableTrack = shareableQueue.getOrNull(resolvedCurrentIndex)
        val resolvedState = when (type.removePrefix("REQUEST_")) {
            "PLAY" -> "playing"
            "PAUSE" -> "paused"
            else -> localPlaybackStateNameProvider()
        }
        return ListenTogetherEvent(
            type = type,
            eventId = eventIdFactory(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = clientInstanceIdProvider(),
            clientSequence = clientSequenceFactory(),
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = resolvedCurrentIndex,
            track = shareableTrack,
            queue = shareableQueue,
            shouldPlay = resolvedState == "playing",
            state = resolvedState,
            repeatMode = PlayerManager.repeatModeFlow.value,
            shuffleEnabled = PlayerManager.shuffleModeFlow.value
        )
    }

    private companion object {
        const val TAG = "NERI-ListenTogether"
    }
}
