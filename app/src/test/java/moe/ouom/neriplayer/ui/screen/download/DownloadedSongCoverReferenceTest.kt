package moe.ouom.neriplayer.ui.screen.download

import moe.ouom.neriplayer.data.model.download.DownloadedSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadedSongCoverReferenceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `shared artwork in every stored field falls back to the default download cover`() {
        assertNull(resolveDownloadedSongCoverReference(song.copy(
            customCoverUrl = "content://media/external/audio/albumart/42",
            coverPath = "content://media/external_primary/audio/albumart/42",
            coverUrl = "content://media/0123-4567/audio/albumart/42"
        )))
    }

    @Test
    fun `shared custom and downloaded artwork cannot hide the song's own file URI`() {
        assertEquals("file:/music/B.jpg", resolveDownloadedSongCoverReference(song.copy(
            customCoverUrl = "content://media/external/audio/albumart/42",
            coverPath = "content://media/external_primary/audio/albumart/42",
            coverUrl = "file:/music/B.jpg"
        )))
    }

    @Test
    fun `an existing downloaded cover file remains available after rejecting shared artwork`() {
        val cover = tempFolder.newFile("B.jpg")
        assertEquals(cover.toURI().toString(), resolveDownloadedSongCoverReference(song.copy(
            customCoverUrl = "content://media/external/audio/albumart/42",
            coverPath = cover.absolutePath
        )))
    }

    @Test
    fun `manual image references and remote source covers remain available`() {
        val selected = "content://media/external/images/media/42"
        assertEquals(selected, resolveDownloadedSongCoverReference(song.copy(customCoverUrl = selected)))
        val remote = "https://example.com/B.jpg"
        assertEquals(remote, resolveDownloadedSongCoverReference(song.copy(coverUrl = remote)))
    }

    @Test
    fun `blank references are skipped in priority order`() {
        assertNull(resolveDownloadedSongCoverReference(song))
        assertEquals("covers/B.jpg", resolveDownloadedSongCoverReference(song.copy(
            customCoverUrl = "  ",
            coverPath = "covers/B.jpg",
            coverUrl = "https://example.com/B.jpg"
        )))
        assertEquals("https://example.com/B.jpg", resolveDownloadedSongCoverReference(song.copy(
            customCoverUrl = "",
            coverPath = " ",
            coverUrl = "https://example.com/B.jpg"
        )))
    }

    @Test
    fun `a missing absolute cover file falls back to the remote cover`() {
        val missing = tempFolder.root.resolve("missing.jpg").absolutePath
        val remote = "https://example.com/B.jpg"
        assertEquals(remote, resolveDownloadedSongCoverReference(song.copy(coverPath = missing, coverUrl = remote)))
        assertNull(resolveDownloadedSongCoverReference(song.copy(coverPath = missing)))
    }

    private val song = DownloadedSong(
        id = 2L,
        name = "B",
        artist = "Artist",
        album = "Shared Album",
        filePath = "/music/B.mp3",
        fileSize = 1024L,
        downloadTime = 0L
    )
}
