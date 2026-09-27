package moe.ouom.neriplayer.core.player.persistence

import moe.ouom.neriplayer.core.player.model.PersistedPlaybackState
import moe.ouom.neriplayer.core.player.model.PlayerQueueSnapshot
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStatePersistenceSnapshotTest {
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
