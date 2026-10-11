package moe.ouom.neriplayer.data.local.audioimport

import moe.ouom.neriplayer.data.local.media.source.CrashLogCleanup
import moe.ouom.neriplayer.data.local.media.source.LocalMediaCoverAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaDownloadAccess
import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.local.LocalAudioImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

class LocalAudioFolderScanMergeTest {
    @Before
    fun bindMediaHost() {
        val downloads = mock(LocalMediaDownloadAccess::class.java)
        doReturn(emptyList<String>()).`when`(downloads).candidateFileNameTemplates(null)
        LocalMediaHostAccess.bind(downloads, mock(LocalMediaCoverAccess::class.java), CrashLogCleanup { false })
    }

    @Test
    fun `missing media store results keep the document scan result`() {
        val saf = result(listOf(safSong(1, "Song.flac", 1_000L)))

        assertSame(saf, LocalAudioImportManager.mergeFolderScanResults(null, saf))
    }

    @Test
    fun `document songs merge into the unique media store song with the same file name and duration`() {
        val media = mediaSong(1, "Song.FLAC", 180_000L)
        val saf = safSong(2, " song.flac ", 180_000L, title = "Detailed Title")

        val merged = LocalAudioImportManager.mergeFolderScanResults(
            result(listOf(media), failed = 1, completed = false),
            result(listOf(saf), failed = 2)
        )

        assertEquals(1, merged.songs.size)
        assertEquals(media.mediaUri, merged.songs.single().mediaUri)
        assertEquals("Detailed Title", merged.songs.single().name)
        assertEquals(3, merged.failedCount)
        assertFalse(merged.completed)
        assertTrue(merged.metadataDeferred)
    }

    @Test
    fun `document songs without a usable or unique fallback key are kept separately`() {
        val media = listOf(
            mediaSong(1, "Twin.flac", 90_000L),
            mediaSong(2, "Twin.flac", 90_000L),
            mediaSong(3, "Zero.flac", 0L),
            mediaSong(4, "", 60_000L)
        )
        val saf = listOf(
            safSong(11, "Twin.flac", 90_000L),
            safSong(13, "Zero.flac", 0L),
            safSong(14, " ", 60_000L)
        )

        val merged = LocalAudioImportManager.mergeFolderScanResults(result(media), result(saf))

        assertEquals((media + saf).map(SongItem::mediaUri), merged.songs.map(SongItem::mediaUri))
    }

    private fun result(songs: List<SongItem>, failed: Int = 0, completed: Boolean = true) = LocalAudioImportResult(
        songs = songs,
        failedCount = failed,
        completed = completed,
        metadataDeferred = true
    )

    private fun mediaSong(id: Long, fileName: String, durationMs: Long) = song(
        id = id,
        fileName = fileName,
        durationMs = durationMs,
        mediaUri = "content://media/external/audio/media/$id",
        title = "Track $id"
    )

    private fun safSong(id: Long, fileName: String, durationMs: Long, title: String = "Track $id") = song(
        id = id,
        fileName = fileName,
        durationMs = durationMs,
        mediaUri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2F$id",
        title = title
    )

    private fun song(id: Long, fileName: String, durationMs: Long, mediaUri: String, title: String) = SongItem(
        id = id,
        name = title,
        artist = "Artist",
        album = "Album",
        albumId = 0L,
        durationMs = durationMs,
        coverUrl = null,
        mediaUri = mediaUri,
        localFileName = fileName,
        channelId = "local"
    )
}
