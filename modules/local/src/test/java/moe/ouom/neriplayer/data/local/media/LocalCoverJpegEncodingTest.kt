package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class LocalCoverJpegEncodingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `embedded cover cache keeps the first jpeg within the cache budget`() {
        val qualities = mutableListOf<Int>()
        val bitmap = encodingBitmap { quality, output ->
            qualities += quality
            output.write(if (quality == 95) ByteArray(MAX_EMBEDDED_COVER_CACHE_BYTES + 1) else byteArrayOf(quality.toByte()))
            true
        }

        assertArrayEquals(byteArrayOf(90), LocalMediaSupport.encodeEmbeddedCoverForCache(bitmap))
        assertEquals(listOf(95, 90), qualities)
    }

    @Test
    fun `embedded cover cache gives up when no quality produces bytes`() {
        val qualities = mutableListOf<Int>()
        val bitmap = encodingBitmap { quality, _ ->
            qualities += quality
            quality % 2 == 0
        }

        assertNull(LocalMediaSupport.encodeEmbeddedCoverForCache(bitmap))
        assertEquals(EDITABLE_COVER_JPEG_QUALITIES.toList(), qualities)
    }

    @Test
    fun `embedded cover cache encoding failures propagate`() {
        val bitmap = mock(Bitmap::class.java)
        doThrow(IllegalStateException("recycled bitmap")).`when`(bitmap).compress(any(), anyInt(), any())

        assertThrows(IllegalStateException::class.java) {
            LocalMediaSupport.encodeEmbeddedCoverForCache(bitmap)
        }
    }

    @Test
    fun `editable cover jpeg uses the first successful quality and recycles the bitmap`() {
        val source = byteArrayOf(1, 2, 3)
        val bitmap = encodingBitmap { quality, output ->
            if (quality == 95) {
                false
            } else {
                output.write(byteArrayOf(9, quality.toByte()))
                true
            }
        }

        val encoded = decoding(source, bitmap) { LocalMediaSupport.encodeEditableCoverAsJpeg(source) }

        assertArrayEquals(byteArrayOf(9, 90), encoded)
        verify(bitmap).recycle()
    }

    @Test
    fun `editable cover jpeg is absent when the image cannot be decoded`() {
        assertNull(LocalMediaSupport.encodeEditableCoverAsJpeg(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `editable cover jpeg recycles the bitmap when every quality fails`() {
        val source = byteArrayOf(4, 5)
        val bitmap = encodingBitmap { _, _ -> true }

        assertNull(decoding(source, bitmap) { LocalMediaSupport.encodeEditableCoverAsJpeg(source) })
        verify(bitmap).recycle()
    }

    @Test
    fun `editable cover jpeg recycles the bitmap when encoding throws`() {
        val source = byteArrayOf(6)
        val bitmap = mock(Bitmap::class.java)
        doThrow(IllegalStateException("encoder crashed")).`when`(bitmap).compress(any(), anyInt(), any())

        assertThrows(IllegalStateException::class.java) {
            decoding(source, bitmap) { LocalMediaSupport.encodeEditableCoverAsJpeg(source) }
        }
        verify(bitmap).recycle()
    }

    @Test
    fun `missing local cover files produce no editable picture`() {
        val missing = File(temporaryFolder.root, "missing.jpg")

        assertNull(LocalMediaSupport.createEditableCoverPicture(mock(Context::class.java), missing.absolutePath, "mp3"))
    }

    @Test
    fun `existing lyric targets are never overwritten`() {
        val (context, resolver) = contextWithResolver()
        val target = temporaryFolder.newFile("Song.lrc").apply { writeText("keep") }

        LocalMediaSupport.copyLyricReference(context, "content://provider/document/lyric", target)

        assertEquals("keep", target.readText())
        verifyNoInteractions(resolver)
    }

    @Test
    fun `lyric sidecars are copied from the provider stream`() {
        val (context, resolver) = contextWithResolver()
        val target = File(temporaryFolder.root, "Lyrics/Song.lrc")

        withParsedUri("content://provider/document/lyric") { lyricUri ->
            doReturn(ByteArrayInputStream("[00:01.00]line".toByteArray())).`when`(resolver).openInputStream(lyricUri)

            LocalMediaSupport.copyLyricReference(context, "content://provider/document/lyric", target)
        }

        assertEquals("[00:01.00]line", target.readText())
    }

    @Test
    fun `unreadable lyric sidecars leave no partial target`() {
        val (context, resolver) = contextWithResolver()
        val target = File(temporaryFolder.root, "Song.lrc")

        withParsedUri("content://provider/document/lyric") { lyricUri ->
            doReturn(null).`when`(resolver).openInputStream(lyricUri)

            LocalMediaSupport.copyLyricReference(context, "content://provider/document/lyric", target)
        }

        assertFalse(target.exists())
    }

    @Test
    fun `nearby lyric content is absent without a reference`() {
        assertNull(LocalMediaSupport.readNearbyLyricContent(mock(Context::class.java), null, "translated lyrics"))
    }

    private fun encodingBitmap(compress: (quality: Int, output: OutputStream) -> Boolean): Bitmap {
        val bitmap = mock(Bitmap::class.java)
        doAnswer { invocation ->
            compress(invocation.getArgument(1), invocation.getArgument(2))
        }.`when`(bitmap).compress(any(), anyInt(), any())
        return bitmap
    }

    private fun <T> decoding(source: ByteArray, bitmap: Bitmap, block: () -> T): T {
        return mockStatic(BitmapFactory::class.java).use { decoder ->
            decoder.`when`<Bitmap> { BitmapFactory.decodeByteArray(source, 0, source.size) }.thenReturn(bitmap)
            block()
        }
    }

    private fun withParsedUri(value: String, block: (Uri) -> Unit) {
        val uri = mock(Uri::class.java)
        doReturn(value).`when`(uri).toString()
        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<Uri> { Uri.parse(value) }.thenReturn(uri)
            block(uri)
        }
    }

    private fun contextWithResolver(): Pair<Context, ContentResolver> {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        doReturn(resolver).`when`(context).contentResolver
        return context to resolver
    }
}
