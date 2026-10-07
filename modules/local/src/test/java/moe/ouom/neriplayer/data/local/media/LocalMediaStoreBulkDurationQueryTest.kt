package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.AdditionalMatchers.aryEq
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.ArgumentMatchers.same
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class LocalMediaStoreBulkDurationQueryTest {
    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java)
    private val collection = mock(Uri::class.java)

    @Before
    fun setUp() {
        `when`(context.contentResolver).thenReturn(resolver)
    }

    @Test
    fun `media store items are queried once per collection by id`() {
        val first = mediaItem("42", "/external/audio/media/42")
        val second = mediaItem("43", "/external/audio/media/43")
        val cursor = mock(Cursor::class.java)
        `when`(cursor.getColumnIndex("_id")).thenReturn(0)
        `when`(cursor.getColumnIndex("duration")).thenReturn(1)
        `when`(cursor.moveToNext()).thenReturn(true, true, true, false)
        `when`(cursor.getLong(1)).thenReturn(180_000L, 0L, 200_000L)
        `when`(cursor.getLong(0)).thenReturn(42L, 99L)
        doReturn(cursor).`when`(resolver).query(any(), any(), any(), any(), any())

        val durations = LocalMediaSupport.resolveMediaStoreDurationsFast(context, listOf(first, second))

        assertEquals(mapOf("content://media/external/audio/media/42" to 180_000L), durations)
        verify(resolver).query(
            same(collection),
            aryEq(arrayOf("_id", "duration")),
            eq("_id IN (?,?)"),
            aryEq(arrayOf("42", "43")),
            isNull()
        )
        verify(cursor).close()
    }

    @Test
    fun `items without a positive id or collection are never queried`() {
        val sources = listOf(
            mediaItem("abc", "/external/audio/media/abc"),
            mediaItem("0", "/external/audio/media/0"),
            mediaItem(null, null),
            mediaItem("44", "44"),
            mock(Uri::class.java).also { document ->
                `when`(document.scheme).thenReturn("content")
                `when`(document.authority).thenReturn("com.android.externalstorage.documents")
                `when`(document.lastPathSegment).thenReturn("45")
            }
        )

        assertEquals(emptyMap<String, Long>(), LocalMediaSupport.resolveMediaStoreDurationsFast(context, sources))
        verifyNoInteractions(resolver)
    }

    @Test
    fun `provider failures leave the durations unknown`() {
        doThrow(SecurityException("grant revoked")).`when`(resolver)
            .query(any(), any(), any(), any(), any())

        val durations = LocalMediaSupport.resolveMediaStoreDurationsFast(
            context,
            listOf(mediaItem("42", "/external/audio/media/42"))
        )

        assertEquals(emptyMap<String, Long>(), durations)
    }

    private fun mediaItem(lastSegment: String?, path: String?): Uri {
        val uri = mock(Uri::class.java)
        `when`(uri.scheme).thenReturn("content")
        `when`(uri.authority).thenReturn("media")
        `when`(uri.lastPathSegment).thenReturn(lastSegment)
        `when`(uri.path).thenReturn(path)
        `when`(uri.toString()).thenReturn("content://media$path")
        val builder = mock(Uri.Builder::class.java)
        `when`(uri.buildUpon()).thenReturn(builder)
        `when`(builder.path("/external/audio/media")).thenReturn(builder)
        `when`(builder.build()).thenReturn(collection)
        return uri
    }
}
