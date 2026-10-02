package moe.ouom.neriplayer.data.sync.playlist

import java.util.LinkedList
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlaylistOrderPolicyTest {
    @Test
    fun `already ordered sequential songs retain the snapshot and backing list`() {
        val songs = IteratorOnlySongs(listOf(
            SyncSong(id = 1, addedAt = Long.MAX_VALUE),
            SyncSong(id = 2, addedAt = 20),
            SyncSong(id = 3, addedAt = 20),
            SyncSong(id = 4, addedAt = Long.MIN_VALUE)
        ))
        val playlist = SyncPlaylist(id = 1, songs = songs, songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION)

        val normalized = playlist.normalizedForDisplayOrder()

        assertSame(playlist, normalized)
        assertSame(songs, normalized.songs)
        assertEquals(songs.size, songs.visits)
    }

    @Test
    fun `sorting sequential songs preserves original order among equal timestamps`() {
        val songs = LinkedList(listOf(
            SyncSong(id = 1, addedAt = 10),
            SyncSong(id = 2, addedAt = 30),
            SyncSong(id = 3, addedAt = 30),
            SyncSong(id = 4, addedAt = 20),
            SyncSong(id = 5, addedAt = 10)
        ))
        val playlist = SyncPlaylist(id = 1, songs = songs, songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION)

        val normalized = playlist.normalizedForDisplayOrder()

        assertEquals(listOf(2L, 3L, 4L, 1L, 5L), normalized.songs.map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), songs.map { it.id })
        assertSame(normalized, normalized.normalizedForDisplayOrder())
    }

    @Test
    fun `timestamp extremes sort without overflow and retain zero timestamp ties`() {
        val songs = LinkedList(listOf(
            SyncSong(id = 1, addedAt = Long.MIN_VALUE),
            SyncSong(id = 2, addedAt = 0),
            SyncSong(id = 3, addedAt = Long.MAX_VALUE),
            SyncSong(id = 4, addedAt = 0)
        ))
        val playlist = SyncPlaylist(id = 1, songs = songs, songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION)

        val normalized = playlist.normalizedForDisplayOrder()

        assertEquals(listOf(3L, 2L, 4L, 1L), normalized.songs.map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), songs.map { it.id })
    }

    @Test
    fun `deleted snapshots still discard songs and update the ordering version`() {
        val playlist = SyncPlaylist(
            id = 1, songs = listOf(SyncSong(id = 1)), isDeleted = true, songOrderVersion = 0
        )

        val normalized = playlist.normalizedForDisplayOrder()

        assertTrue(normalized.isDeleted)
        assertTrue(normalized.songs.isEmpty())
        assertEquals(DISPLAY_ORDER_SONG_ORDER_VERSION, normalized.songOrderVersion)
    }

    private class IteratorOnlySongs(private val source: List<SyncSong>) : AbstractList<SyncSong>() {
        var visits = 0
            private set
        override val size: Int get() = source.size
        override fun get(index: Int): SyncSong = error("song ordering must support sequential lists")
        override fun iterator(): Iterator<SyncSong> {
            val iterator = source.iterator()
            return object : Iterator<SyncSong> {
                override fun hasNext(): Boolean = iterator.hasNext()
                override fun next(): SyncSong {
                    visits += 1
                    return iterator.next()
                }
            }
        }
    }
}
