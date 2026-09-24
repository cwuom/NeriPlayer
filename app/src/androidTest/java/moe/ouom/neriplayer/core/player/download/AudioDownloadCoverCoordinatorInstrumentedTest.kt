package moe.ouom.neriplayer.core.player.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.metadata.MAX_SOURCE_COVER_BYTES
import moe.ouom.neriplayer.core.download.storage.metadata.isCoverPixelBudgetWithin
import moe.ouom.neriplayer.data.model.SongItem
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioDownloadCoverCoordinatorInstrumentedTest {
    @Test
    fun valid4429PixelCoverIsSampledAndCommittedOnFirstAttempt() = runBlocking {
        assertLargeCover(width = 4429, height = 4429, alpha = false)
    }

    @Test
    fun valid4627PixelCoverPreservesTransparencyWithinDecodeBudget() = runBlocking {
        assertLargeCover(width = 4627, height = 4627, alpha = true)
    }

    @Test
    fun largeJpegCoverIsSampledAndCommittedOnFirstAttempt() = runBlocking {
        val bitmap = Bitmap.createBitmap(4627, 4627, Bitmap.Config.RGB_565)
        val bytes = try {
            bitmap.eraseColor(Color.rgb(20, 80, 120))
            ByteArrayOutputStream().also { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        assertLargeCover(width = 4627, height = 4627, alpha = false, sourceBytes = bytes)
    }

    @Test
    fun original4000PixelCoverKeepsBytesWhileValidationUsesBoundedDecode() = runBlocking {
        val bytes = png(4000, 4000, alpha = false)
        val result = download(bytes)
        assertNotNull(result.reference)
        assertEquals(1, result.requests)
        assertArrayEquals(bytes, requireNotNull(result.committed))
    }

    @Test
    fun smallCoverKeepsOriginalBytes() = runBlocking {
        val bytes = png(37, 29, alpha = false)
        val result = download(bytes)
        assertNotNull(result.reference)
        assertEquals(1, result.requests)
        assertArrayEquals(bytes, requireNotNull(result.committed))
    }

    @Test
    fun invalidImageNeverReachesCommit() = runBlocking {
        val result = download("not an image".toByteArray())
        assertNull(result.reference)
        assertNull(result.committed)
    }

    @Test
    fun truncatedImageWithValidHeaderNeverReachesCommit() = runBlocking {
        val original = png(4429, 4429, alpha = false)
        val result = download(original.copyOf(original.size / 2))
        assertNull(result.reference)
        assertNull(result.committed)
    }

    @Test
    fun incompleteHttpBodyNeverReachesCommit() = runBlocking {
        val bytes = png(37, 29, alpha = false)
        val result = download(bytes, declaredBytes = bytes.size.toLong() + 1)
        assertNull(result.reference)
        assertNull(result.committed)
    }

    @Test
    fun sourceByteLimitStillRejectsOtherwiseValidImage() = runBlocking {
        val bytes = png(37, 29, alpha = false)
        val result = download(bytes, maxBytes = bytes.size.toLong() - 1)
        assertNull(result.reference)
        assertNull(result.committed)
    }

    private suspend fun assertLargeCover(
        width: Int,
        height: Int,
        alpha: Boolean,
        sourceBytes: ByteArray = png(width, height, alpha)
    ) {
        val result = download(sourceBytes)
        assertNotNull("a valid large cover must not enter permanent validation retries", result.reference)
        assertEquals("sampling must succeed on the first network attempt", 1, result.requests)
        val bytes = requireNotNull(result.committed)
        assertTrue(bytes.size.toLong() <= MAX_SOURCE_COVER_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertTrue(isCoverPixelBudgetWithin(bounds.outWidth, bounds.outHeight))
        assertTrue(isCoverPixelBudgetWithin(bounds.outWidth, bounds.outHeight, MAX_COVER_DECODE_PIXELS))
        assertEquals(width.toDouble() / height, bounds.outWidth.toDouble() / bounds.outHeight, 0.002)
        assertEquals(bounds.outMimeType, result.mimeType)
        assertTrue(result.fileName.orEmpty().endsWith(if (alpha) ".png" else ".jpg"))
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            if (alpha) {
                assertTrue(bitmap.hasAlpha())
                assertEquals(127, bitmap.getPixel(0, 0).ushr(24))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun download(
        bytes: ByteArray,
        maxBytes: Long = MAX_SOURCE_COVER_BYTES,
        declaredBytes: Long = bytes.size.toLong()
    ): Outcome {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var requests = 0
        var committed: ByteArray? = null
        var mimeType: String? = null
        var fileName: String? = null
        val coordinator = AudioDownloadCoverCoordinator(
            maxAttempts = 3,
            retryDelayMs = 0,
            maxResponseBytes = maxBytes,
            ensureNotCancelled = { _, _, _, _, _, _ -> },
            executeTrackedCall = { request, _, _, _, consume ->
                requests += 1
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(if (declaredBytes == bytes.size.toLong()) {
                        bytes.toResponseBody("image/png".toMediaType())
                    } else {
                        object : ResponseBody() {
                            override fun contentType() = "image/png".toMediaType()
                            override fun contentLength() = declaredBytes
                            override fun source() = Buffer().write(bytes)
                        }
                    })
                    .build().use(consume)
            },
            commitCover = { _, cover, name, mime, _, _, _, _, _ ->
                committed = cover
                mimeType = mime
                fileName = name
                "test-cover-reference"
            },
            rememberPartial = { _, _, _ -> }
        )
        val identity = UUID.randomUUID().toString()
        val reference = coordinator.cacheCover(
            context = context,
            song = SongItem(483378334, "cover fixture", "artist", "album", 0, 1000, "https://example.invalid/cover.png"),
            songKey = identity,
            baseName = identity,
            storedAudio = ManagedDownloadStorage.StoredEntry(
                name = "$identity.mp3", reference = "test-audio-$identity", mediaUri = "",
                localFilePath = null, sizeBytes = 1, lastModifiedMs = 1
            ),
            allowIndexedLookup = false,
            operationId = identity
        )
        return Outcome(reference, requests, committed, mimeType, fileName)
    }

    private data class Outcome(
        val reference: AudioCachedCoverReference?,
        val requests: Int,
        val committed: ByteArray?,
        val mimeType: String?,
        val fileName: String?
    )

    private fun png(width: Int, height: Int, alpha: Boolean): ByteArray {
        val channels = if (alpha) 4 else 3
        val row = ByteArray(1 + width * channels)
        repeat(width) { x ->
            val offset = 1 + x * channels
            row[offset] = 20
            row[offset + 1] = 80
            row[offset + 2] = 120
            if (alpha) row[offset + 3] = 127
        }
        val compressed = ByteArrayOutputStream().also { buffer ->
            DeflaterOutputStream(buffer).use { stream -> repeat(height) { stream.write(row) } }
        }.toByteArray()
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
                val header = ByteArrayOutputStream().also { data ->
                    DataOutputStream(data).use {
                        it.writeInt(width)
                        it.writeInt(height)
                        it.writeByte(8)
                        it.writeByte(if (alpha) 6 else 2)
                        it.write(byteArrayOf(0, 0, 0))
                    }
                }.toByteArray()
                output.chunk("IHDR", header)
                output.chunk("IDAT", compressed)
                output.chunk("IEND", byteArrayOf())
            }
        }.toByteArray()
    }

    private fun DataOutputStream.chunk(type: String, payload: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        writeInt(payload.size)
        write(typeBytes)
        write(payload)
        writeInt(CRC32().apply {
            update(typeBytes)
            update(payload)
        }.value.toInt())
    }
}
