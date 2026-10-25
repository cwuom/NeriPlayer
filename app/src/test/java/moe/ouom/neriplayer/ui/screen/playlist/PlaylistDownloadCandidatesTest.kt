package moe.ouom.neriplayer.ui.screen.playlist

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistDownloadCandidatesTest {
    @Test
    fun `local media references stay excluded after album edits`() {
        val localSongs = listOf(
            song(1).copy(mediaUri = "content://media/external/audio/media/1"),
            song(2).copy(mediaUri = "content://test.documents/document/track%3A2"),
            song(3).copy(mediaUri = "file:///storage/emulated/0/Music/track.flac"),
            song(4).copy(localFilePath = "/storage/emulated/0/Music/track.flac")
        )

        assertTrue(playlistDownloadCandidates(localSongs).isEmpty())
    }

    @Test
    fun `mixed selection keeps only remote songs in source order`() {
        val firstRemote = song(1)
        val localUri = song(2).copy(mediaUri = "content://test.documents/document/local")
        val secondRemote = song(3).copy(mediaUri = "https://example.com/audio.mp3")
        val localPath = song(4).copy(localFilePath = "/synthetic/local.flac")

        assertEquals(
            listOf(firstRemote, secondRemote),
            playlistDownloadCandidates(listOf(firstRemote, localUri, secondRemote, localPath))
        )
    }

    @Test
    fun `legacy local placeholder without media reference cannot download`() {
        val legacy = song(1).copy(album = "__local_files__", albumId = 0L)

        assertTrue(playlistDownloadCandidates(listOf(legacy)).isEmpty())
    }

    @Test
    fun `remote album named local files does not determine source`() {
        val remote = song(1).copy(album = "Local Files")

        assertEquals(listOf(remote), playlistDownloadCandidates(listOf(remote)))
    }

    @Test
    fun `empty selection has no download candidates`() {
        assertTrue(playlistDownloadCandidates(emptyList()).isEmpty())
    }

    private fun song(id: Long) = SongItem(
        id = id,
        name = "song-$id",
        artist = "artist",
        album = "user edited album",
        albumId = 42L,
        durationMs = 180_000L,
        coverUrl = null
    )
}
