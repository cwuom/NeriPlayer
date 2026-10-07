package moe.ouom.neriplayer.data.local.audioimport

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.io.FileNotFoundException

class LocalAudioImportReadableContentProbeTest {
    private val resolver: ContentResolver = mock(ContentResolver::class.java)
    private val context: Context = mock(Context::class.java).also { context ->
        doReturn(resolver).`when`(context).contentResolver
    }
    private val audio: Uri = mock(Uri::class.java)

    @Test
    fun `non empty descriptors are readable and closed after probing`() {
        val descriptor = descriptor(length = 4_096L)
        doReturn(descriptor).`when`(resolver).openAssetFileDescriptor(audio, "r")

        assertTrue(LocalAudioImportManager.probeReadableContentReference(context, audio))
        verify(descriptor).close()
    }

    @Test
    fun `empty or missing descriptors are not readable`() {
        val empty = descriptor(length = 0L)
        doReturn(empty).`when`(resolver).openAssetFileDescriptor(audio, "r")
        assertFalse(LocalAudioImportManager.probeReadableContentReference(context, audio))
        verify(empty).close()

        doReturn(null).`when`(resolver).openAssetFileDescriptor(audio, "r")
        assertFalse(LocalAudioImportManager.probeReadableContentReference(context, audio))
    }

    @Test
    fun `missing documents and revoked grants read as unreadable`() {
        doThrow(FileNotFoundException("deleted")).`when`(resolver).openAssetFileDescriptor(audio, "r")
        assertFalse(LocalAudioImportManager.probeReadableContentReference(context, audio))

        doThrow(SecurityException("grant revoked")).`when`(resolver).openAssetFileDescriptor(audio, "r")
        assertFalse(LocalAudioImportManager.probeReadableContentReference(context, audio))
    }

    @Test
    fun `cancellation is propagated to the scan`() {
        val cancelled = CancellationException("scan cancelled")
        doThrow(cancelled).`when`(resolver).openAssetFileDescriptor(audio, "r")

        val thrown = assertThrows(CancellationException::class.java) {
            LocalAudioImportManager.probeReadableContentReference(context, audio)
        }
        assertSame(cancelled, thrown)
    }

    private fun descriptor(length: Long): AssetFileDescriptor = mock(AssetFileDescriptor::class.java).also {
        doReturn(length).`when`(it).length
    }
}
