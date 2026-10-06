package moe.ouom.neriplayer.core.download.storage.backend

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.download.storage.StorageLookupResult
import moe.ouom.neriplayer.data.model.download.storage.StorageReference
import moe.ouom.neriplayer.data.model.download.storage.StorageStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class SafStorageBackendDocumentMetadataTest {
    private val uri = mock(Uri::class.java)
    private val reference = StorageReference.SafRef(uri)

    @Test
    fun `document metadata keeps only trustworthy sizes and timestamps`() {
        assertEquals(
            StorageStat(reference, "song.mp3", 5L, 7L, isDirectory = false),
            metadata("song.mp3", sizeBytes = 5L, lastModifiedMs = 7L).toStat(uri)
        )
        assertEquals(
            StorageStat(reference, "Covers", null, null, isDirectory = true),
            metadata("Covers", sizeBytes = 5L, lastModifiedMs = 0L, isDirectory = true).toStat(uri)
        )
        assertEquals(
            StorageStat(reference, "negative.mp3", null, null, isDirectory = false),
            metadata("negative.mp3", sizeBytes = -1L, lastModifiedMs = null).toStat(uri)
        )
        assertEquals(
            StorageStat(reference, "unknown.mp3", null, null, isDirectory = false),
            metadata("unknown.mp3", sizeBytes = null, lastModifiedMs = -3L).toStat(uri)
        )
    }

    @Test
    fun `stat reads the provider cursor and closes it`() = runTest {
        val cursor = documentCursor(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID to "primary:Music/song.mp3",
            DocumentsContract.Document.COLUMN_DISPLAY_NAME to "song.mp3",
            DocumentsContract.Document.COLUMN_MIME_TYPE to "audio/mpeg",
            DocumentsContract.Document.COLUMN_SIZE to 12L,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED to 34L,
            DocumentsContract.Document.COLUMN_FLAGS to null
        )
        val backend = SafStorageBackend(contextReturning(cursor), parentDocumentCache = SafParentDocumentCache())

        assertEquals(
            StorageLookupResult.Found(StorageStat(reference, "song.mp3", 12L, 34L, isDirectory = false)),
            backend.stat(reference)
        )
        verify(cursor).close()
        assertTrue(backend.stat(StorageReference.FileRef("song.mp3")) is StorageLookupResult.Unsupported)
    }

    @Test
    fun `stat treats a provider without a cursor as a provider failure`() = runTest {
        val context = mock(Context::class.java)
        `when`(context.contentResolver).thenReturn(mock(ContentResolver::class.java))

        val result = SafStorageBackend(context, parentDocumentCache = SafParentDocumentCache()).stat(reference)

        assertEquals(
            "provider returned null document cursor",
            (result as StorageLookupResult.ProviderFailure).error.message
        )
    }

    @Test
    fun `scope violations anywhere in the cause chain are out of scope`() = runTest {
        val failures = listOf(
            FileNotFoundException("Document primary:Other/song.mp3 is not a child of primary:Music"),
            IllegalArgumentException("Document primary:Other/song.mp3 is not a child of primary:Music"),
            IllegalStateException("Requested document is OUTSIDE THE TREE"),
            UnsupportedOperationException("document access out of scope"),
            IllegalStateException(null as String?, IllegalArgumentException("primary:Other is not a child of primary:Music"))
        )
        failures.forEach { failure ->
            assertEquals(
                failure.toString(),
                StorageLookupResult.OutOfScope,
                SafStorageBackend(contextThatThrows(failure), parentDocumentCache = SafParentDocumentCache())
                    .stat(reference)
            )
        }

        val unrelated = IllegalStateException(null as String?, IllegalStateException("provider crashed"))
        assertEquals(
            StorageLookupResult.ProviderFailure(unrelated),
            SafStorageBackend(contextThatThrows(unrelated), parentDocumentCache = SafParentDocumentCache())
                .stat(reference)
        )
    }

    @Test
    fun `parent document cache rejects non positive limits`() {
        assertThrows(IllegalArgumentException::class.java) { SafParentDocumentCache<String>(validateIntervalMs = 0L) }
        assertThrows(IllegalArgumentException::class.java) { SafParentDocumentCache<String>(maxEntries = 0) }
    }

    @Test
    fun `parent document cache reloads after expiry or clear`() {
        var now = 1_000L
        var loads = 0
        val cache = SafParentDocumentCache<String>(validateIntervalMs = 10L, nowMs = { now })
        val load = { "value-${++loads}" }

        assertEquals("value-1", cache.getOrLoad("parent", load) { true })
        now += 10L
        assertEquals("value-1", cache.getOrLoad("parent", load) { true })
        now += 1L
        assertEquals("value-2", cache.getOrLoad("parent", load) { true })
        cache.clear()
        assertEquals("value-3", cache.getOrLoad("parent", load) { true })
    }

    private fun metadata(
        displayName: String,
        sizeBytes: Long?,
        lastModifiedMs: Long?,
        isDirectory: Boolean = false
    ): SafStorageBackend.SafDocumentMetadata {
        return SafStorageBackend.SafDocumentMetadata(
            displayName = displayName,
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs,
            isDirectory = isDirectory,
            flags = 0L
        )
    }

    private fun documentCursor(vararg columns: Pair<String, Any?>): Cursor {
        val names = columns.map { it.first }
        val cursor = mock(Cursor::class.java)
        `when`(cursor.moveToFirst()).thenReturn(true)
        `when`(cursor.getColumnIndex(anyString())).thenAnswer { invocation ->
            names.indexOf(invocation.getArgument<String>(0))
        }
        `when`(cursor.isNull(anyInt())).thenAnswer { invocation ->
            columns[invocation.getArgument<Int>(0)].second == null
        }
        `when`(cursor.getString(anyInt())).thenAnswer { invocation ->
            columns[invocation.getArgument<Int>(0)].second as String
        }
        `when`(cursor.getLong(anyInt())).thenAnswer { invocation ->
            columns[invocation.getArgument<Int>(0)].second as Long
        }
        return cursor
    }

    private fun contextReturning(cursor: Cursor): Context {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(resolver.query(any(), any(), any(), any(), any())).thenReturn(cursor)
        return context
    }

    private fun contextThatThrows(error: Throwable): Context {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        doAnswer { throw error }.`when`(resolver).query(any(), any(), any(), any(), any())
        return context
    }
}
