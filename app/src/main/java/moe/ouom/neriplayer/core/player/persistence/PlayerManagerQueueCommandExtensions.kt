@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.persistence

import moe.ouom.neriplayer.core.player.queue.policy.PlayerQueueEditOwner
import moe.ouom.neriplayer.core.player.queue.policy.QueueInsertPlacement
import moe.ouom.neriplayer.core.player.queue.policy.RemoveQueueEdit
import moe.ouom.neriplayer.core.player.queue.policy.RemovedQueuePlaybackAction

import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.playback.playAtIndex
import moe.ouom.neriplayer.core.player.policy.command.PlaybackCommandSource
import moe.ouom.neriplayer.data.model.SongItem

internal fun PlayerManager.replaceCurrentInQueueAndPlayImpl(
    song: SongItem,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL,
    bypassLoudVolumeWarning: Boolean = false
) {
    ensureInitialized()
    if (!initialized) return
    replaceCurrentWithReadyPlayer(song, commandSource, bypassLoudVolumeWarning)
}

private fun PlayerManager.replaceCurrentWithReadyPlayer(
    song: SongItem,
    source: PlaybackCommandSource,
    bypassWarning: Boolean,
) {
    if (currentQueueSnapshot().currentIndex < 0) {
        NPLogger.d(
            "NERI-PlayerManager",
            "replaceCurrentInQueueAndPlay(): queue empty, fallback to playPlaylist, song=${song.name}/${song.id}"
        )
        playPlaylist(listOf(song), 0, source)
        return
    }
    replaceCurrentIfRoomAllowed(song, source, bypassWarning)
}

private fun PlayerManager.replaceCurrentIfRoomAllowed(
    song: SongItem,
    source: PlaybackCommandSource,
    bypassWarning: Boolean,
) {
    if (shouldBlockLocalRoomControl(source)) return
    replaceCurrentIfSongAllowed(song, source, bypassWarning)
}

private fun PlayerManager.replaceCurrentIfSongAllowed(
    song: SongItem,
    source: PlaybackCommandSource,
    bypassWarning: Boolean,
) {
    if (shouldBlockLocalSongSwitch(song, source)) return
    replaceCurrentAfterLoudnessGate(song, source, bypassWarning)
}

private fun PlayerManager.replaceCurrentAfterLoudnessGate(
    song: SongItem,
    source: PlaybackCommandSource,
    bypassWarning: Boolean,
) {
    if (requestUsbExclusiveLoudPlaybackConfirmation(
            commandSource = source,
            bypassWarning = bypassWarning,
            continuePlayback = {
                replaceCurrentInQueueAndPlayImpl(
                    song = song,
                    commandSource = source,
                    bypassLoudVolumeWarning = true
                )
            }
        )
    ) {
        return
    }
    applyCurrentReplacement(song, source)
}

private fun PlayerManager.applyCurrentReplacement(song: SongItem, source: PlaybackCommandSource) {
    var existingIndex = -1
    val updatedQueue = updateCurrentQueue(bumpDisplayRevision = true) { snapshot ->
        val planned = PlayerQueueEditOwner.replaceCurrent(snapshot, song) ?: return@updateCurrentQueue null
        existingIndex = planned.existingIndex
        planned.queue
    } ?: return
    suppressAutoResumeForCurrentSession = false
    consecutivePlayFailures = 0

    NPLogger.d(
        "NERI-PlayerManager",
        "replaceCurrentInQueueAndPlay(): song=${song.name}/${song.id}, existingIndex=$existingIndex, targetIndex=${updatedQueue.currentIndex}, queueSize=${updatedQueue.playlist.size}, source=$source, stack=[${debugStackHint()}]"
    )
    playAtIndex(updatedQueue.currentIndex, commandSource = source)
    emitPlaybackCommand(
        type = "PLAY_FROM_QUEUE",
        source = source,
        queue = updatedQueue.playlist,
        currentIndex = updatedQueue.currentIndex,
        positionMs = _playbackPositionMs.value
    )
}

internal fun PlayerManager.moveQueueItemImpl(fromIndex: Int, toIndex: Int) {
    ensureInitialized()
    if (!initialized) return
    moveQueueIfRoomAllowed(fromIndex, toIndex)
}

private fun PlayerManager.moveQueueIfRoomAllowed(fromIndex: Int, toIndex: Int) {
    if (shouldBlockLocalRoomControl(PlaybackCommandSource.LOCAL)) return
    applyQueueMove(fromIndex, toIndex)
}

