package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmbeddedCoverCacheWriteTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val coverDirectory = File(context.filesDir, "local_audio_covers")

    @After
    fun clearCovers() {
        coverDirectory.deleteRecursively()
    }

    @Test
    fun `missing embedded pictures are not cached`() {
        assertNull(LocalMediaSupport.saveEmbeddedCover(context, "song", null))
        assertNull(LocalMediaSupport.saveEmbeddedCover(context, "song", ByteArray(0)))
        assertFalse(coverDirectory.exists())
    }

    @Test
    fun `small decodable pictures are cached unchanged`() {
        val picture = png(4, 3)

        val cached = File(URI(LocalMediaSupport.saveEmbeddedCover(context, "song", picture)!!))

        assertEquals(LocalMediaSupport.embeddedCoverFile(context, "song"), cached)
        assertArrayEquals(picture, cached.readBytes())
        assertFalse(File(coverDirectory, ".${cached.name}.tmp").exists())
    }

    @Test
    fun `a usable cached picture is reused instead of being overwritten`() {
        val first = png(2, 2)
        val original = LocalMediaSupport.saveEmbeddedCover(context, "song", first)

        assertEquals(original, LocalMediaSupport.saveEmbeddedCover(context, "song", png(5, 5)))
        assertArrayEquals(first, LocalMediaSupport.embeddedCoverFile(context, "song").readBytes())
    }

    @Test
    fun `an undecodable cached picture is replaced`() {
        val cacheFile = LocalMediaSupport.embeddedCoverFile(context, "song").apply {
            parentFile!!.mkdirs()
            writeText("broken")
        }
        val picture = png(3, 3)

        assertEquals(cacheFile.toURI().toString(), LocalMediaSupport.saveEmbeddedCover(context, "song", picture))
        assertArrayEquals(picture, cacheFile.readBytes())
    }

    @Test
    fun `undecodable pictures are not cached`() {
        assertNull(LocalMediaSupport.saveEmbeddedCover(context, "song", byteArrayOf(1, 2, 3)))
        assertFalse(LocalMediaSupport.embeddedCoverFile(context, "song").exists())
    }

    @Test
    fun `a blocked cache directory leaves the picture uncached`() {
        coverDirectory.writeText("not a directory")

        assertNull(LocalMediaSupport.saveEmbeddedCover(context, "song", png(2, 2)))
        assertTrue(coverDirectory.isFile)
        coverDirectory.delete()
    }

    @Test
    fun `oversized pictures are downsampled into a jpeg within the cache budget`() {
        val picture = png(1_000, 800) + ByteArray(MAX_EMBEDDED_COVER_CACHE_BYTES)
        assertTrue(picture.size > MAX_EMBEDDED_COVER_CACHE_BYTES)

        val compacted = LocalMediaSupport.compactEmbeddedCoverForCache(picture)!!

        assertTrue(compacted.size <= MAX_EMBEDDED_COVER_CACHE_BYTES)
        assertEquals("image/jpeg", LocalMediaSupport.detectEditableCoverMimeType(compacted))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(compacted, 0, compacted.size, bounds)
        assertEquals(500, bounds.outWidth)
        assertEquals(400, bounds.outHeight)
    }

    @Test
    fun `oversized undecodable pictures cannot be compacted`() {
        assertNull(LocalMediaSupport.compactEmbeddedCoverForCache(ByteArray(MAX_EMBEDDED_COVER_CACHE_BYTES + 1)))
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun installMockMakerBeforeNativeJpegEncoding() {
            // Robolectric 原生 JPEG 编码后 ByteBuddy 无法再自附加，同一 JVM 中之后首次创建 mock 会失败
            Mockito.mock(Runnable::class.java)
        }
    }

    private fun png(width: Int, height: Int): ByteArray = encode(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB))

    private fun encode(image: BufferedImage): ByteArray = ByteArrayOutputStream().use { output ->
        ImageIO.write(image, "png", output)
        output.toByteArray()
    }
}
