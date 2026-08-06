package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.content.ContentResolver
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class CustomSongCoverStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `remote original cover references are retained without copying`() = runBlocking {
        val reference = "HTTPS://example.com/cover.jpg"

        val persisted = CustomSongCoverStorage.persistOriginalCover(
            context = mock(Context::class.java),
            song = testSong(),
            reference = reference
        )

        assertEquals(reference, persisted)
    }

    @Test
    fun `local original cover is copied to a stable private file`() = runBlocking {
        val source = tempFolder.newFile("source-cover.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        `when`(context.filesDir).thenReturn(tempFolder.root)
        `when`(context.contentResolver).thenReturn(resolver)

        val first = CustomSongCoverStorage.persistOriginalCover(
            context = context,
            song = testSong(),
            reference = source.absolutePath
        )
        val storedFiles = File(tempFolder.root, "original_song_covers").listFiles()

        assertNotNull(first)
        assertTrue(first?.startsWith("file:") == true)
        assertEquals(1, storedFiles?.size)
        assertEquals(
            CustomSongCoverStorage.originalCoverFileName(testSong(), "png"),
            storedFiles?.single()?.name
        )
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), storedFiles?.single()?.readBytes()?.toList())

        val second = CustomSongCoverStorage.persistOriginalCover(
            context = context,
            song = testSong(),
            reference = first
        )
        assertEquals(first, second)
    }

    private fun testSong(): SongItem {
        return SongItem(
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
    }
}
