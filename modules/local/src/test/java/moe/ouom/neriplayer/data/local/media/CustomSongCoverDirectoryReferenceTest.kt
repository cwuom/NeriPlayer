package moe.ouom.neriplayer.data.local.media

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class CustomSongCoverDirectoryReferenceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `absolute paths are directories only when they point at one`() {
        val directory = temporaryFolder.newFolder("covers")
        val file = temporaryFolder.newFile("cover.jpg")

        assertTrue(CustomSongCoverStorage.isDirectoryReference(" ${directory.absolutePath} "))
        assertFalse(CustomSongCoverStorage.isDirectoryReference(file.absolutePath))
    }

    @Test
    fun `missing and blank references are not directories`() {
        assertFalse(CustomSongCoverStorage.isDirectoryReference(null))
        assertFalse(CustomSongCoverStorage.isDirectoryReference("   "))
    }

    @Test
    fun `file urls are resolved even when they cannot be parsed as android uris`() {
        val directory = temporaryFolder.newFolder("covers")

        assertTrue(CustomSongCoverStorage.isDirectoryReference(directory.toURI().toString()))
        assertFalse(CustomSongCoverStorage.isDirectoryReference("file:relative/covers"))
    }

    @Test
    fun `parsed file uris are resolved from their path`() {
        val directory = temporaryFolder.newFolder("covers")

        withParsedUri(uri(scheme = "FILE", path = directory.absolutePath)) {
            assertTrue(CustomSongCoverStorage.isDirectoryReference("file://${directory.absolutePath}"))
        }
        withParsedUri(uri(scheme = "file", path = null)) {
            assertFalse(CustomSongCoverStorage.isDirectoryReference("file://"))
        }
    }

    @Test
    fun `content uris never point at local directories`() {
        withParsedUri(uri(scheme = "content", path = "/external/images/media/1")) {
            assertFalse(CustomSongCoverStorage.isDirectoryReference("content://media/external/images/media/1"))
        }
    }

    @Test
    fun `unparseable references still resolve absolute paths`() {
        val directory = temporaryFolder.newFolder("covers")

        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(anyString()) }.thenThrow(IllegalArgumentException("bad uri"))

            assertTrue(CustomSongCoverStorage.isDirectoryReference(directory.absolutePath))
            assertFalse(CustomSongCoverStorage.isDirectoryReference("content://media/external/images/media/1"))
        }
    }

    private fun withParsedUri(uri: Uri, block: () -> Unit) {
        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(anyString()) }.thenReturn(uri)
            block()
        }
    }

    private fun uri(scheme: String?, path: String?): Uri {
        val uri = mock(Uri::class.java)
        doReturn(scheme).`when`(uri).scheme
        doReturn(path).`when`(uri).path
        return uri
    }
}
