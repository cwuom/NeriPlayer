package moe.ouom.neriplayer.data.ltw.control

import moe.ouom.neriplayer.data.ltw.mapping.ListenTogetherSongMapper
import moe.ouom.neriplayer.data.ltw.playback.ListenTogetherPlaybackHost

import moe.ouom.neriplayer.data.model.playback.PlaybackCommand
import moe.ouom.neriplayer.data.ltw.compat.resolveListenTogetherLinkReadyState
import moe.ouom.neriplayer.data.ltw.mapping.withStreamUrls
import moe.ouom.neriplayer.data.ltw.playback.currentTrack
import moe.ouom.neriplayer.data.ltw.playback.mergeCurrentTrack
import moe.ouom.neriplayer.data.ltw.playback.wrapListenTogetherSingleTrackRepeatPosition
import moe.ouom.neriplayer.data.model.ltw.message.event.ListenTogetherEvent
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.message.socket.ListenTogetherSocketEnvelope
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherTrack
import moe.ouom.neriplayer.data.model.SongItem
import java.util.UUID

internal fun nextListenTogetherEventId(): String {
    return "evt-${System.currentTimeMillis()}-${UUID.randomUUID()}"
}

internal class ListenTogetherEventFactory(
    private val playback: ListenTogetherPlaybackHost,
    private val songMapper: ListenTogetherSongMapper,
    private val roomStateProvider: () -> ListenTogetherRoomState?,
    private val isControllerProvider: () -> Boolean,
    private val eventIdFactory: () -> String,
    private val clientInstanceIdProvider: () -> String,
    private val clientSequenceFactory: () -> Long,
    private val localPlaybackStateNameProvider: () -> String,
    private val localTransportActiveProvider: () -> Boolean
) : ListenTogetherSongMapper by songMapper {
    fun buildSetTrackEvent(
        queue: List<SongItem>,
        currentIndex: Int,
        positionMs: Long,
        shouldPlay: Boolean
    ): ListenTogetherEvent {
        val roomState = roomStateProvider()
        val (shareableQueue, resolvedCurrentIndex) = queue.toShareableQueueSnapshot(
            currentIndex = currentIndex,
            roomSettings = roomState?.settings,
            includeResolvedStreamUrl = false
        )
        val payload = buildListenTogetherQueueEventPayload(roomState, shareableQueue, resolvedCurrentIndex)
        return eventMetadata("SET_TRACK").copy(
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = resolvedCurrentIndex,
            track = shareableQueue.getOrNull(resolvedCurrentIndex),
            queue = payload.queue,
            queueMutation = payload.mutation,
            legacyQueueSnapshot = payload.legacySnapshot,
            shouldPlay = shouldPlay
        )
    }

    fun buildSetQueueEvent(queue: List<SongItem>, currentIndex: Int, positionMs: Long, commandShouldPlay: Boolean? = null): ListenTogetherEvent? {
        val room = roomStateProvider()
        val (tracks, index) = queueSelection(queue, currentIndex, room)
        val track = tracks.getOrNull(index)
        if (!isValidQueueSelection(queue, track)) return null
        val payload = buildListenTogetherQueueEventPayload(room, tracks, index)
        val shouldPlay = queueShouldPlay(queue, commandShouldPlay)
        return eventMetadata(if (isControllerProvider()) "SET_QUEUE" else "REQUEST_SET_QUEUE").copy(
            positionMs = if (queue.isEmpty()) 0L else positionMs.coerceAtLeast(0L),
            currentIndex = index,
            track = track,
            queue = payload.queue,
            queueMutation = payload.mutation,
            legacyQueueSnapshot = payload.legacySnapshot,
            shouldPlay = shouldPlay,
            state = if (shouldPlay) "playing" else "paused",
            repeatMode = playback.repeatModeFlow.value,
            shuffleEnabled = playback.shuffleModeFlow.value,
            requestTrackStableKey = track?.stableKey
        )
    }

    private fun queueSelection(queue: List<SongItem>, index: Int, room: ListenTogetherRoomState?): Pair<List<ListenTogetherTrack>, Int> {
        if (queue.isEmpty()) return emptyList<ListenTogetherTrack>() to -1
        return queue.toShareableQueueSnapshot(index, room?.settings, includeResolvedStreamUrl = false)
    }

    private fun isValidQueueSelection(queue: List<SongItem>, track: ListenTogetherTrack?): Boolean = queue.isEmpty() || track != null

    private fun queueShouldPlay(queue: List<SongItem>, commandShouldPlay: Boolean?): Boolean = queue.isNotEmpty() && resolveQueueShouldPlay(commandShouldPlay)

    private fun resolveQueueShouldPlay(commandShouldPlay: Boolean?): Boolean =
        commandShouldPlay ?: (localTransportActiveProvider() || playback.isPlayingFlow.value)

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
        val positionMs = playbackModePositionSnapshot(
            playback.playbackPositionFlow.value.coerceAtLeast(0L)
        )
        return playbackSnapshotEvent(
            type = if (isControllerProvider()) "PLAYBACK_MODE" else "REQUEST_PLAYBACK_MODE",
            positionMs = positionMs,
            includeQueueMutation = true
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
        val roomState = roomStateProvider()
        val (shareableQueue, resolvedCurrentIndex) = currentQueueSnapshot(roomState)
        val shareableTrack = shareableQueue.getOrNull(resolvedCurrentIndex)
        return eventMetadata("HEARTBEAT").copy(
            currentIndex = resolvedCurrentIndex,
            track = shareableTrack,
            queue = shareableQueue.takeIf {
                includeQueue && usesLegacyQueueSnapshot(roomState)
            },
            state = state,
            positionMs = positionMs.coerceAtLeast(0L)
        )
    }

    fun buildRequestLinkEvent(
        stableKey: String,
        currentIndex: Int? = null,
        track: ListenTogetherTrack? = null,
        forceRefresh: Boolean = false
    ): ListenTogetherEvent {
        return eventMetadata("REQUEST_LINK").copy(
            currentIndex = currentIndex,
            track = track,
            requestTrackStableKey = stableKey,
            forceRefresh = forceRefresh.takeIf { it }
        )
    }

    fun buildLinkReadyEvent(stableKey: String, positionMs: Long, streamUrlOverride: String? = null, streamUrlsOverride: List<String> = emptyList()): ListenTogetherEvent? {
        val song = playback.currentSongFlow.value ?: return null
        if (song.toListenTogetherTrackOrNull()?.stableKey != stableKey) return null
        val (queue, index) = linkQueueSnapshot(song)
        val track = queue.getOrNull(index) ?: return null
        if (track.stableKey != stableKey) return null
        val trusted = track.withStreamUrls(linkStreamUrls(streamUrlOverride, streamUrlsOverride))
        if (trusted.streamUrls.isEmpty()) return null
        return eventMetadata("LINK_READY").copy(
            currentIndex = index, track = trusted,
            queue = queue.mergeCurrentTrack(index, trusted),
            state = linkPlaybackState(), positionMs = positionMs.coerceAtLeast(0L), requestTrackStableKey = stableKey
        )
    }

    private fun linkQueueSnapshot(song: SongItem): Pair<List<ListenTogetherTrack>, Int> {
        val queue = playback.currentQueueFlow.value
        val index = queue.indexOfTrack(song).coerceAtLeast(0)
        return queue.toShareableQueueSnapshot(index, roomStateProvider()?.settings, includeResolvedStreamUrl = true)
    }

    private fun linkStreamUrls(primary: String?, overrides: List<String>): List<String> {
        if (overrides.isNotEmpty()) return overrides
        return listOfNotNull(primary) + playback.currentListenTogetherShareableStreamUrls()
    }

    private fun linkPlaybackState(): String = resolveListenTogetherLinkReadyState(
        roomStateProvider()?.playback?.state, localTransportActiveProvider(), playback.isPlayingFlow.value
    )

    fun buildLinkUnavailableEvent(stableKey: String): ListenTogetherEvent? {
        val currentSong = playback.currentSongFlow.value ?: return null
        val currentTrack = currentSong.toListenTogetherTrackOrNull() ?: return null
        if (currentTrack.stableKey != stableKey) return null
        val currentIndex = playback.currentQueueFlow.value
            .indexOfFirst { song -> song.sameTrackAs(currentSong) }
            .takeIf { it >= 0 }
        return eventMetadata("LINK_UNAVAILABLE").copy(
            currentIndex = currentIndex,
            track = currentTrack.withStreamUrls(emptyList()),
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

    fun buildTrackFinishedEvent(command: PlaybackCommand, queue: List<SongItem>, currentSong: SongItem?, positionMs: Long): ListenTogetherEvent? {
        if (queue.isEmpty() || currentSong == null) return null
        val finished = currentSong.toListenTogetherTrackOrNull() ?: return null
        val nextIndex = nextTrackIndex(command, queue, currentSong)
        if (!queue.hasShareableListenTogetherTrackAt(nextIndex)) return null
        val room = roomStateProvider()
        val (tracks, index) = queue.toShareableQueueSnapshot(nextIndex, room?.settings, includeResolvedStreamUrl = false)
        val event = eventMetadata("TRACK_FINISHED").copy(positionMs = positionMs.coerceAtLeast(0L), finishedTrackStableKey = finished.stableKey)
        if (!isControllerProvider()) return event
        return controllerTrackFinished(event, command, room, tracks, index)
    }

    private fun nextTrackIndex(command: PlaybackCommand, queue: List<SongItem>, song: SongItem): Int =
        command.currentIndex?.coerceIn(0, queue.lastIndex) ?: queue.indexOfTrack(song).coerceAtLeast(0)

    private fun controllerTrackFinished(event: ListenTogetherEvent, command: PlaybackCommand, room: ListenTogetherRoomState?, tracks: List<ListenTogetherTrack>, index: Int): ListenTogetherEvent {
        val advance = command.shouldPlay == true
        val payload = buildListenTogetherQueueEventPayload(room, tracks, index)
        return event.copy(
            currentIndex = index, nextIndex = index,
            track = if (advance) tracks.getOrNull(index) else null,
            queue = payload.queue, queueMutation = payload.mutation, legacyQueueSnapshot = payload.legacySnapshot,
            shouldPlay = advance
        )
    }

    fun buildControllerCommitEventFromForwardedRequest(
        message: ListenTogetherSocketEnvelope
    ): ListenTogetherEvent? {
        val requestType = message.causedBy?.type ?: return null
        val commitType = requestType.removePrefix("REQUEST_")
        if (commitType == requestType) return null
        val rawPositionMs = message.positionMs ?: message.expectedPositionMs ?: 0L
        val positionMs = if (commitType == "PLAYBACK_MODE") {
            playbackModePositionSnapshot(rawPositionMs)
        } else {
            rawPositionMs
        }
        return eventMetadata(commitType).copy(
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = message.currentIndex,
            track = message.track,
            queue = message.queue,
            shouldPlay = message.shouldPlay,
            state = message.stateName,
            repeatMode = message.repeatMode,
            shuffleEnabled = message.shuffleEnabled,
            queueMutation = message.queueMutation,
            requestTrackStableKey = message.requestTrackStableKey
        )
    }

    private fun playbackModePositionSnapshot(positionMs: Long): Long {
        val roomState = roomStateProvider()
        val currentTrack = roomState?.currentTrack()
        val durationMs = currentTrack?.durationMs
            ?: playback.currentSongFlow.value?.durationMs
            ?: 0L
        val previousRepeatMode = roomState?.playback?.repeatMode
            ?: playback.repeatModeFlow.value
        return wrapListenTogetherSingleTrackRepeatPosition(
            positionMs = positionMs,
            repeatMode = previousRepeatMode,
            durationMs = durationMs
        )
    }

    private val commandEventBuilder = ListenTogetherPlaybackCommandEventBuilder(this, playback, songMapper, roomStateProvider, isControllerProvider, localTransportActiveProvider)

    fun buildEventForPlaybackCommand(command: PlaybackCommand): ListenTogetherEvent? = commandEventBuilder.build(command)

    private fun playbackSnapshotEvent(
        type: String,
        positionMs: Long,
        includeQueueMutation: Boolean = false
    ): ListenTogetherEvent {
        val roomState = roomStateProvider()
        val (shareableQueue, resolvedCurrentIndex) = currentQueueSnapshot(roomState)
        val shareableTrack = shareableQueue.getOrNull(resolvedCurrentIndex)
        val payload = buildListenTogetherQueueEventPayload(roomState, shareableQueue, resolvedCurrentIndex, includeQueueMutation, alwaysIncludeSnapshot = false)
        val resolvedState = when (type.removePrefix("REQUEST_")) {
            "PLAY" -> "playing"
            "PAUSE" -> "paused"
            else -> localPlaybackStateNameProvider()
        }
        return eventMetadata(type).copy(
            positionMs = positionMs.coerceAtLeast(0L),
            currentIndex = resolvedCurrentIndex,
            track = shareableTrack,
            queue = payload.queue,
            queueMutation = payload.mutation,
            legacyQueueSnapshot = payload.legacySnapshot,
            shouldPlay = resolvedState == "playing",
            state = resolvedState,
            repeatMode = playback.repeatModeFlow.value,
            shuffleEnabled = playback.shuffleModeFlow.value
        )
    }

    private fun currentQueueSnapshot(room: ListenTogetherRoomState?): Pair<List<ListenTogetherTrack>, Int> {
        val queue = playback.currentQueueFlow.value
        val index = queue.indexOfTrack(playback.currentSongFlow.value).coerceAtLeast(0)
        return queue.toShareableQueueSnapshot(index, room?.settings, includeResolvedStreamUrl = false)
    }

    private fun eventMetadata(type: String) = ListenTogetherEvent(
        type = type,
        eventId = eventIdFactory(),
        clientTimeMs = System.currentTimeMillis(),
        clientInstanceId = clientInstanceIdProvider(),
        clientSequence = clientSequenceFactory()
    )

}
