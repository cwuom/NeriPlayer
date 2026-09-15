package moe.ouom.neriplayer.core.download.storage.reference

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.download.storage.backend.FileStorageBackend
import moe.ouom.neriplayer.core.download.storage.backend.StorageTarget
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

    @Test
    fun `nested missing file provider exception is classified as missing`() {
        val nested = IllegalArgumentException(
            "Failed to determine if primary:Downloads/song.mp3.npmeta.json is child of " +
                "primary:Downloads: java.io.FileNotFoundException: Missing file for " +
                "primary:Downloads/song.mp3.npmeta.json"
        )
        val wrapped = RuntimeException("DocumentsProvider lookup failed", nested)

        assertTrue(ManagedDownloadReferenceIo.isMissingDocumentFailure(wrapped))
    }

    @Test
    fun `permission cause wins over nested missing file text`() {
        val nested = IllegalArgumentException(
            "Missing file for primary:Downloads/song.mp3",
            SecurityException("permission denied")
        )

        assertFalse(ManagedDownloadReferenceIo.isMissingDocumentFailure(nested))
    }

    @Test
    fun `file reference delete waits for an in-flight backend write`() = runBlocking {
        val root = Files.createTempDirectory("neriplayer-reference-io").toFile()
        try {
            val backend = FileStorageBackend(root)
            val writerEntered = CompletableDeferred<Unit>()
            val releaseWriter = CompletableDeferred<Unit>()
            val writer = async(Dispatchers.Default) {
                backend.writeRecoverable(StorageTarget.FileTarget("song.mp3")) { output ->
                    output.write("audio".toByteArray())
                    writerEntered.complete(Unit)
                    releaseWriter.await()
                }
            }
            withTimeout(5_000L) { writerEntered.await() }

            val deleteStarted = CompletableDeferred<Unit>()
            val delete = async(Dispatchers.Default) {
                deleteStarted.complete(Unit)
                ManagedDownloadReferenceIo.deleteFileReference(root.resolve("song.mp3"))
            }
            withTimeout(5_000L) { deleteStarted.await() }
            delay(100L)
            assertFalse(delete.isCompleted)

            releaseWriter.complete(Unit)
            withTimeout(5_000L) { writer.await() }
            assertEquals(
                ManagedDownloadReferenceIo.DeleteResult.Deleted,
                withTimeout(5_000L) { delete.await() }
            )
            assertFalse(root.resolve("song.mp3").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
