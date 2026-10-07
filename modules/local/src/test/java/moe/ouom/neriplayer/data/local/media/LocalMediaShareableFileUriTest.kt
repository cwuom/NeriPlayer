package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import java.io.File

class LocalMediaShareableFileUriTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var source: File
    private lateinit var shareDir: File

    @Before
    fun setUp() {
        val cache = temporaryFolder.newFolder("cache")
        context = mock(Context::class.java).also { context ->
            doReturn("moe.ouom.neriplayer").`when`(context).packageName
            doReturn(cache).`when`(context).cacheDir
        }
        source = temporaryFolder.newFile("song.flac").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        shareDir = File(cache, SHARED_LOCAL_MEDIA_DIR)
    }

    @Test
    fun `files under a configured provider root are shared directly`() {
        val direct = mock(Uri::class.java)
        mockStatic(FileProvider::class.java).use { provider ->
            provider.`when`<Uri> { FileProvider.getUriForFile(context, AUTHORITY, source) }.thenReturn(direct)

            assertSame(direct, buildShareableFileUri(context, source))
        }
        assertFalse(shareDir.exists())
    }

    @Test
    fun `files outside the provider roots are shared through a staged copy`() {
        val staged = mock(Uri::class.java)
        val stagedFile = File(shareDir, LocalMediaSupport.shareableStageFileName(source))
        mockStatic(FileProvider::class.java).use { provider ->
            provider.`when`<Uri> { FileProvider.getUriForFile(context, AUTHORITY, source) }
                .thenThrow(IllegalArgumentException("Failed to find configured root"))
            provider.`when`<Uri> { FileProvider.getUriForFile(context, AUTHORITY, stagedFile) }.thenReturn(staged)

            assertSame(staged, buildShareableFileUri(context, source))
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), stagedFile.readBytes())
    }

    @Test
    fun `sharing fails when the source cannot be staged or the stage has no uri`() {
        mockStatic(FileProvider::class.java).use { provider ->
            provider.`when`<Uri> { FileProvider.getUriForFile(eq(context), eq(AUTHORITY), any(File::class.java)) }
                .thenThrow(IllegalArgumentException("Failed to find configured root"))

            assertNull(buildShareableFileUri(context, File(temporaryFolder.root, "missing.flac")))
            assertNull(buildShareableFileUri(context, source))
        }
        assertTrue(File(shareDir, LocalMediaSupport.shareableStageFileName(source)).isFile)
    }

    private companion object {
        const val AUTHORITY = "moe.ouom.neriplayer.fileprovider"
    }
}
