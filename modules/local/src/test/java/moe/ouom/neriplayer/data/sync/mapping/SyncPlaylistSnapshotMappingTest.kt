package moe.ouom.neriplayer.data.sync.mapping

import android.content.Context
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class SyncPlaylistSnapshotMappingTest {
    @Test
    fun `playlist tombstones retain input order without rescanning current playlists`() {
        val current = (1L..64L).map { LocalPlaylist(it, "playlist $it", modifiedAt = it) }
        val playlists = CountingPlaylistList(current)
        val deletions = linkedMapOf<Long, Long>().apply {
            current.asReversed().forEach { put(it.id, 1_000L + it.id) }
            for (id in 65L..128L) put(id, 1_000L + id)
        }

        val snapshots = buildPlaylistSyncSnapshots(playlists, deletions, mock(Context::class.java))

        assertEquals((1L..128L).toList(), snapshots.map { it.id })
        assertTrue(snapshots.take(64).all { !it.isDeleted })
        assertEquals(current.map { it.modifiedAt }, snapshots.take(64).map { it.modifiedAt })
        assertTrue(snapshots.drop(64).all { it.isDeleted && it.songs.isEmpty() })
        assertEquals((65L..128L).map { 1_000L + it }, snapshots.drop(64).map { it.modifiedAt })
        assertEquals(current.size, playlists.visits)
    }

    private class CountingPlaylistList(private val source: List<LocalPlaylist>) : AbstractList<LocalPlaylist>() {
        var visits = 0
            private set
        override val size: Int get() = source.size
        override fun get(index: Int): LocalPlaylist = error("snapshot must traverse through an iterator")
        override fun iterator(): Iterator<LocalPlaylist> {
            val iterator = source.iterator()
            return object : Iterator<LocalPlaylist> {
                override fun hasNext(): Boolean = iterator.hasNext()
                override fun next(): LocalPlaylist {
                    visits += 1
                    return iterator.next()
                }
            }
        }
    }
}
