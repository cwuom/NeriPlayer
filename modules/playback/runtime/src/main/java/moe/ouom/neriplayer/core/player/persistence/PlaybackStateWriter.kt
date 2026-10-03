package moe.ouom.neriplayer.core.player.persistence

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicReference
import moe.ouom.neriplayer.data.model.playback.PersistedState

internal class PlaybackStateWriter(
    private val buildQueueState: (PlaybackStatePersistenceSnapshot) -> PersistedState = { it.toPersistedState() }
) {
    private sealed interface Confirmation {
        class Unconfirmed : Confirmation
        data class Room(val snapshot: PlaybackStatePersistenceSnapshot) : Confirmation
        data object Legacy : Confirmation
    }

    private val confirmation = AtomicReference<Confirmation>(Confirmation.Unconfirmed())

    fun invalidate() {
        confirmation.set(Confirmation.Unconfirmed())
    }

    // 由保存协调器串行调用，失效操作可以来自播放器线程
    suspend fun write(
        snapshot: PlaybackStatePersistenceSnapshot,
        roomStore: PlaybackQueueStateStore,
        legacyStore: PlaybackQueueLegacyStore,
        onRoomFailure: (Throwable) -> Unit = {}
    ): PlaybackQueuePersistTarget {
        val previous = confirmation.get()
        val previousSnapshot = (previous as? Confirmation.Room)?.snapshot
        val write = snapshot.writeAfter(previousSnapshot)
        if (write == PlaybackStateWrite.NONE) return PlaybackQueuePersistTarget.NONE

        try {
            val target = persistPlaybackQueueWithRoomFallback(
                roomStore = roomStore,
                legacyStore = legacyStore,
                queueState = null,
                playbackState = snapshot.playback,
                shouldWriteQueueState = write != PlaybackStateWrite.UPDATE_PLAYBACK,
                shouldWritePlaybackState = true,
                onRoomFailure = onRoomFailure,
                queueStateProvider = if (write == PlaybackStateWrite.CLEAR) null else { { buildQueueState(snapshot) } }
            )
            currentCoroutineContext().ensureActive()
            val next = when (target) {
                PlaybackQueuePersistTarget.ROOM -> Confirmation.Room(snapshot)
                PlaybackQueuePersistTarget.LEGACY_JSON -> Confirmation.Legacy
                PlaybackQueuePersistTarget.NONE -> previous
            }
            // 写盘期间发生失效时，旧写入不能重新确认已经过期的基线
            confirmation.compareAndSet(previous, next)
            return target
        } catch (error: Exception) {
            confirmation.compareAndSet(previous, Confirmation.Unconfirmed())
            throw error
        }
    }
}
