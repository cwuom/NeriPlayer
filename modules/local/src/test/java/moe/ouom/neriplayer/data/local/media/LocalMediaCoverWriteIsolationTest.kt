package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalMediaCoverWriteIsolationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `original cover backup never copies another song's readable album thumbnail`() =
        runBlocking(Dispatchers.IO) {
            val context = mock(Context::class.java)
            val resolver = mock(ContentResolver::class.java)
            val uri = mock(Uri::class.java)
            val reference = "content://media/external/audio/albumart/17"
            doReturn("content").`when`(uri).scheme
            doReturn("album-cover.jpg").`when`(uri).lastPathSegment
            doReturn(resolver).`when`(context).contentResolver
            doReturn(tempFolder.root).`when`(context).filesDir
            doReturn("image/jpeg").`when`(resolver).getType(uri)
            doReturn(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
                .`when`(resolver).openInputStream(uri)
            val song = SongItem(
                id = 2L,
                name = "B",
                artist = "Artist",
                album = "Shared Album",
                albumId = 0L,
                durationMs = 120_000L,
                coverUrl = reference,
                mediaUri = "/music/B.mp3",
                localFilePath = "/music/B.mp3",
                channelId = "local"
            )
            mockStatic(Uri::class.java).use { uris ->
                uris.`when`<Uri> { Uri.parse(reference) }.thenReturn(uri)

                assertNull(CustomSongCoverStorage.persistOriginalCover(context, song, reference))
                assertFalse(File(tempFolder.root, "bak").exists())
            }
        }

    @Test
    fun `editable cover input never reads a readable shared thumbnail for audio embedding`() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        val reference = "content://media/external_primary/audio/albumart/17"
        doReturn("content").`when`(uri).scheme
        doReturn(resolver).`when`(context).contentResolver
        doReturn(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            .`when`(resolver).openInputStream(uri)
        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(reference) }.thenReturn(uri)

            assertNull(LocalMediaSupport.readEditableCoverBytes(context, reference))
        }
    }

    @Test
    fun `editable cover input keeps a manually selected image provider reference`() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        val reference = "content://media/external/images/media/17"
        doReturn("content").`when`(uri).scheme
        doReturn(resolver).`when`(context).contentResolver
        doReturn(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            .`when`(resolver).openInputStream(uri)
        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(reference) }.thenReturn(uri)

            assertArrayEquals(byteArrayOf(1, 2, 3), LocalMediaSupport.readEditableCoverBytes(context, reference))
        }
    }
}
