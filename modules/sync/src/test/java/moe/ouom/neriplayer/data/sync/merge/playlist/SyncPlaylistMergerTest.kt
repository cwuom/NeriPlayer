package moe.ouom.neriplayer.data.sync.merge.playlist

import java.util.LinkedList
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.merge.engine.TestSyncMergeHost
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlaylistMergerTest {
    private val merger = SyncPlaylistMerger(TestSyncMergeHost())

    @Test
    fun `remote replacement counts unique identities rather than duplicate rows`() {
        val local = playlist(50, listOf(song(1), song(1), song(2)))
        val remote = playlist(200, listOf(song(2), song(3), song(3)))

        val result = merger.mergePlaylist(local, remote, 100, emptyList())

        assertEquals(listOf(2L, 3L), result.playlist.songs.map { it.id })
        assertEquals(1, result.songsAdded)
        assertEquals(1, result.songsRemoved)
    }

    @Test
    fun `concurrent duplicate identities are counted after explicit deletion`() {
        val local = playlist(200, listOf(song(1), song(1), song(2)))
        val remote = playlist(200, listOf(song(2), song(3), song(3)))
        val deletion = SyncPlaylistSongDeletion(playlistId = 7, songId = 1, album = "netease", deletedAt = 300)

        val result = merger.mergePlaylist(local, remote, 100, listOf(deletion))
        val next = merger.mergePlaylist(result.playlist, result.playlist, 100, listOf(deletion))

        assertEquals(listOf(2L, 3L), result.playlist.songs.map { it.id })
        assertEquals(1, result.songsAdded)
        assertEquals(1, result.songsRemoved)
        assertEquals(0, next.songsAdded)
        assertEquals(0, next.songsRemoved)
    }

    private fun playlist(modifiedAt: Long, songs: List<SyncSong>) =
        SyncPlaylist(id = 7, name = "playlist", songs = LinkedList(songs), modifiedAt = modifiedAt, songOrderVersion = 1)

    private fun song(id: Long) = SyncSong(id = id, name = "song $id", album = "netease", addedAt = 10)
}
