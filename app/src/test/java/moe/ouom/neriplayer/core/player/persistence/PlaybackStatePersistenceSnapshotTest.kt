package moe.ouom.neriplayer.core.player.persistence

import moe.ouom.neriplayer.core.player.model.PersistedPlaybackState
import moe.ouom.neriplayer.core.player.model.PlayerQueueSnapshot
import moe.ouom.neriplayer.core.player.model.PlayerQueueStateStore
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackStatePersistenceSnapshotTest {
    @Test
    fun `empty snapshots clear storage once and clear again when playback state changes`() {
        val empty = PlayerQueueSnapshot.EMPTY
        val previous = PlaybackStatePersistenceSnapshot(empty, PersistedPlaybackState(-1), null, -1)
        val changed = PlaybackStatePersistenceSnapshot(empty, PersistedPlaybackState(-1, positionMs = 1L), null, -1)
        val nonempty = PlaybackStatePersistenceSnapshot(
            PlayerQueueSnapshot.from(listOf(song(1L)), 0), PersistedPlaybackState(0), null, -1
        )

        assertEquals(PlaybackStateWrite.CLEAR, previous.writeAfter(null))
        assertEquals(PlaybackStateWrite.CLEAR, previous.writeAfter(nonempty))
        assertEquals(PlaybackStateWrite.NONE, previous.writeAfter(previous))
        assertEquals(PlaybackStateWrite.CLEAR, changed.writeAfter(previous))
    }

    @Test
    fun `restore selection prefers current identity and otherwise preserves an available fallback`() {
        val restore = listOf(song(2L), song(1L))
        val cases = listOf(
            Triple(listOf(song(1L)), 0, 1),
            Triple(listOf(song(3L)), 0, 0),
            Triple(listOf(song(1L)), -1, 0)
        )
        cases.forEach { (songs, selected, expected) ->
            val state = PlaybackStatePersistenceSnapshot(
                PlayerQueueSnapshot.from(songs, selected),
                PersistedPlaybackState(selected, shuffleEnabled = true), restore, 0
            ).toPersistedState()
            assertEquals(expected, state.shuffleRestoreIndex)
        }
        val withoutFallback = PlaybackStatePersistenceSnapshot(
            PlayerQueueSnapshot.from(listOf(song(3L)), 0),
            PersistedPlaybackState(0, shuffleEnabled = true), restore, -1
        ).toPersistedState()
        assertNull(withoutFallback.shuffleRestoreIndex)
    }

    @Test
    fun `changed restore fallback and changed playlist storage both require replacement`() {
        val queue = PlayerQueueSnapshot.from(listOf(song(1L)), -1)
        val playback = PersistedPlaybackState(-1, shuffleEnabled = true)
        val previous = PlaybackStatePersistenceSnapshot(queue, playback, listOf(song(1L)), 0)
        val fallbackChanged = PlaybackStatePersistenceSnapshot(queue, playback, listOf(song(1L)), 1)
        val copiedQueue = PlaybackStatePersistenceSnapshot(
            PlayerQueueSnapshot.from(queue.playlist, -1), playback, listOf(song(1L)), 0
        )

        assertEquals(PlaybackStateWrite.REPLACE_QUEUE, fallbackChanged.writeAfter(previous))
        assertEquals(PlaybackStateWrite.REPLACE_QUEUE, copiedQueue.writeAfter(previous))
    }

    @Test
    fun `persistence rejects mismatched selection and negative progress`() {
        val queue = PlayerQueueSnapshot.from(listOf(song(1L)), 0)
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackStatePersistenceSnapshot(queue, PersistedPlaybackState(-1), null, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackStatePersistenceSnapshot(queue, PersistedPlaybackState(0, positionMs = -1L), null, -1)
        }
    }

    @Test
    fun `selection only updates retain the queue reference for incremental persistence`() {
        val store = PlayerQueueStateStore()
        store.publish(listOf(song(1L), song(2L)), 0)
        val before = store.sessionSnapshot()
        val previous = PlaybackStatePersistenceSnapshot(before, PersistedPlaybackState(0), true)
        store.select(1)
        val after = store.sessionSnapshot()
        val updated = PlaybackStatePersistenceSnapshot(after, PersistedPlaybackState(1), true)

        assertSame(before.queue.playlist, after.queue.playlist)
        assertEquals(PlaybackStateWrite.UPDATE_PLAYBACK, updated.writeAfter(previous))
    }

    @Test
    fun `disabled mode persistence omits both session shuffle mode and its restore order`() {
        val store = PlayerQueueStateStore()
        store.publish(listOf(song(1L), song(2L)), 1)
        store.setLocalShuffle(true, song(2L)) { it.reverse() }
        val session = store.sessionSnapshot()
        val state = PlaybackStatePersistenceSnapshot(
            session, PersistedPlaybackState(session.queue.currentIndex), keepShuffleMode = false
        ).toPersistedState()

        assertEquals(listOf(2L, 1L), state.playlist.map { it.id })
        assertEquals(false, state.shuffleEnabled)
        assertNull(state.shuffleRestorePlaylist)
        assertNull(state.shuffleRestoreIndex)
    }

    @Test
    fun `captured queue selection and shuffle order survive later list changes`() {
        val first = song(1L).copy(matchedLyric = "first lyrics")
        val second = song(2L).copy(matchedLyric = "second lyrics")
        val restore = mutableListOf(second, first)
        val queue = PlayerQueueSnapshot.from(listOf(first, second), 0)
        val snapshot = PlaybackStatePersistenceSnapshot(
            queue,
            PersistedPlaybackState(0, positionMs = 42_000L, shuffleEnabled = true),
            restore,
            1
        )
        restore.clear()

        val state = snapshot.toPersistedState()
        assertEquals(listOf(1L, 2L), state.playlist.map { it.id })
        assertEquals(0, state.index)
        assertEquals(42_000L, state.positionMs)
        assertEquals("first lyrics", state.playlist[0].matchedLyric)
        assertNull(state.playlist[1].matchedLyric)
        assertEquals(listOf(2L, 1L), state.shuffleRestorePlaylist?.map { it.id })
        assertEquals(1, state.shuffleRestoreIndex)
        assertNull(state.shuffleRestorePlaylist?.get(0)?.matchedLyric)
        assertEquals("first lyrics", state.shuffleRestorePlaylist?.get(1)?.matchedLyric)
    }

    @Test
    fun `changing only the restore queue still requires a full queue write`() {
        val first = song(1L)
        val second = song(2L)
        val queue = PlayerQueueSnapshot.from(listOf(first, second), 0)
        val playback = PersistedPlaybackState(0, shuffleEnabled = true)
        val previous = PlaybackStatePersistenceSnapshot(queue, playback, listOf(first, second), 0)
        val updated = PlaybackStatePersistenceSnapshot(queue, playback, listOf(second, first), 1)

        assertEquals(PlaybackStateWrite.REPLACE_QUEUE, updated.writeAfter(previous))
    }

    @Test
    fun `only playback changes use the incremental write plan`() {
        val queue = PlayerQueueSnapshot.from(listOf(song(1L)), 0)
        val playback = PersistedPlaybackState(0, positionMs = 1_000L)
        val previous = PlaybackStatePersistenceSnapshot(queue, playback, null, -1)
        val updated = PlaybackStatePersistenceSnapshot(queue, playback.copy(positionMs = 2_000L), null, -1)

        assertEquals(PlaybackStateWrite.REPLACE_QUEUE, previous.writeAfter(null))
        assertEquals(PlaybackStateWrite.NONE, previous.writeAfter(previous))
        assertEquals(PlaybackStateWrite.UPDATE_PLAYBACK, updated.writeAfter(previous))
    }

    @Test
    fun `disabling shuffle omits its obsolete restore data`() {
        val song = song(1L)
        val snapshot = PlaybackStatePersistenceSnapshot(
            PlayerQueueSnapshot.from(listOf(song), 0),
            PersistedPlaybackState(0, shuffleEnabled = false),
            listOf(song),
            0
        )

        assertNull(snapshot.toPersistedState().shuffleRestorePlaylist)
        assertNull(snapshot.toPersistedState().shuffleRestoreIndex)
    }

    private fun song(id: Long) = SongItem(id, "Song $id", "Artist", "Album", id, 180_000L, null)
}
