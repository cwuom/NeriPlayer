package moe.ouom.neriplayer.core.player.queue.policy

import moe.ouom.neriplayer.core.player.queue.TestQueueSongIdentity
import moe.ouom.neriplayer.data.model.playback.queue.ListenTogetherTrackFinishPlan
import moe.ouom.neriplayer.data.model.playback.queue.PlaybackFailureAdvanceAction
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueSnapshot
import moe.ouom.neriplayer.data.model.playback.queue.QueueNavigationStep
import moe.ouom.neriplayer.data.model.playback.queue.QueueTrackCompletion
import moe.ouom.neriplayer.data.model.playback.queue.RemotePlaybackModeUpdate
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerQueueNavigationOwnerTest {
    private val first = song(1, "First")
    private val second = song(2, "Second")
    private val third = song(3, "Third")

    @Test
    fun `track completion preserves repeat one, repeat all, ordinary advance and stop`() {
        assertEquals(QueueTrackCompletion.REPLAY_CURRENT, PlayerQueueNavigationOwner.trackCompletion(3, 2, QueueRepeatMode.ONE))
        assertEquals(QueueTrackCompletion.WRAP, PlayerQueueNavigationOwner.trackCompletion(3, 2, QueueRepeatMode.ALL))
        assertEquals(QueueTrackCompletion.ADVANCE, PlayerQueueNavigationOwner.trackCompletion(3, 1, QueueRepeatMode.OFF))
        assertEquals(QueueTrackCompletion.STOP, PlayerQueueNavigationOwner.trackCompletion(3, 2, QueueRepeatMode.OFF))
        assertTrue(PlayerQueueNavigationOwner.forceWrapAfterCompletion(QueueTrackCompletion.WRAP))
        assertFalse(PlayerQueueNavigationOwner.forceWrapAfterCompletion(QueueTrackCompletion.ADVANCE))
        assertTrue(PlayerQueueNavigationOwner.forceWrapAfterFailure(PlaybackFailureAdvanceAction.WRAP))
        assertFalse(PlayerQueueNavigationOwner.forceWrapAfterFailure(PlaybackFailureAdvanceAction.NEXT))
    }

    @Test
    fun `listen together finish resolves repeat and end of queue without changing the queue`() {
        assertEquals(ListenTogetherTrackFinishPlan(true, 2), PlayerQueueNavigationOwner.listenTogetherFinishPlan(3, 2, QueueRepeatMode.ONE))
        assertEquals(ListenTogetherTrackFinishPlan(true, 0), PlayerQueueNavigationOwner.listenTogetherFinishPlan(3, 2, QueueRepeatMode.ALL))
        assertEquals(ListenTogetherTrackFinishPlan(true, 2), PlayerQueueNavigationOwner.listenTogetherFinishPlan(3, 1, QueueRepeatMode.OFF))
        assertEquals(ListenTogetherTrackFinishPlan(false, 2), PlayerQueueNavigationOwner.listenTogetherFinishPlan(3, 2, QueueRepeatMode.OFF))
        assertEquals(60_000L, PlayerQueueNavigationOwner.trackFinishPosition(first, 50_000L, 40_000L))
        assertEquals(70_000L, PlayerQueueNavigationOwner.trackFinishPosition(null, 50_000L, 70_000L))
        assertEquals(60_000L, PlayerQueueNavigationOwner.finishedDuration(first, 50_000L))
        assertEquals(70_000L, PlayerQueueNavigationOwner.finishedDuration(null, 70_000L))
    }

    @Test
    fun `next and previous navigation preserve boundaries and forced wrap`() {
        assertTrue(PlayerQueueNavigationOwner.hasSelectedTrack(3, 2))
        assertFalse(PlayerQueueNavigationOwner.hasSelectedTrack(3, -1))
        assertFalse(PlayerQueueNavigationOwner.hasSelectedTrack(3, 3))
        assertFalse(PlayerQueueNavigationOwner.hasSelectedTrack(0, 0))
        assertEquals(QueueNavigationStep(1), PlayerQueueNavigationOwner.nextStep(3, 0, QueueRepeatMode.OFF, false))
        assertEquals(QueueNavigationStep(0, true), PlayerQueueNavigationOwner.nextStep(3, 2, QueueRepeatMode.ALL, false))
        assertEquals(QueueNavigationStep(0, true), PlayerQueueNavigationOwner.nextStep(3, 2, QueueRepeatMode.OFF, true))
        assertNull(PlayerQueueNavigationOwner.nextStep(3, 2, QueueRepeatMode.OFF, false))
        assertNull(PlayerQueueNavigationOwner.nextStep(0, -1, QueueRepeatMode.ALL, true))
        assertEquals(1, PlayerQueueNavigationOwner.previousIndex(3, 2, QueueRepeatMode.OFF))
        assertEquals(2, PlayerQueueNavigationOwner.previousIndex(3, 0, QueueRepeatMode.ALL))
        assertNull(PlayerQueueNavigationOwner.previousIndex(3, 0, QueueRepeatMode.OFF))
        assertNull(PlayerQueueNavigationOwner.previousIndex(0, -1, QueueRepeatMode.ALL))
        assertTrue(PlayerQueueNavigationOwner.shouldUseTransitionFade(true, true, false))
        assertTrue(PlayerQueueNavigationOwner.shouldUseTransitionFade(true, false, true))
        assertFalse(PlayerQueueNavigationOwner.shouldUseTransitionFade(true, false, false))
        assertFalse(PlayerQueueNavigationOwner.shouldUseTransitionFade(false, true, true))
    }

    @Test
    fun `mode changes normalize remote input and ignore unchanged values`() {
        assertEquals(QueueRepeatMode.ALL, PlayerQueueNavigationOwner.nextRepeatMode(QueueRepeatMode.OFF))
        assertEquals(QueueRepeatMode.ONE, PlayerQueueNavigationOwner.nextRepeatMode(QueueRepeatMode.ALL))
        assertEquals(QueueRepeatMode.OFF, PlayerQueueNavigationOwner.nextRepeatMode(QueueRepeatMode.ONE))
        assertEquals(QueueRepeatMode.OFF, PlayerQueueNavigationOwner.nextRepeatMode(999))
        assertEquals(
            RemotePlaybackModeUpdate(QueueRepeatMode.ONE, true),
            PlayerQueueNavigationOwner.remoteModeUpdate(QueueRepeatMode.OFF, false, QueueRepeatMode.ONE, true),
        )
        assertEquals(
            RemotePlaybackModeUpdate(QueueRepeatMode.OFF, null),
            PlayerQueueNavigationOwner.remoteModeUpdate(QueueRepeatMode.ONE, false, 999, false),
        )
        assertEquals(
            RemotePlaybackModeUpdate(QueueRepeatMode.ALL, null),
            PlayerQueueNavigationOwner.remoteModeUpdate(QueueRepeatMode.OFF, false, QueueRepeatMode.ALL, null),
        )
        assertEquals(
            RemotePlaybackModeUpdate(null, false),
            PlayerQueueNavigationOwner.remoteModeUpdate(QueueRepeatMode.OFF, true, null, false),
        )
        assertNull(PlayerQueueNavigationOwner.remoteModeUpdate(QueueRepeatMode.OFF, false, null, null))
        assertNull(PlayerQueueNavigationOwner.remoteModeUpdate(QueueRepeatMode.OFF, false, QueueRepeatMode.OFF, false))
    }

    @Test
    fun `shuffle captures restore queue and keeps selected song first`() {
        val original = queue(listOf(first, second, third), 1)
        assertTrue(PlayerQueueNavigationOwner.hasShuffleRestoreSnapshot(original.playlist))
        assertFalse(PlayerQueueNavigationOwner.hasShuffleRestoreSnapshot(null))
        assertEquals(original, PlayerQueueNavigationOwner.captureShuffleRestore(original))
        assertNull(PlayerQueueNavigationOwner.captureShuffleRestore(PlayerQueueSnapshot.EMPTY))

        val shuffled = PlayerQueueNavigationOwner.sequentialShuffle(original) { it.reverse() }
        assertEquals(listOf(second, third, first), shuffled?.playlist)
        assertEquals(0, shuffled?.currentIndex)
        assertNull(PlayerQueueNavigationOwner.sequentialShuffle(PlayerQueueSnapshot.EMPTY))
        assertNull(PlayerQueueNavigationOwner.sequentialShuffle(queue(listOf(first), 0)))
    }

    @Test
    fun `restore shuffle follows current song and keeps refreshed metadata`() {
        val refreshedSecond = second.copy(name = "Refreshed")
        val current = queue(listOf(second, third, first), 0)
        val refreshed = queue(listOf(refreshedSecond, third, first), 0)
        val restored = PlayerQueueNavigationOwner.restoreShuffleOrder(
            current = refreshed,
            restorePlaylist = listOf(first, second, third),
            currentSong = refreshedSecond,
            fallbackIndex = 1,
            identity = TestQueueSongIdentity,
        )
        assertEquals(listOf(first, refreshedSecond, third), restored?.playlist)
        assertEquals(1, restored?.currentIndex)
        val withoutCurrentSong = PlayerQueueNavigationOwner.restoreShuffleOrder(
            current = current,
            restorePlaylist = listOf(first, second, third),
            currentSong = null,
            fallbackIndex = 2,
            identity = TestQueueSongIdentity,
        )
        assertEquals(2, withoutCurrentSong?.currentIndex)
        assertNull(PlayerQueueNavigationOwner.restoreShuffleOrder(current, null, second, 0, identity = TestQueueSongIdentity))
        assertNull(PlayerQueueNavigationOwner.restoreShuffleOrder(current, listOf(first), second, 0, identity = TestQueueSongIdentity))
    }

    @Test
    fun `repeat all reshuffles only at wrap for a controller`() {
        val original = queue(listOf(first, second, third), 2)
        val reordered = PlayerQueueNavigationOwner.repeatAllShuffle(
            queue = original,
            shuffleQueue = { it.reverse() },
        )
        assertEquals(listOf(second, third, first), reordered?.playlist)
        assertEquals(0, reordered?.currentIndex)
        assertNull(PlayerQueueNavigationOwner.repeatAllShuffle(queue(listOf(first, second), 0)))
        assertNull(PlayerQueueNavigationOwner.repeatAllShuffle(queue(listOf(first), 0)))
        assertFalse(PlayerQueueNavigationOwner.mayRepeatAllShuffle(false, QueueRepeatMode.ALL, false))
        assertFalse(PlayerQueueNavigationOwner.mayRepeatAllShuffle(true, QueueRepeatMode.OFF, false))
        assertFalse(PlayerQueueNavigationOwner.mayRepeatAllShuffle(true, QueueRepeatMode.ALL, true))
        assertTrue(PlayerQueueNavigationOwner.mayRepeatAllShuffle(true, QueueRepeatMode.ALL, false))
        assertTrue(PlayerQueueNavigationOwner.listenerCannotControlQueue(false))
        assertFalse(PlayerQueueNavigationOwner.listenerCannotControlQueue(true))
    }

    @Test
    fun `original queue snapshots remain untouched after navigation`() {
        val original = queue(listOf(first, second, third), 1)
        PlayerQueueNavigationOwner.sequentialShuffle(original) { it.reverse() }
        assertEquals(listOf(first, second, third), original.playlist)
        assertEquals(1, original.currentIndex)
        assertFalse(original.playlist.isEmpty())
        assertTrue(PlayerQueueNavigationOwner.nextStep(3, 1, QueueRepeatMode.OFF, false) != null)
    }

    private fun queue(songs: List<SongItem>, index: Int) = PlayerQueueSnapshot.from(songs, index)

    private fun song(id: Long, name: String) = SongItem(
        id = id,
        name = name,
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        durationMs = 60_000L,
        coverUrl = null,
    )
}
