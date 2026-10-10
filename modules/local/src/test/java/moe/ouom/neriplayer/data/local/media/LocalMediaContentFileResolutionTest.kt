package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.same
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File

class LocalMediaContentFileResolutionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java)
    private val uri = mock(Uri::class.java)

    @Before
    fun setUp() {
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(uri.scheme).thenReturn("content")
        `when`(uri.toString()).thenReturn("content://media/external/audio/media/7")
    }

    @Test
    fun `content rows resolve to the file path reported by the provider`() {
        val audio = tempFolder.newFile("Song.flac")
        val cursor = rowWithDataPath(audio.absolutePath)
        doReturn(cursor).`when`(resolver).query(any(), any(), any(), any(), any())

        assertEquals(audio, LocalMediaSupport.resolveLocalFile(context, uri))
        verify(cursor).close()
        verify(resolver, never()).openFileDescriptor(any(), anyString())
    }

    @Test
    fun `content without an existing row path or descriptor resolves to no file`() {
        val staleRow = rowWithDataPath(File(tempFolder.root, "moved/Song.flac").absolutePath)
        doReturn(staleRow).`when`(resolver).query(any(), any(), any(), any(), any())
        assertNull(LocalMediaSupport.resolveLocalFile(context, uri))

        doReturn(null).`when`(resolver).query(any(), any(), any(), any(), any())
        assertNull(LocalMediaSupport.resolveLocalFile(context, uri))

        verify(resolver, times(2)).openFileDescriptor(same(uri), eq("r"))
    }

    private fun rowWithDataPath(path: String): Cursor {
        val cursor = mock(Cursor::class.java)
        `when`(cursor.moveToFirst()).thenReturn(true)
        `when`(cursor.getColumnIndex(anyString())).thenReturn(-1)
        `when`(cursor.getColumnIndex("_data")).thenReturn(5)
        `when`(cursor.getString(5)).thenReturn(path)
        return cursor
    }
}
