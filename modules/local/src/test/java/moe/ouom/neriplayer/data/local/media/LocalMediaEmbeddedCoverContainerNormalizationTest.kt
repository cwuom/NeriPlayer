package moe.ouom.neriplayer.data.local.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import java.io.OutputStream

class LocalMediaEmbeddedCoverContainerNormalizationTest {
    private val source = byteArrayOf(1, 2, 3, 4)

    @Test
    fun `non mp4 containers keep the cover bytes with a normalized mime type`() {
        val flac = LocalMediaSupport.normalizeEmbeddedCoverForContainer(source, "IMAGE/JPG", "flac")
        val mp3 = LocalMediaSupport.normalizeEmbeddedCoverForContainer(source, null, "mp3")
        val ogg = LocalMediaSupport.normalizeEmbeddedCoverForContainer(source, "image/webp", "ogg")

        assertSame(source, flac?.first)
        assertEquals("image/jpeg", flac?.second)
        assertSame(source, mp3?.first)
        assertEquals("image/jpeg", mp3?.second)
        assertSame(source, ogg?.first)
        assertEquals("image/webp", ogg?.second)
    }

    @Test
    fun `mp4 containers keep jpeg and png covers as they are`() {
        val png = LocalMediaSupport.normalizeEmbeddedCoverForContainer(source, "image/png", " M4A ")
        val jpeg = LocalMediaSupport.normalizeEmbeddedCoverForContainer(source, "image/pjpeg", "mp4")

        assertSame(source, png?.first)
        assertEquals("image/png", png?.second)
        assertSame(source, jpeg?.first)
        assertEquals("image/jpeg", jpeg?.second)
    }

    @Test
    fun `mp4 containers re-encode other cover formats as jpeg`() {
        val encoded = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val bitmap = mock(Bitmap::class.java)
        doAnswer { invocation ->
            invocation.getArgument<OutputStream>(2).write(encoded)
            true
        }.`when`(bitmap).compress(any(), anyInt(), any())

        mockStatic(BitmapFactory::class.java).use { decoder ->
            decoder.`when`<Bitmap> { BitmapFactory.decodeByteArray(source, 0, source.size) }
                .thenReturn(bitmap)

            val result = LocalMediaSupport.normalizeEmbeddedCoverForContainer(source, "image/webp", "m4a")

            assertArrayEquals(encoded, result?.first)
            assertEquals("image/jpeg", result?.second)
        }
        verify(bitmap).compress(any(), eq(95), any())
        verify(bitmap).recycle()
    }

    @Test
    fun `mp4 covers that cannot be decoded are dropped`() {
        mockStatic(BitmapFactory::class.java).use { decoder ->
            assertNull(LocalMediaSupport.normalizeEmbeddedCoverForContainer(source, "image/gif", "m4a"))
            decoder.verify { BitmapFactory.decodeByteArray(source, 0, source.size) }
        }
    }
}
