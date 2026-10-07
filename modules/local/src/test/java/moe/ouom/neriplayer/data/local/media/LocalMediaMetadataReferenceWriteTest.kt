package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class LocalMediaMetadataReferenceWriteTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val resolver: ContentResolver = mock(ContentResolver::class.java)
    private val context: Context = mock(Context::class.java).also { context ->
        doReturn(resolver).`when`(context).contentResolver
    }
    private val sidecarUri: Uri = mock(Uri::class.java)

    @Test
    fun `local metadata sidecars are replaced atomically on disk`() {
        val audio = temporaryFolder.newFile("song.flac")
        val sidecar = File(temporaryFolder.root, "song.npmeta.json").apply { writeText("""{"title":"old"}""") }

        assertTrue(LocalMediaSupport.writeLocalMetadataReference(context, sidecar.absolutePath, audio, METADATA))

        assertEquals(METADATA, sidecar.readText())
        assertEquals(listOf("song.flac", "song.npmeta.json"), temporaryFolder.root.list()!!.sorted())
        verifyNoInteractions(resolver)
    }

    @Test
    fun `document metadata sidecars are written through the provider and read back`() {
        val audio = temporaryFolder.newFile("song.flac")
        val written = mutableListOf<ByteArrayOutputStream>()
        doAnswer { ByteArrayOutputStream().also(written::add) }.`when`(resolver).openOutputStream(sidecarUri, "wt")
        doAnswer { ByteArrayInputStream(written.last().toByteArray()) }.`when`(resolver).openInputStream(sidecarUri)

        withParsedSidecar {
            assertTrue(LocalMediaSupport.writeLocalMetadataReference(context, SIDECAR_REFERENCE, null, METADATA))
            assertTrue(LocalMediaSupport.writeLocalMetadataReference(context, SIDECAR_REFERENCE, audio, METADATA))
        }

        assertEquals(listOf(METADATA, METADATA), written.map { String(it.toByteArray(), Charsets.UTF_8) })
        assertEquals(0L, audio.length())
    }

    @Test
    fun `providers without an output stream fail after trying both write modes`() {
        doReturn(null).`when`(resolver).openOutputStream(sidecarUri, "wt")
        doReturn(null).`when`(resolver).openOutputStream(sidecarUri, "w")

        withParsedSidecar {
            mockStatic(SystemClock::class.java).use { clock ->
                assertFalse(LocalMediaSupport.writeLocalMetadataReference(context, SIDECAR_REFERENCE, null, METADATA))
                clock.verify { SystemClock.sleep(8L) }
                clock.verifyNoMoreInteractions()
            }
        }

        verify(resolver).openOutputStream(sidecarUri, "wt")
        verify(resolver).openOutputStream(sidecarUri, "w")
    }

    private fun withParsedSidecar(block: () -> Unit) {
        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(SIDECAR_REFERENCE) }.thenReturn(sidecarUri)
            block()
        }
    }

    private companion object {
        const val METADATA = """{"title":"Night Drive","artist":"Neri Band"}"""
        const val SIDECAR_REFERENCE =
            "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fsong.npmeta.json"
    }
}
