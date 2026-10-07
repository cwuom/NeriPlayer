package moe.ouom.neriplayer.data.local.media.metadata

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

class LocalMediaMetadataTargetReplacementTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context: Context = mock(Context::class.java)

    @Test
    fun `direct targets are replaced inside newly created parents`() {
        val source = temporaryFolder.newFile("updated.flac").apply { writeText("tagged audio") }
        val target = File(temporaryFolder.root, "albums/new/song.flac")

        assertTrue(
            LocalMediaMetadataRecoveryStore.replaceTargetFromFile(
                context, target.absolutePath, source, lastModifiedMs = MODIFIED_AT
            )
        )

        assertEquals("tagged audio", target.readText())
        assertEquals(MODIFIED_AT, target.lastModified())
        assertEquals(listOf("song.flac"), target.parentFile!!.list()!!.toList())
    }

    @Test
    fun `direct replacement reports blocked parents, occupied targets and unusable sources`() {
        val source = temporaryFolder.newFile("updated.flac").apply { writeText("tagged audio") }
        val blocker = temporaryFolder.newFile("blocker").apply { writeText("plain file") }
        val album = temporaryFolder.newFolder("album")
        val occupied = File(album, "song.flac").apply { mkdir() }
        File(occupied, "keep").writeText("unrelated")

        assertFalse(replace(File(blocker, "nested/song.flac"), source))
        assertFalse(replace(occupied, source))
        assertFalse(replace(File(album, "other.flac"), File(temporaryFolder.root, "missing.flac")))
        assertFalse(replace(File(album, "other.flac"), temporaryFolder.newFile("empty.flac")))

        assertEquals("plain file", blocker.readText())
        assertEquals(listOf("song.flac"), album.list()!!.toList())
        assertEquals("unrelated", File(occupied, "keep").readText())
    }

    @Test
    fun `content targets fall back to truncating write mode`() {
        val source = temporaryFolder.newFile("updated.flac").apply { writeText("tagged audio") }
        val document = temporaryFolder.newFile("document.flac")
        val uri = contentUri()
        val resolver = mock(ContentResolver::class.java)
        val descriptor = mock(ParcelFileDescriptor::class.java)
        doReturn(resolver).`when`(context).contentResolver
        doThrow(FileNotFoundException("rwt unsupported")).`when`(resolver).openFileDescriptor(uri, "rwt")
        doReturn(descriptor).`when`(resolver).openFileDescriptor(uri, "wt")

        FileOutputStream(document).use { stream ->
            doReturn(stream.fd).`when`(descriptor).fileDescriptor
            withParsedUri(uri) {
                assertTrue(LocalMediaMetadataRecoveryStore.replaceTargetFromFile(context, CONTENT_REFERENCE, source))
            }
        }

        assertEquals("tagged audio", document.readText())
        verify(descriptor).close()
    }

    @Test
    fun `content targets without a writable descriptor are not replaced`() {
        val source = temporaryFolder.newFile("updated.flac").apply { writeText("tagged audio") }
        val uri = contentUri()
        val resolver = mock(ContentResolver::class.java)
        doReturn(resolver).`when`(context).contentResolver

        withParsedUri(uri) {
            assertFalse(LocalMediaMetadataRecoveryStore.replaceTargetFromFile(context, CONTENT_REFERENCE, source))
        }

        verify(resolver).openFileDescriptor(uri, "rwt")
        verify(resolver).openFileDescriptor(uri, "wt")
    }

    private fun replace(target: File, source: File): Boolean =
        LocalMediaMetadataRecoveryStore.replaceTargetFromFile(context, target.absolutePath, source)

    private fun contentUri(): Uri = mock(Uri::class.java).also { uri ->
        doReturn("content").`when`(uri).scheme
        doReturn("/external/audio/media/7").`when`(uri).path
    }

    private fun withParsedUri(uri: Uri, block: () -> Unit) {
        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(CONTENT_REFERENCE) }.thenReturn(uri)
            block()
        }
    }

    private companion object {
        const val CONTENT_REFERENCE = "content://media/external/audio/media/7"
        const val MODIFIED_AT = 1_700_000_000_000L
    }
}