private fun PlayerManager.applyQueueMove(fromIndex: Int, toIndex: Int) {
    var oldIndex = -1
    val updatedQueue = updateCurrentQueue(bumpDisplayRevision = true) { snapshot ->
        oldIndex = snapshot.currentIndex
        PlayerQueueEditOwner.move(snapshot, fromIndex, toIndex)
    } ?: return

    NPLogger.d(
        "NERI-PlayerManager",
        "moveQueueItem(): from=$fromIndex, to=$toIndex, queueSize=${updatedQueue.playlist.size}, oldIndex=$oldIndex, currentIndex=${updatedQueue.currentIndex}, shuffle=${player.shuffleModeEnabled}, stack=[${debugStackHint()}]"
    )
    emitQueueUpdateCommand()

    scheduleStatePersist(debounceMs = 0L)
}

internal fun PlayerManager.removeQueueItemImpl(index: Int) {
    ensureInitialized()
    if (!initialized) return
    removeQueueIfRoomAllowed(index)
}

private fun PlayerManager.removeQueueIfRoomAllowed(index: Int) {
    if (shouldBlockLocalRoomControl(PlaybackCommandSource.LOCAL)) return
    applyQueueRemoval(index)
}

private fun PlayerManager.applyQueueRemoval(index: Int) {
    var removal: RemoveQueueEdit? = null
    val updatedQueue = updateCurrentQueue(bumpDisplayRevision = true) { snapshot ->
        val planned = PlayerQueueEditOwner.remove(snapshot, index) ?: return@updateCurrentQueue null
        removal = planned
        planned.queue
    } ?: return
    applyCommittedQueueRemoval(index, updatedQueue.playlist.size, updatedQueue.currentIndex, removal)
}

private fun PlayerManager.applyCommittedQueueRemoval(
    index: Int,
    queueSize: Int,
    selectedIndex: Int,
    edit: RemoveQueueEdit?,
) {
    val removal = edit ?: return
    val playbackAction = PlayerQueueEditOwner.removalPlaybackAction(
        removal,
        transportActive = isTransportActiveWithoutInitialization(),
    )

    NPLogger.d(
        "NERI-PlayerManager",
        "removeQueueItem(): index=$index, removed=${removal.removedSong.name}/${removal.removedSong.id}, queueSize=$queueSize, currentIndex=$selectedIndex, action=$playbackAction, shuffle=${player.shuffleModeEnabled}, stack=[${debugStackHint()}]"
    )

    applyRemovalPlaybackAction(playbackAction)
}

private fun PlayerManager.applyRemovalPlaybackAction(action: RemovedQueuePlaybackAction) {
    if (action == RemovedQueuePlaybackAction.PLAY_NEXT) {
        playAtIndex(currentIndex, commandSource = PlaybackCommandSource.LOCAL)
        emitQueueUpdateCommand(shouldPlay = true)
        return
    }
    applyRemovalWithoutPlaybackContinuation(action)
}

private fun PlayerManager.applyRemovalWithoutPlaybackContinuation(action: RemovedQueuePlaybackAction) {
    if (action == RemovedQueuePlaybackAction.STOP) {
        stopAfterQueueRemoval()
        return
    }
    setCurrentSongForPlayback(currentPlaylist.getOrNull(currentIndex))
    emitQueueUpdateCommand()
    scheduleStatePersist(debounceMs = 0L)
}

private fun PlayerManager.stopAfterQueueRemoval() {
    stopPlaybackPreservingQueue(clearMediaUrl = true)
    emitQueueUpdateCommand(shouldPlay = false)
}

internal fun PlayerManager.reorderQueueImpl(
    queue: List<SongItem>,
    currentIndexInQueue: Int,
    commandSource: PlaybackCommandSource = PlaybackCommandSource.LOCAL
): Boolean {
    ensureInitialized()
    if (!initialized) return false
    return reorderQueueIfRoomAllowed(queue, currentIndexInQueue, commandSource)
}

private fun PlayerManager.reorderQueueIfRoomAllowed(
    queue: List<SongItem>,
    currentIndexInQueue: Int,
    source: PlaybackCommandSource,
): Boolean {
    if (shouldBlockLocalRoomControl(source)) return false
    return reorderNonemptyQueue(queue, currentIndexInQueue, source)
}

private fun PlayerManager.reorderNonemptyQueue(
    queue: List<SongItem>,
    currentIndexInQueue: Int,
    source: PlaybackCommandSource,
): Boolean {
    if (queue.isEmpty()) return false
    return applyQueueReorder(queue, currentIndexInQueue, source)
}

