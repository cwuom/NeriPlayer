package moe.ouom.neriplayer.data.local.media.metadata

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import java.io.ByteArrayInputStream
import java.io.File

class LocalMediaCoverBytesReadbackTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val resolver: ContentResolver = mock(ContentResolver::class.java)
    private val context: Context = mock(Context::class.java).also { context ->
        doReturn(resolver).`when`(context).contentResolver
    }
    private val cover: Uri = mock(Uri::class.java)

    @Test
    fun `file and content references are read as raw bytes`() {
        val file = temporaryFolder.newFile("cover.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        doReturn(ByteArrayInputStream(byteArrayOf(4, 5))).`when`(resolver).openInputStream(cover)

        withParsedCover {
            assertArrayEquals(byteArrayOf(1, 2, 3), LocalMediaSupport.readBytesContent(context, file.absolutePath))
            assertArrayEquals(byteArrayOf(4, 5), LocalMediaSupport.readBytesContent(context, COVER_REFERENCE))
        }
    }

    @Test
    fun `missing files and absent streams read as null`() {
        doReturn(null).`when`(resolver).openInputStream(cover)

        withParsedCover {
            assertNull(LocalMediaSupport.readBytesContent(context, missingCoverPath()))
            assertNull(LocalMediaSupport.readBytesContent(context, COVER_REFERENCE))
        }
    }

    @Test
    fun `permission failures are propagated to the caller`() {
        val denial = SecurityException("grant revoked")
        doThrow(denial).`when`(resolver).openInputStream(cover)

        withParsedCover {
            val thrown = assertThrows(SecurityException::class.java) {
                LocalMediaSupport.readBytesContent(context, COVER_REFERENCE)
            }
            assertSame(denial, thrown)
        }
    }

    @Test
    fun `readback waits one backoff step when the first read is stale`() {
        doReturn(ByteArrayInputStream(byteArrayOf(9)), ByteArrayInputStream(byteArrayOf(4, 5)))
            .`when`(resolver).openInputStream(cover)

        withParsedCover {
            mockStatic(SystemClock::class.java).use { clock ->
                assertTrue(
                    LocalMediaSupport.readBytesContentMatchesWithRetry(context, COVER_REFERENCE, byteArrayOf(4, 5))
                )
                clock.verify { SystemClock.sleep(8L) }
                clock.verifyNoMoreInteractions()
            }
        }
    }

    @Test
    fun `readback gives up after every attempt misses without sleeping after the last one`() {
        mockStatic(SystemClock::class.java).use { clock ->
            assertFalse(
                LocalMediaSupport.readBytesContentMatchesWithRetry(context, missingCoverPath(), byteArrayOf(4, 5))
            )
            clock.verify { SystemClock.sleep(8L) }
            clock.verify { SystemClock.sleep(24L) }
            clock.verifyNoMoreInteractions()
        }
    }

    private fun missingCoverPath(): String = File(temporaryFolder.root, "missing.jpg").absolutePath

    private fun withParsedCover(block: () -> Unit) {
        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(COVER_REFERENCE) }.thenReturn(cover)
            block()
        }
    }

    private companion object {
        const val COVER_REFERENCE =
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fcover.jpg"
    }
}
