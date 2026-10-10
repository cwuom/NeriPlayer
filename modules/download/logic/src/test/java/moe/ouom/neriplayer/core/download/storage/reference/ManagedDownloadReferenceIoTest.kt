package moe.ouom.neriplayer.core.download.storage.reference

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo.AccessObservation
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo.AccessResult
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo.DeleteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class ManagedDownloadReferenceIoTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also {
        `when`(it.contentResolver).thenReturn(resolver)
    }
    private val documentUri = mock(Uri::class.java).also {
        `when`(it.scheme).thenReturn("content")
    }

    @Test
    fun `permission failures are recognised anywhere in the cause chain`() {
        assertTrue(ManagedDownloadReferenceIo.isPermissionDocumentFailure(SecurityException()))
        listOf(
            "Permission denied",
            "ACCESS DENIED by provider",
            "Operation not permitted",
            "write not permitted",
            "open failed: EACCES",
            "Security Exception thrown"
        ).forEach { message ->
            assertTrue(message, ManagedDownloadReferenceIo.isPermissionDocumentFailure(IOException(message)))
        }
        assertTrue(
            ManagedDownloadReferenceIo.isPermissionDocumentFailure(
                IllegalStateException("outer", IOException("permission denied"))
            )
        )
        assertFalse(ManagedDownloadReferenceIo.isPermissionDocumentFailure(IOException(null as String?)))
        assertFalse(ManagedDownloadReferenceIo.isPermissionDocumentFailure(IOException("disk full")))
    }

    @Test
    fun `local file references are inspected without the content resolver`() {
        val audio = temporaryFolder.newFile("track.flac").apply { writeBytes(ByteArray(5)) }
        val missing = File(temporaryFolder.root, "missing.flac")

        assertEquals(AccessObservation(AccessResult.Missing, null), ManagedDownloadReferenceIo.inspectWithSize(context, null))
        assertEquals(AccessObservation(AccessResult.Missing, null), ManagedDownloadReferenceIo.inspectWithSize(context, "  "))
        assertEquals(
            AccessObservation(AccessResult.Accessible, 5L),
            ManagedDownloadReferenceIo.inspectWithSize(context, audio.absolutePath)
        )
        assertEquals(
            AccessObservation(AccessResult.Accessible, 5L),
            ManagedDownloadReferenceIo.inspectWithSize(context, audio.toURI().toString())
        )
        assertEquals(AccessResult.Missing, ManagedDownloadReferenceIo.inspect(context, missing.absolutePath))
        verifyNoInteractions(resolver)
    }

    @Test
    fun `file references are deleted recursively and missing files are reported`() {
        val album = temporaryFolder.newFolder("album").apply { resolve("track.flac").writeText("pcm") }

        assertEquals(DeleteResult.Deleted, ManagedDownloadReferenceIo.deleteFileReference(album))
        assertFalse(album.exists())
        assertEquals(DeleteResult.Missing, ManagedDownloadReferenceIo.deleteFileReference(album.absolutePath))
        assertTrue(ManagedDownloadReferenceIo.isFileReferenceGone(album.absolutePath))
    }

    @Test
    fun `documents without a cursor fall back to the file descriptor`() {
        val descriptor = mock(ParcelFileDescriptor::class.java)
        `when`(resolver.openFileDescriptor(documentUri, "r")).thenReturn(null, descriptor)

        val withoutDescriptor = ManagedDownloadReferenceIo.inspect(context, documentUri)
        val withDescriptor = ManagedDownloadReferenceIo.inspect(context, documentUri)

        assertTrue(withoutDescriptor is AccessResult.ProviderFailure)
        assertTrue(
            (withoutDescriptor as AccessResult.ProviderFailure).error.message.orEmpty()
                .startsWith("provider returned null document cursor")
        )
        assertEquals(AccessResult.Accessible, withDescriptor)
        verify(resolver, times(4)).query(any(Uri::class.java), any(), any(), any(), any())
        verify(descriptor).close()
    }

    @Test
    fun `document cursors decide between missing and readable documents`() {
        val emptyCursor = mock(Cursor::class.java)
        val rowCursor = mock(Cursor::class.java).also { `when`(it.moveToFirst()).thenReturn(true) }
        val sized = mock(ParcelFileDescriptor::class.java).also { `when`(it.statSize).thenReturn(42L) }
        val unsized = mock(ParcelFileDescriptor::class.java).also { `when`(it.statSize).thenReturn(-1L) }
        `when`(resolver.query(any(Uri::class.java), any(), any(), any(), any()))
            .thenReturn(emptyCursor, rowCursor, rowCursor, rowCursor)
        `when`(resolver.openFileDescriptor(documentUri, "r")).thenReturn(null, sized, unsized)

        assertEquals(AccessResult.Missing, ManagedDownloadReferenceIo.inspect(context, documentUri))
        val nullDescriptor = ManagedDownloadReferenceIo.inspect(context, documentUri)
        assertEquals(AccessResult.Accessible, ManagedDownloadReferenceIo.inspect(context, documentUri))
        assertEquals(AccessResult.Accessible, ManagedDownloadReferenceIo.inspect(context, documentUri))

        assertEquals(
            "provider returned null file descriptor",
            (nullDescriptor as AccessResult.ProviderFailure).error.message
        )
        verify(emptyCursor).close()
        verify(sized).close()
        verify(unsized).close()
    }

    @Test
    fun `document open failures are classified by type and message`() {
        val rowCursor = mock(Cursor::class.java).also { `when`(it.moveToFirst()).thenReturn(true) }
        val providerError = IllegalStateException("binder died")
        `when`(resolver.query(any(Uri::class.java), any(), any(), any(), any())).thenReturn(rowCursor)
        `when`(resolver.openFileDescriptor(documentUri, "r"))
            .thenThrow(FileNotFoundException("No such file or directory"))
            .thenThrow(SecurityException())
            .thenThrow(IllegalStateException("open failed: EACCES (Permission denied)"))
            .thenThrow(providerError)

        assertEquals(AccessResult.Missing, ManagedDownloadReferenceIo.inspect(context, documentUri))
        assertEquals(AccessResult.PermissionLost, ManagedDownloadReferenceIo.inspect(context, documentUri))
        assertEquals(AccessResult.PermissionLost, ManagedDownloadReferenceIo.inspect(context, documentUri))
        assertEquals(AccessResult.ProviderFailure(providerError), ManagedDownloadReferenceIo.inspect(context, documentUri))
    }

    @Test
    fun `document query failures are classified by type and message`() {
        val providerError = IllegalStateException("provider crashed")
        `when`(resolver.query(any(Uri::class.java), any(), any(), any(), any()))
            .thenThrow(SecurityException())
            .thenAnswer { throw FileNotFoundException("document not found") }
            .thenThrow(IllegalStateException("permission denied"))
            .thenThrow(providerError)

        assertEquals(AccessResult.PermissionLost, ManagedDownloadReferenceIo.inspect(context, documentUri))
        assertEquals(AccessResult.Missing, ManagedDownloadReferenceIo.inspect(context, documentUri))
        assertEquals(AccessResult.PermissionLost, ManagedDownloadReferenceIo.inspect(context, documentUri))
        assertEquals(AccessResult.ProviderFailure(providerError), ManagedDownloadReferenceIo.inspect(context, documentUri))
    }

    @Test
    fun `directories need a directory mime type`() {
        val emptyCursor = mock(Cursor::class.java)
        val noMimeColumn = directoryCursor(mimeIndex = -1)
        val nullMime = directoryCursor(mimeIndex = 0).also { `when`(it.isNull(0)).thenReturn(true) }
        val audioMime = directoryCursor(mimeIndex = 0).also { `when`(it.getString(0)).thenReturn("audio/mpeg") }
        val directoryMime = directoryCursor(mimeIndex = 0).also {
            `when`(it.getString(0)).thenReturn(DocumentsContract.Document.MIME_TYPE_DIR)
        }
        `when`(resolver.query(any(Uri::class.java), any(), any(), any(), any()))
            .thenReturn(null, null, emptyCursor, noMimeColumn, nullMime, audioMime, directoryMime)

        val noCursor = ManagedDownloadReferenceIo.inspectDirectory(context, documentUri)
        assertEquals(AccessResult.Missing, ManagedDownloadReferenceIo.inspectDirectory(context, documentUri))
        val omittedMime = ManagedDownloadReferenceIo.inspectDirectory(context, documentUri)
        val unreadableMime = ManagedDownloadReferenceIo.inspectDirectory(context, documentUri)
        assertEquals(AccessResult.Missing, ManagedDownloadReferenceIo.inspectDirectory(context, documentUri))
        assertEquals(AccessResult.Accessible, ManagedDownloadReferenceIo.inspectDirectory(context, documentUri))

        assertTrue(
            (noCursor as AccessResult.ProviderFailure).error.message.orEmpty()
                .startsWith("provider returned null document cursor")
        )
        assertEquals("provider omitted document mime type", (omittedMime as AccessResult.ProviderFailure).error.message)
        assertEquals("provider omitted document mime type", (unreadableMime as AccessResult.ProviderFailure).error.message)
    }

    @Test
    fun `directory query failures are classified`() {
        val providerError = IllegalStateException("provider crashed")
        `when`(resolver.query(any(Uri::class.java), any(), any(), any(), any()))
            .thenThrow(SecurityException())
            .thenAnswer { throw FileNotFoundException("missing document") }
            .thenThrow(providerError)

        assertEquals(AccessResult.PermissionLost, ManagedDownloadReferenceIo.inspectDirectory(context, documentUri))
        assertEquals(AccessResult.Missing, ManagedDownloadReferenceIo.inspectDirectory(context, documentUri))
        assertEquals(AccessResult.ProviderFailure(providerError), ManagedDownloadReferenceIo.inspectDirectory(context, documentUri))
    }

    @Test
    fun `content references are gone only when they are missing`() {
        val existing = temporaryFolder.newFile("kept.flac")

        assertTrue(ManagedDownloadReferenceIo.isContentReferenceGone(context, uriNamed(File(temporaryFolder.root, "gone.flac"))))
        assertFalse(ManagedDownloadReferenceIo.isContentReferenceGone(context, uriNamed(existing)))
    }

    @Test
    fun `delete stops on missing, deleted or permission results`() {
        val existing = uriNamed(temporaryFolder.newFile("kept.flac"))
        `when`(resolver.delete(existing, null, null))
            .thenReturn(1)
            .thenThrow(SecurityException())
            .thenThrow(IllegalArgumentException("Document not found"))
            .thenThrow(IllegalStateException("open failed: EACCES"))

        assertEquals(DeleteResult.Missing, delete(uriNamed(File(temporaryFolder.root, "gone.flac"))))
        assertEquals(DeleteResult.Deleted, delete(existing))
        assertEquals(DeleteResult.PermissionLost, delete(existing))
        assertEquals(DeleteResult.Missing, delete(existing))
        assertEquals(DeleteResult.PermissionLost, delete(existing))
    }

    @Test
    fun `delete retries provider failures until attempts run out`() {
        val existing = uriNamed(temporaryFolder.newFile("kept.flac"))
        `when`(resolver.delete(existing, null, null)).thenThrow(IllegalStateException("provider busy"))

        val retried = delete(existing, maxAttempts = 2)
        val exhausted = delete(existing, maxAttempts = 0)

        assertEquals(
            "DocumentFile delete was not accepted",
            (retried as DeleteResult.ProviderFailure).error.message
        )
        assertEquals(
            "content reference delete attempts exhausted",
            (exhausted as DeleteResult.ProviderFailure).error.message
        )
        verify(resolver, times(2)).delete(existing, null, null)
    }

    private fun delete(uri: Uri, maxAttempts: Int = 3) = ManagedDownloadReferenceIo.deleteContentReference(
        context = context,
        uri = uri,
        maxAttempts = maxAttempts,
        retryDelayMs = 0L
    )

    private fun uriNamed(file: File): Uri = mock(Uri::class.java).also {
        `when`(it.scheme).thenReturn("content")
        `when`(it.toString()).thenReturn(file.absolutePath)
    }

    private fun directoryCursor(mimeIndex: Int): Cursor = mock(Cursor::class.java).also {
        `when`(it.moveToFirst()).thenReturn(true)
        `when`(it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)).thenReturn(mimeIndex)
    }
}
