package moe.ouom.neriplayer.core.download.cleanup

import moe.ouom.neriplayer.data.model.download.DownloadedSong
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSongDeleteTokenOwnershipTest {
    private val visibility = DownloadedSongDeleteVisibility()

    @Test
    fun `only the newest active token owns a song deletion`() {
        val song = song(id = 1L, mediaUri = "content://downloads/1")
        val older = visibility.begin(listOf(song))
        assertTrue(visibility.owns(older, song))

        val newer = visibility.begin(listOf(song.copy(name = "Renamed")))
        assertFalse(visibility.owns(older, song))
        assertTrue(visibility.owns(newer, song))

        visibility.finish(newer)
        assertTrue(visibility.owns(older, song))

        visibility.finish(older)
        assertFalse(visibility.owns(older, song))
        assertFalse(visibility.hasActiveDeletions())
    }

    @Test
    fun `a token only owns songs whose trimmed identity it started`() {
        val first = song(id = 1L, mediaUri = " content://downloads/1 ")
        val second = song(id = 2L, filePath = "/music/NeriPlayer/2.flac")
        val token = visibility.begin(listOf(first))
        visibility.begin(listOf(second))

        assertTrue(visibility.owns(token, first.copy(mediaUri = "content://downloads/1")))
        assertFalse(visibility.owns(token, second))
    }

    private fun song(
        id: Long,
        mediaUri: String? = null,
        filePath: String = "/music/NeriPlayer/$id.flac"
    ) = DownloadedSong(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "Album",
        filePath = filePath,
        fileSize = 1_024L,
        downloadTime = 1_000L,
        mediaUri = mediaUri
    )
}
