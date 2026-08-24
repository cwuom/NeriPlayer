package moe.ouom.neriplayer.core.download.storage.backend

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class SafStorageBackendWriteCapabilityTest {
    @Test
    fun `provider without rename can create a new target directly`() {
        assertEquals(
            SafWriteCommitMode.DirectCreate,
            chooseSafWriteCommitMode(
                canRename = false,
                targetExists = false
            )
        )
    }

    @Test
    fun `provider without rename cannot replace an existing target atomically`() {
        assertEquals(
            SafWriteCommitMode.Unsupported,
            chooseSafWriteCommitMode(
                canRename = false,
                targetExists = true
            )
        )
    }

    @Test
    fun `provider rename remains the preferred commit strategy`() {
        assertEquals(
            SafWriteCommitMode.AtomicRename,
            chooseSafWriteCommitMode(
                canRename = true,
                targetExists = false
            )
        )
    }

    @Test
    fun `provider without rename creates a new target with the requested name`() = runBlocking {
        val parentUri = mock(Uri::class.java)
        val temporaryUri = mock(Uri::class.java)
        val finalUri = mock(Uri::class.java)
        val childrenUri = mock(Uri::class.java)
        val operationUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val temporaryName = ".song.mp3.$operationUuid.pending"
        val resolver = mock(ContentResolver::class.java)
        val context = mock(Context::class.java)
        `when`(context.contentResolver).thenReturn(resolver)

        val parentCursor = documentCursor(
            documentId = "root",
            displayName = "root",
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
            sizeBytes = null,
            flags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong()
        )
        val temporaryCursor = documentCursor(
            documentId = "temporary",
            displayName = ".song.mp3.pending",
            mimeType = "audio/mpeg",
            sizeBytes = null,
            flags = 0L
        )
        val finalCursor = documentCursor(
            documentId = "final",
            displayName = "song.mp3",
            mimeType = "audio/mpeg",
            sizeBytes = 5L,
            flags = 0L
        )
        val emptyChildrenCursor = mock(Cursor::class.java)
        `when`(emptyChildrenCursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
            .thenReturn(0)
        `when`(emptyChildrenCursor.moveToNext()).thenReturn(false)
        val temporaryBytes = ByteArrayOutputStream()
        val finalBytes = ByteArrayOutputStream()
        doAnswer { invocation ->
            when (invocation.getArgument<Uri>(0)) {
                parentUri -> parentCursor
                temporaryUri -> temporaryCursor
                finalUri -> finalCursor
                else -> emptyChildrenCursor
            }
        }.`when`(resolver).query(any(), any(), any(), any(), any())
        `when`(resolver.openOutputStream(temporaryUri, "w")).thenReturn(temporaryBytes)
        `when`(resolver.openOutputStream(finalUri, "w")).thenReturn(finalBytes)
        `when`(resolver.openInputStream(temporaryUri))
            .thenAnswer { ByteArrayInputStream(temporaryBytes.toByteArray()) }
        doAnswer { 1 }.`when`(resolver).delete(any(), any(), any())

        mockStatic(UUID::class.java).use { uuidMock ->
            uuidMock.`when`<UUID> { UUID.randomUUID() }.thenReturn(operationUuid)
            mockStatic(DocumentsContract::class.java).use { documentsContract ->
            documentsContract.`when`<String> {
                DocumentsContract.getDocumentId(parentUri)
            }.thenReturn("root")
            documentsContract.`when`<Boolean> {
                DocumentsContract.isTreeUri(parentUri)
            }.thenReturn(true)
            documentsContract.`when`<Uri> {
            DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, "root")
            }.thenReturn(childrenUri)
            documentsContract.`when`<Boolean> {
                DocumentsContract.deleteDocument(resolver, temporaryUri)
            }.thenReturn(true)
            documentsContract.`when`<Boolean> {
                DocumentsContract.deleteDocument(resolver, finalUri)
            }.thenReturn(true)
            documentsContract.`when`<Uri?> {
                DocumentsContract.createDocument(
                    resolver,
                    parentUri,
                    "audio/mpeg",
                    temporaryName
                )
            }.thenReturn(temporaryUri)
            documentsContract.`when`<Uri?> {
                DocumentsContract.createDocument(
                    resolver,
                    parentUri,
                    "audio/mpeg",
                    "song.mp3"
                )
            }.thenReturn(finalUri)

            val result = SafStorageBackend(context, Dispatchers.Unconfined).writeRecoverable(
                target = StorageTarget.SafTarget(
                    parent = StorageReference.SafRef(parentUri),
                    displayName = "song.mp3",
                    mimeType = "audio/mpeg"
                )
            ) { output ->
                output.write("audio".toByteArray())
            }

            assertTrue("result=$result", result is StorageWriteResult.Written)
                assertEquals("audio", finalBytes.toString())
            }
        }
    }

    private fun documentCursor(
        documentId: String,
        displayName: String,
        mimeType: String,
        sizeBytes: Long?,
        flags: Long
    ): Cursor {
        val cursor = mock(Cursor::class.java)
        val columns = listOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )
        columns.forEachIndexed { index, column ->
            `when`(cursor.getColumnIndex(column)).thenReturn(index)
        }
        `when`(cursor.moveToFirst()).thenReturn(true)
        `when`(cursor.isNull(anyInt())).thenAnswer { invocation ->
            val index = invocation.getArgument<Int>(0)
            index == 3 && sizeBytes == null || index == 4
        }
        `when`(cursor.getString(0)).thenReturn(documentId)
        `when`(cursor.getString(1)).thenReturn(displayName)
        `when`(cursor.getString(2)).thenReturn(mimeType)
        `when`(cursor.getLong(3)).thenReturn(sizeBytes ?: 0L)
        `when`(cursor.getLong(4)).thenReturn(0L)
        `when`(cursor.getLong(5)).thenReturn(flags)
        return cursor
    }
}
