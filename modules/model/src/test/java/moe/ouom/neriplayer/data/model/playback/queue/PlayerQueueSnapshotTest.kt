package moe.ouom.neriplayer.data.model.playback.queue

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlayerQueueSnapshotTest {
    private fun song(id: Long) = SongItem(
        id = id, name = "song $id", artist = "artist", album = "album",
        albumId = 0L, durationMs = 1_000L, coverUrl = null
    )

    @Test
    fun `out of range indices collapse to no selection`() {
        val songs = listOf(song(1L), song(2L))

        assertEquals(1, PlayerQueueSnapshot.from(songs, 1).currentIndex)
        assertEquals(-1, PlayerQueueSnapshot.from(songs, 2).currentIndex)
        assertEquals(-1, PlayerQueueSnapshot.from(songs, -1).currentIndex)
        assertEquals(-1, PlayerQueueSnapshot.EMPTY.selecting(0).currentIndex)
    }

    @Test
    fun `snapshots own their playlist and selecting keeps it`() {
        val songs = mutableListOf(song(1L), song(2L))
        val snapshot = PlayerQueueSnapshot.from(songs, 0)
        songs.clear()

        val selected = snapshot.selecting(1)

        assertEquals(listOf(1L, 2L), selected.playlist.map { it.id })
        assertEquals(1, selected.currentIndex)
        assertEquals(0, snapshot.currentIndex)
        assertSame(snapshot.playlist, selected.playlist)
    }
}
