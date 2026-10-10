package moe.ouom.neriplayer.data.config

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class LimitedTextReaderTest {
    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also { doReturn(resolver).`when`(it).contentResolver }
    private val uri = mock(Uri::class.java)

    @Test
    fun `text within the byte budget is decoded as utf8`() {
        val text = "歌词 lyrics\n".repeat(1_000)
        val bytes = text.toByteArray(Charsets.UTF_8)
        doReturn(ByteArrayInputStream(bytes)).`when`(resolver).openInputStream(uri)

        assertEquals(text, LimitedTextReader.readUtf8(context, uri, bytes.size.toLong()))
    }

    @Test
    fun `text beyond the byte budget is rejected`() {
        doReturn(ByteArrayInputStream(ByteArray(10_000))).`when`(resolver).openInputStream(uri)

        val error = assertThrows(IOException::class.java) { LimitedTextReader.readUtf8(context, uri, 9_999L) }
        assertEquals("Input file is too large", error.message)
    }

    @Test
    fun `unopenable inputs are reported as io failures`() {
        doReturn(null).`when`(resolver).openInputStream(uri)

        val error = assertThrows(IOException::class.java) { LimitedTextReader.readUtf8(context, uri, 1L) }
        assertEquals("Cannot open input", error.message)
    }

    @Test
    fun `non positive budgets are rejected before opening the input`() {
        assertThrows(IllegalArgumentException::class.java) { LimitedTextReader.readUtf8(context, uri, 0L) }
        verifyNoInteractions(resolver)
    }
}
