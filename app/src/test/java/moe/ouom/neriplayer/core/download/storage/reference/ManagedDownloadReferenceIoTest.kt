package moe.ouom.neriplayer.core.download.storage.reference

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

class ManagedDownloadReferenceIoTest {
    @Test
    fun `null document cursor is retried before opening the document`() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val cursor = mock(Cursor::class.java)
        val descriptor = mock(ParcelFileDescriptor::class.java)
        val uri = mock(Uri::class.java)
        val reference = "content://provider/document/song"
        var queryCount = 0

        `when`(context.contentResolver).thenReturn(resolver)
        `when`(uri.toString()).thenReturn(reference)
        `when`(cursor.moveToFirst()).thenReturn(true)
        doAnswer {
            queryCount += 1
            if (queryCount == 1) null else cursor
        }.`when`(resolver).query(any(), any(), any(), any(), any())
        `when`(resolver.openFileDescriptor(any(), any())).thenReturn(descriptor)

        assertEquals(
            ManagedDownloadReferenceIo.AccessResult.Accessible,
            ManagedDownloadReferenceIo.inspect(context, uri)
        )
        assertEquals(2, queryCount)
    }

    @Test
    fun `readable descriptor is accepted when provider keeps returning null cursors`() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val descriptor = mock(ParcelFileDescriptor::class.java)
        val uri = mock(Uri::class.java)
        val reference = "content://provider/document/song"
        var queryCount = 0

        `when`(context.contentResolver).thenReturn(resolver)
        `when`(uri.toString()).thenReturn(reference)
        doAnswer {
            queryCount += 1
            null
        }.`when`(resolver).query(any(), any(), any(), any(), any())
        `when`(resolver.openFileDescriptor(any(), any())).thenReturn(descriptor)

        assertEquals(
            ManagedDownloadReferenceIo.AccessResult.Accessible,
            ManagedDownloadReferenceIo.inspect(context, uri)
        )
        assertEquals(2, queryCount)
    }

    @Test
    fun `persistent null cursor without a readable descriptor remains provider failure`() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        val reference = "content://provider/document/song"
        var queryCount = 0

        `when`(context.contentResolver).thenReturn(resolver)
        `when`(uri.toString()).thenReturn(reference)
        doAnswer {
            queryCount += 1
            null
        }.`when`(resolver).query(any(), any(), any(), any(), any())

        val result = ManagedDownloadReferenceIo.inspect(context, uri)

        assertTrue(result is ManagedDownloadReferenceIo.AccessResult.ProviderFailure)
        assertFalse(
            ManagedDownloadReferenceIo.isMissingDocumentFailure(
                (result as ManagedDownloadReferenceIo.AccessResult.ProviderFailure).error
            )
        )
        assertEquals(2, queryCount)
    }

    @Test
    fun `directory inspection keeps persistent null cursor as provider failure`() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val uri = mock(Uri::class.java)
        val reference = "content://provider/tree/root"
        var queryCount = 0

        `when`(context.contentResolver).thenReturn(resolver)
        `when`(uri.toString()).thenReturn(reference)
        doAnswer {
            queryCount += 1
            null
        }.`when`(resolver).query(any(), any(), any(), any(), any())

        val result = ManagedDownloadReferenceIo.inspectDirectory(context, uri)

        assertTrue(result is ManagedDownloadReferenceIo.AccessResult.ProviderFailure)
        assertEquals(2, queryCount)
    }
}
