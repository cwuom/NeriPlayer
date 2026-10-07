package moe.ouom.neriplayer.data.local.audioimport

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.AdditionalMatchers.aryEq
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.isNull
import org.mockito.ArgumentMatchers.same
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class LocalAudioImportDocumentTimestampQueryTest {
    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java)
    private val uri = mock(Uri::class.java)

    @Before
    fun setUp() {
        `when`(context.contentResolver).thenReturn(resolver)
    }

    @Test
    fun `document last modified time is read from the provider row`() {
        val cursor = documentCursor(lastModified = 1_700_000_000_000L)
        stubQuery(cursor)

        val info = LocalAudioImportManager.queryExternalDocumentTimestamp(context, uri)

        assertEquals(ExternalProviderTimestampInfo(documentLastModifiedMs = 1_700_000_000_000L), info)
        verify(resolver).query(
            same(uri),
            aryEq(arrayOf("last_modified")),
            isNull(),
            isNull(),
            isNull()
        )
        verify(cursor).close()
    }

    @Test
    fun `missing null and out of range document times stay unknown`() {
        val unknown = ExternalProviderTimestampInfo()
        val missingColumn = documentCursor(lastModified = 1_700_000_000_000L, columnIndex = -1)
        val nullValue = documentCursor(lastModified = 1_700_000_000_000L, valueIsNull = true)
        val zeroValue = documentCursor(lastModified = 0L)
        val farFuture = documentCursor(lastModified = Long.MAX_VALUE)

        listOf(missingColumn, nullValue, zeroValue, farFuture).forEach { cursor ->
            stubQuery(cursor)

            assertEquals(unknown, LocalAudioImportManager.queryExternalDocumentTimestamp(context, uri))
            verify(cursor).close()
        }
    }

    @Test
    fun `empty rows missing cursors and provider failures give no timestamp info`() {
        val emptyCursor = mock(Cursor::class.java)
        stubQuery(emptyCursor)
        assertNull(LocalAudioImportManager.queryExternalDocumentTimestamp(context, uri))
        verify(emptyCursor).close()

        stubQuery(null)
        assertNull(LocalAudioImportManager.queryExternalDocumentTimestamp(context, uri))

        doThrow(SecurityException("grant revoked")).`when`(resolver)
            .query(any(), any(), any(), any(), any())
        assertNull(LocalAudioImportManager.queryExternalDocumentTimestamp(context, uri))
    }

    private fun stubQuery(cursor: Cursor?) {
        doReturn(cursor).`when`(resolver).query(any(), any(), any(), any(), any())
    }

    private fun documentCursor(
        lastModified: Long,
        columnIndex: Int = 4,
        valueIsNull: Boolean = false
    ): Cursor {
        val cursor = mock(Cursor::class.java)
        `when`(cursor.moveToFirst()).thenReturn(true)
        `when`(cursor.getColumnIndex(anyString())).thenReturn(-1)
        `when`(cursor.getColumnIndex("last_modified")).thenReturn(columnIndex)
        `when`(cursor.isNull(columnIndex)).thenReturn(valueIsNull)
        `when`(cursor.getLong(columnIndex)).thenReturn(lastModified)
        return cursor
    }
}
