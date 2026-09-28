package moe.ouom.neriplayer.core.player.queue.policy

import androidx.media3.common.Player
import moe.ouom.neriplayer.core.player.queue.model.PlayerQueueSnapshot
import moe.ouom.neriplayer.core.player.queue.model.reorderQueueSongsPreservingLatestMetadata
import moe.ouom.neriplayer.core.player.queue.model.resolvePlayerQueueRestoreOrder
import moe.ouom.neriplayer.core.player.queue.model.resolvePlayerRepeatAllShuffleOrder
import moe.ouom.neriplayer.core.player.queue.model.resolvePlayerSequentialShuffleOrder
import moe.ouom.neriplayer.core.player.policy.failure.PlaybackFailureAdvanceAction
import moe.ouom.neriplayer.data.model.SongItem

internal data class ListenTogetherTrackFinishPlan(
    val shouldAdvance: Boolean,
    val nextIndex: Int,
)

internal data class QueueNavigationStep(
    val index: Int,
    val reshuffleAtWrap: Boolean = false,
)

internal enum class QueueTrackCompletion {
    REPLAY_CURRENT,
    ADVANCE,
    WRAP,
    STOP,
}

internal data class RemotePlaybackModeUpdate(
    val repeatMode: Int?,
    val shuffleEnabled: Boolean?,
)

internal object PlayerQueueNavigationOwner {
    fun hasSelectedTrack(queueSize: Int, currentIndex: Int): Boolean =
        currentIndex in 0 until queueSize

    fun hasShuffleRestoreSnapshot(playlist: List<SongItem>?): Boolean = playlist != null

    fun listenerCannotControlQueue(isController: Boolean): Boolean = !isController

    fun forceWrapAfterCompletion(action: QueueTrackCompletion): Boolean =
        action == QueueTrackCompletion.WRAP

    fun forceWrapAfterFailure(action: PlaybackFailureAdvanceAction): Boolean =
        action == PlaybackFailureAdvanceAction.WRAP

    fun listenTogetherFinishPlan(
        queueSize: Int,
        currentIndex: Int,
        repeatMode: Int,
    ): ListenTogetherTrackFinishPlan {
        val fallbackIndex = currentIndex.coerceIn(0, queueSize - 1)
        if (repeatMode == Player.REPEAT_MODE_ONE) {
            return ListenTogetherTrackFinishPlan(true, fallbackIndex)
        }
        val next = nextStep(queueSize, currentIndex, repeatMode, force = false)
        return ListenTogetherTrackFinishPlan(next != null, next?.index ?: fallbackIndex)
    }

    fun trackFinishPosition(song: SongItem?, playerDurationMs: Long, playerPositionMs: Long): Long =
        maxOf(song?.durationMs?.coerceAtLeast(0L) ?: 0L, playerDurationMs, playerPositionMs)

    fun finishedDuration(song: SongItem?, reportedDurationMs: Long): Long =
        maxOf(song?.durationMs?.coerceAtLeast(0L) ?: 0L, reportedDurationMs)

    fun shouldUseTransitionFade(fadeEnabled: Boolean, isPlaying: Boolean, playWhenReady: Boolean): Boolean =
        fadeEnabled && (isPlaying || playWhenReady)

    fun trackCompletion(queueSize: Int, currentIndex: Int, repeatMode: Int): QueueTrackCompletion = when {
        repeatMode == Player.REPEAT_MODE_ONE -> QueueTrackCompletion.REPLAY_CURRENT
        repeatMode == Player.REPEAT_MODE_ALL -> QueueTrackCompletion.WRAP
        currentIndex < queueSize - 1 -> QueueTrackCompletion.ADVANCE
        else -> QueueTrackCompletion.STOP
    }

    fun nextStep(
        queueSize: Int,
        currentIndex: Int,
        repeatMode: Int,
        force: Boolean,
    ): QueueNavigationStep? {
        if (queueSize <= 0) return null
        if (currentIndex < queueSize - 1) return QueueNavigationStep(currentIndex + 1)
        return wrappedNextStep(repeatMode, force)
    }

    private fun wrappedNextStep(repeatMode: Int, force: Boolean): QueueNavigationStep? =
        if (force || repeatMode == Player.REPEAT_MODE_ALL) QueueNavigationStep(0, reshuffleAtWrap = true)
        else null

    fun previousIndex(queueSize: Int, currentIndex: Int, repeatMode: Int): Int? {
        if (queueSize <= 0) return null
        if (currentIndex > 0) return currentIndex - 1
        return if (repeatMode == Player.REPEAT_MODE_ALL) queueSize - 1 else null
    }

