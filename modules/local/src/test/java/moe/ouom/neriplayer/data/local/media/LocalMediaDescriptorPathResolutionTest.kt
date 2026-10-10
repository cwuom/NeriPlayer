package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class LocalMediaDescriptorPathResolutionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also { doReturn(resolver).`when`(it).contentResolver }
    private val contentUri = uri("content", path = null)

    @Test
    fun `unsupported uris are never opened`() {
        assertNull(LocalMediaSupport.resolvePathFromDescriptor(context, uri("https", "/covers/front.jpg")))

        verifyNoInteractions(resolver)
    }

    @Test
    fun `existing file uris resolve directly without opening a descriptor`() {
        val audio = temporaryFolder.newFile("song.flac")

        assertEquals(audio.absolutePath, LocalMediaSupport.resolvePathFromDescriptor(context, uri("file", audio.absolutePath)))
        verifyNoInteractions(resolver)
    }

    @Test
    fun `descriptor links to existing files resolve to the linked path`() {
        val audio = temporaryFolder.newFile("song.flac")
        val descriptor = descriptor(fd = 42)

        val path = readLink(descriptor, "${audio.absolutePath} (deleted)")

        assertEquals(audio.absolutePath, path)
        verify(descriptor.first).close()
    }

    @Test
    fun `descriptor links that are not existing absolute paths are ignored`() {
        val missing = File(temporaryFolder.root, "missing.flac")

        assertNull(readLink(descriptor(fd = 7), "pipe:[12345]"))
        assertNull(readLink(descriptor(fd = 8), missing.absolutePath))
    }

    @Test
    fun `providers without a readable descriptor give no path`() {
        assertNull(LocalMediaSupport.resolvePathFromDescriptor(context, contentUri))

        doThrow(FileNotFoundException("gone")).`when`(resolver).openFileDescriptor(contentUri, "r")
        assertNull(LocalMediaSupport.resolvePathFromDescriptor(context, contentUri))
    }

    private fun readLink(descriptor: Pair<ParcelFileDescriptor, Int>, target: String): String? {
        val (fileDescriptor, fd) = descriptor
        doReturn(fileDescriptor).`when`(resolver).openFileDescriptor(contentUri, "r")
        return mockStatic(Os::class.java).use { os ->
            os.`when`<String> { Os.readlink("/proc/self/fd/$fd") }.thenReturn(target)
            LocalMediaSupport.resolvePathFromDescriptor(context, contentUri)
        }
    }

    private fun descriptor(fd: Int): Pair<ParcelFileDescriptor, Int> =
        mock(ParcelFileDescriptor::class.java).also { doReturn(fd).`when`(it).fd } to fd

    private fun uri(scheme: String, path: String?): Uri {
        val uri = mock(Uri::class.java)
        doReturn(scheme).`when`(uri).scheme
        doReturn(path).`when`(uri).path
        return uri
    }
}
