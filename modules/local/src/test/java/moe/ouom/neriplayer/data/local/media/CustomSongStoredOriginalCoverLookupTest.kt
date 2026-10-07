package moe.ouom.neriplayer.data.local.media

import android.content.Context
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class CustomSongStoredOriginalCoverLookupTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val song = SongItem(
        id = 42L,
        name = "Song",
        artist = "Artist",
        album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null,
        mediaUri = "/music/song.mp3",
        localFilePath = "/music/song.mp3",
        channelId = "local",
        audioId = "42"
    )
    private val prefix = CustomSongCoverStorage.originalCoverFileName(song, "jpg").substringBeforeLast('.')

    @Test
    fun `the newest non empty backup named after the song is restored`() = runTest {
        val backups = backupDirectory()
        stored(backups, "$prefix.jpg", byteArrayOf(1, 2), modifiedAt = 1_000_000L)
        val newest = stored(backups, "$prefix.png", byteArrayOf(3, 4, 5), modifiedAt = 3_000_000L)
        stored(backups, "$prefix.webp", ByteArray(0), modifiedAt = 5_000_000L)
        stored(backups, "other.png", byteArrayOf(9), modifiedAt = 9_000_000L)
        File(backups, "$prefix.d").apply { mkdirs(); setLastModified(7_000_000L) }

        val resolved = CustomSongCoverStorage.persistOriginalCover(context(), song, backups.toURI().toString())

        assertEquals(newest.toURI().toString(), resolved)
    }

    @Test
    fun `backups without a usable song cover resolve to nothing`() = runTest {
        val backups = backupDirectory()
        stored(backups, "$prefix.webp", ByteArray(0), modifiedAt = 5_000_000L)
        stored(backups, "other.png", byteArrayOf(9), modifiedAt = 9_000_000L)
        File(backups, prefix).mkdirs()

        assertNull(CustomSongCoverStorage.persistOriginalCover(context(), song, backups.toURI().toString()))
    }

    private fun context(): Context = mock(Context::class.java).also {
        `when`(it.filesDir).thenReturn(tempFolder.root)
    }

    private fun backupDirectory(): File = File(tempFolder.root, "bak").apply { mkdirs() }

    private fun stored(directory: File, name: String, bytes: ByteArray, modifiedAt: Long): File =
        File(directory, name).apply {
            writeBytes(bytes)
            setLastModified(modifiedAt)
        }
}
