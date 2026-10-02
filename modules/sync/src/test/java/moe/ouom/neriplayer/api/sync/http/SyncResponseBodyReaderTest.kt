package moe.ouom.neriplayer.api.sync.http

import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SyncResponseBodyReaderTest {
    @Test
    fun `legacy and canonical archive paths select their own response budgets`() {
        assertEquals(SyncFileTransferLimits.ARCHIVE_FILE_BYTES, SyncFileTransferLimits.responseBudget("neriplayer-sync-v3.manifest"))
        assertEquals(SyncFileTransferLimits.ARCHIVE_FILE_BYTES, SyncFileTransferLimits.responseBudget("/directory/neriplayer-sync-v3-${"a".repeat(64)}.zst"))
        for (path in listOf("backup.json", "backup.bin", "neriplayer-sync-v3-short.zst")) {
            assertEquals(SyncResponseBodyReader.MAX_SYNC_FILE_BYTES, SyncFileTransferLimits.responseBudget(path))
        }
    }

    @Test
    fun `known length excess is rejected before reading the response source`() {
        val body = ByteArray(1_025).toResponseBody()
        body.use { assertTrue(runCatching { SyncResponseBodyReader.read(it, 1_024) }.exceptionOrNull() is IOException) }
    }

    @Test
    fun `unknown length reader stops near the limit instead of consuming the whole oversized stream`() {
        var consumed = 0L
        val source = object : ForwardingSource(Buffer().write(ByteArray(16_384))) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { if (it > 0L) consumed += it }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1L
            override fun source(): BufferedSource = source
        }
        body.use { assertTrue(runCatching { SyncResponseBodyReader.read(it, 1_024) }.exceptionOrNull() is IOException) }
        assertTrue("consumed $consumed", consumed <= 8_192L)
    }

    @Test
    fun `exact length empty response and text remain supported while invalid budgets fail`() {
        val bytes = byteArrayOf(1, 2, 3)
        bytes.toResponseBody().use { assertArrayEquals(bytes, SyncResponseBodyReader.read(it, bytes.size)) }
        byteArrayOf().toResponseBody().use { assertArrayEquals(byteArrayOf(), SyncResponseBodyReader.read(it)) }
        "legacy".toResponseBody().use { assertEquals("legacy", SyncResponseBodyReader.readText(it)) }
        bytes.toResponseBody().use { assertTrue(runCatching { SyncResponseBodyReader.read(it, 0) }.exceptionOrNull() is IllegalArgumentException) }
    }
}