    fun nextRepeatMode(currentMode: Int): Int = when (currentMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
        else -> Player.REPEAT_MODE_OFF
    }

    fun remoteModeUpdate(
        currentRepeatMode: Int,
        currentShuffleEnabled: Boolean,
        requestedRepeatMode: Int?,
        requestedShuffleEnabled: Boolean?,
    ): RemotePlaybackModeUpdate? {
        val repeatMode = changedRepeatMode(currentRepeatMode, requestedRepeatMode)
        val shuffleEnabled = changedShuffleMode(currentShuffleEnabled, requestedShuffleEnabled)
        return changedRemoteMode(repeatMode, shuffleEnabled)
    }

    private fun changedRepeatMode(currentMode: Int, requestedMode: Int?): Int? =
        normalizedRepeatMode(requestedMode)?.takeIf { it != currentMode }

    private fun changedShuffleMode(currentMode: Boolean, requestedMode: Boolean?): Boolean? =
        requestedMode?.takeIf { it != currentMode }

    private fun changedRemoteMode(repeatMode: Int?, shuffleEnabled: Boolean?): RemotePlaybackModeUpdate? {
        if (repeatMode == null && shuffleEnabled == null) return null
        return RemotePlaybackModeUpdate(repeatMode, shuffleEnabled)
    }

    private fun normalizedRepeatMode(mode: Int?): Int? = when (mode) {
        null -> null
        Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE -> mode
        else -> Player.REPEAT_MODE_OFF
    }

    fun captureShuffleRestore(queue: PlayerQueueSnapshot): PlayerQueueSnapshot? =
        queue.takeIf { it.playlist.isNotEmpty() }

    fun restoreShuffleOrder(
        current: PlayerQueueSnapshot,
        restorePlaylist: List<SongItem>?,
        currentSong: SongItem?,
        fallbackIndex: Int,
    ): PlayerQueueSnapshot? {
        val restoreOrder = resolvePlayerQueueRestoreOrder(
            restorePlaylist = restorePlaylist,
            currentSong = currentSong,
            fallbackIndex = fallbackIndex,
        ) ?: return null
        val latestSongs = reorderQueueSongsPreservingLatestMetadata(
            currentQueue = current.playlist,
            requestedQueue = restoreOrder.playlist,
        ) ?: return null
        return selectedRestoredQueue(latestSongs, currentSong, restoreOrder.currentIndex)
    }

    private fun selectedRestoredQueue(
        latestSongs: List<SongItem>,
        currentSong: SongItem?,
        fallbackIndex: Int,
    ): PlayerQueueSnapshot? {
        val index = resolvePlayerQueueRestoreOrder(
            restorePlaylist = latestSongs,
            currentSong = currentSong,
            fallbackIndex = fallbackIndex,
        )?.currentIndex ?: return null
        return PlayerQueueSnapshot.from(latestSongs, index)
    }

    fun sequentialShuffle(
        queue: PlayerQueueSnapshot,
        shuffleRemaining: (MutableList<Int>) -> Unit = { it.shuffle() },
    ): PlayerQueueSnapshot? {
        val order = resolvePlayerSequentialShuffleOrder(
            queueSize = queue.playlist.size,
            currentIndex = queue.currentIndex,
            shuffleRemaining = shuffleRemaining,
        )
        if (order.queueIndices.isEmpty()) return null
        val playlist = order.queueIndices.map(queue.playlist::get)
        if (playlist == queue.playlist && queue.currentIndex == order.currentIndex) return null
        return PlayerQueueSnapshot.from(playlist, order.currentIndex)
    }

    fun repeatAllShuffle(
        queue: PlayerQueueSnapshot,
        shuffleQueue: (MutableList<Int>) -> Unit = { it.shuffle() },
    ): PlayerQueueSnapshot? {
        if (queue.playlist.size <= 1 || queue.currentIndex != queue.playlist.lastIndex) return null
        val order = resolvePlayerRepeatAllShuffleOrder(
            queueSize = queue.playlist.size,
            completedIndex = queue.currentIndex,
            shuffleQueue = shuffleQueue,
        )
        return PlayerQueueSnapshot.from(order.queueIndices.map(queue.playlist::get), order.currentIndex)
    }

    fun mayRepeatAllShuffle(
        shuffleEnabled: Boolean,
        repeatMode: Int,
        listenerCannotControlQueue: Boolean,
    ): Boolean = shuffleEnabled && repeatMode == Player.REPEAT_MODE_ALL && !listenerCannotControlQueue
}