private fun PlayerManager.applyQueueReorder(
    queue: List<SongItem>,
    currentIndexInQueue: Int,
    source: PlaybackCommandSource,
): Boolean {
    var oldIndex = -1
    var oldSize = 0
    val updatedQueue = updateCurrentQueue(bumpDisplayRevision = true) { snapshot ->
        oldIndex = snapshot.currentIndex
        oldSize = snapshot.playlist.size
        PlayerQueueEditOwner.reorder(
            current = snapshot,
            requestedQueue = queue,
            requestedIndex = currentIndexInQueue,
            currentSong = _currentSongFlow.value,
        )
    }
    if (updatedQueue == null) {
        NPLogger.w(
            "NERI-PlayerManager",
            "reorderQueue(): rejected stale or ambiguous queue content, oldSize=$oldSize, newSize=${queue.size}"
        )
        return false
    }

    NPLogger.d(
        "NERI-PlayerManager",
        "reorderQueue(): queueSize=${updatedQueue.playlist.size}, oldIndex=$oldIndex, currentIndex=${updatedQueue.currentIndex}, shuffle=${player.shuffleModeEnabled}, stack=[${debugStackHint()}]"
    )

    emitPlaybackCommand(
        type = "SET_QUEUE",
        source = source,
        queue = updatedQueue.playlist,
        currentIndex = updatedQueue.currentIndex,
        positionMs = _playbackPositionMs.value
    )

    scheduleStatePersist(debounceMs = 0L)
    return true
}

internal fun PlayerManager.addToQueueNextImpl(song: SongItem) {
    addToQueueImpl(song, QueueInsertPlacement.NEXT)
}

internal fun PlayerManager.addToQueueEndImpl(song: SongItem) {
    addToQueueImpl(song, QueueInsertPlacement.END)
}

private fun PlayerManager.addToQueueImpl(song: SongItem, placement: QueueInsertPlacement) {
    ensureInitialized()
    if (!initialized) return
    addToQueueIfRoomAllowed(song, placement)
}

private fun PlayerManager.addToQueueIfRoomAllowed(song: SongItem, placement: QueueInsertPlacement) {
    if (shouldBlockLocalRoomControl(PlaybackCommandSource.LOCAL)) return
    addToQueueIfSongAllowed(song, placement)
}

private fun PlayerManager.addToQueueIfSongAllowed(song: SongItem, placement: QueueInsertPlacement) {
    if (shouldBlockLocalSongSwitch(song, PlaybackCommandSource.LOCAL)) return
    addToQueueWithReadyPlayer(song, placement)
}

private fun PlayerManager.addToQueueWithReadyPlayer(song: SongItem, placement: QueueInsertPlacement) {
    if (currentPlaylist.isEmpty()) {
        NPLogger.d(
            "NERI-PlayerManager",
            "${placement.logName}(): queue empty, fallback to playPlaylist, song=${song.name}/${song.id}"
        )
        playPlaylist(listOf(song), 0)
        return
    }
    applyQueueInsertion(song, placement)
}

private fun PlayerManager.applyQueueInsertion(song: SongItem, placement: QueueInsertPlacement) {
    var existingIndex = -1
    var insertedIndex = -1
    val updatedQueue = updateCurrentQueue(bumpDisplayRevision = true) { snapshot ->
        val planned = PlayerQueueEditOwner.insert(snapshot, song, _currentSongFlow.value, placement)
            ?: return@updateCurrentQueue null
        existingIndex = planned.existingIndex
        insertedIndex = planned.insertedIndex
        planned.queue
    } ?: return
    NPLogger.d(
        "NERI-PlayerManager",
        "${placement.logName}(): song=${song.name}/${song.id}, existingIndex=$existingIndex, insertIndex=$insertedIndex, queueSize=${updatedQueue.playlist.size}, currentIndex=${updatedQueue.currentIndex}, shuffle=${player.shuffleModeEnabled}, stack=[${debugStackHint()}]"
    )
    emitQueueUpdateCommand()
    scheduleStatePersist(debounceMs = 0L)
}

private fun PlayerManager.emitQueueUpdateCommand(shouldPlay: Boolean? = null) {
    emitPlaybackCommand(
        type = "SET_QUEUE",
        source = PlaybackCommandSource.LOCAL,
        queue = currentPlaylist.toList(),
        currentIndex = currentIndex,
        positionMs = _playbackPositionMs.value,
        shouldPlay = shouldPlay
    )
}

internal fun PlayerManager.applyRemoteQueueUpdateImpl(
    queue: List<SongItem>,
    currentIndexInQueue: Int
) {
    ensureInitialized()
    if (!initialized) return
    publishRemoteQueueEdit(queue, currentIndexInQueue)
}

private fun PlayerManager.publishRemoteQueueEdit(queue: List<SongItem>, currentIndexInQueue: Int) {
    val updatedQueue = PlayerQueueEditOwner.remote(queue, currentIndexInQueue)
    publishCurrentQueue(
        updatedQueue.playlist,
        updatedQueue.currentIndex,
        bumpDisplayRevision = true,
    )
    if (queue.isEmpty()) {
        stopPlaybackPreservingQueue(clearMediaUrl = true)
    }

    scheduleStatePersist(debounceMs = 0L)
}
