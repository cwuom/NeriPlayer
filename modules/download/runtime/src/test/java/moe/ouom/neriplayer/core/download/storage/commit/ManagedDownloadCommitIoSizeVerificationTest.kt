package moe.ouom.neriplayer.core.download.storage.commit

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class ManagedDownloadCommitIoSizeVerificationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val uri: Uri = mock(Uri::class.java)

    @Test
    fun `plain stream copy returns the copied byte count`() {
        val payload = ByteArray(10_000) { index -> (index % 251).toByte() }
        val output = ByteArrayOutputStream()

        val copied = ManagedDownloadCommitIo.copyStreamWithProgress(
            input = ByteArrayInputStream(payload),
            output = output,
            bufferSizeBytes = 1024
        )

        assertEquals(payload.size.toLong(), copied)
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun `progress copy reports cumulative bytes and skips empty reads`() {
        val payload = "0123456789abcdef".toByteArray()
        val progress = mutableListOf<Long>()
        val output = ByteArrayOutputStream()

        val copied = ManagedDownloadCommitIo.copyStreamWithProgress(
            input = StutteringInputStream(payload, chunkSize = 6),
            output = output,
            bufferSizeBytes = 64,
            onProgress = { copiedBytes -> progress += copiedBytes }
        )

        assertEquals(16L, copied)
        assertEquals(listOf(6L, 12L, 16L), progress)
        assertArrayEquals(payload, output.toByteArray())
    }

    @Test
    fun `digest copy hashes exactly the bytes that reached the output`() {
        val payload = "managed download payload".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
        val output = ByteArrayOutputStream()

        val copied = ManagedDownloadCommitIo.copyStreamWithProgress(
            input = StutteringInputStream(payload, chunkSize = 5),
            output = output,
            bufferSizeBytes = 8,
            outputDigest = digest
        )

        assertEquals(payload.size.toLong(), copied)
        assertArrayEquals(payload, output.toByteArray())
        assertEquals(sha256Hex(payload), ManagedDownloadCommitIo.digestHex(digest))
    }

    @Test
    fun `combined progress and digest copy keeps both observers in step`() {
        val payload = ByteArray(20) { index -> index.toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        val progress = mutableListOf<Long>()

        val copied = ManagedDownloadCommitIo.copyStreamWithProgress(
            input = ByteArrayInputStream(payload),
            output = ByteArrayOutputStream(),
            bufferSizeBytes = 8,
            onProgress = { copiedBytes -> progress += copiedBytes },
            outputDigest = digest
        )

        assertEquals(20L, copied)
        assertEquals(listOf(8L, 16L, 20L), progress)
        assertEquals(sha256Hex(payload), ManagedDownloadCommitIo.digestHex(digest))
    }

    @Test
    fun `stream byte counting ignores empty reads and stops at end of stream`() {
        assertEquals(
            11L,
            ManagedDownloadCommitIo.countInputStreamBytes(
                StutteringInputStream(ByteArray(11), chunkSize = 4),
                bufferSizeBytes = 3
            )
        )
        assertEquals(
            0L,
            ManagedDownloadCommitIo.countInputStreamBytes(ByteArrayInputStream(ByteArray(0)), 8)
        )
    }

    @Test
    fun `verified byte count accepts a reported size within tolerance`() {
        assertEquals(
            98L,
            ManagedDownloadCommitIo.requireVerifiedCommittedByteCount(
                expectedSizeBytes = 100L,
                reportedSizeBytes = 98L,
                countedSizeBytes = 100L,
                toleranceBytes = 4L,
                description = "tree audio"
            )
        )
    }

    @Test
    fun `verified byte count failure names the expected and observed sizes`() {
        val error = assertThrows(IOException::class.java) {
            ManagedDownloadCommitIo.requireVerifiedCommittedByteCount(
                expectedSizeBytes = -5L,
                reportedSizeBytes = 7L,
                countedSizeBytes = 9L,
                description = "cover.jpg"
            )
        }

        assertEquals(
            "提交后的目标大小不匹配: cover.jpg, expected=0, reported=7, counted=9",
            error.message
        )
    }

    @Test
    fun `file commit length accepts the exact committed size`() {
        val target = tempFolder.newFile("song.flac").apply { writeBytes(ByteArray(42)) }

        assertEquals(
            42L,
            ManagedDownloadCommitIo.verifyFileCommittedLength(target, 42L, "song.flac")
        )
    }

    @Test
    fun `file commit length rejects missing targets directories and truncated files`() {
        val missing = File(tempFolder.root, "missing.flac")
        val directory = tempFolder.newFolder("folder.flac")
        val truncated = tempFolder.newFile("short.flac").apply { writeBytes(ByteArray(10)) }

        val missingError = assertThrows(IOException::class.java) {
            ManagedDownloadCommitIo.verifyFileCommittedLength(missing, 42L, "missing.flac")
        }
        val directoryError = assertThrows(IOException::class.java) {
            ManagedDownloadCommitIo.verifyFileCommittedLength(directory, 42L, "folder.flac")
        }
        val truncatedError = assertThrows(IOException::class.java) {
            ManagedDownloadCommitIo.verifyFileCommittedLength(truncated, 42L, "short.flac")
        }

        assertTrue(missingError.message!!.endsWith("reported=unavailable, counted=unavailable"))
        assertTrue(directoryError.message!!.endsWith("reported=unavailable, counted=unavailable"))
        assertTrue(truncatedError.message!!.endsWith("expected=42, reported=10, counted=unavailable"))
    }

    @Test
    fun `document size reported by the provider is trusted without reading the stream`() {
        val cursor = sizeCursor(size = 1_000L)
        val resolver = resolver(cursor = cursor, content = ByteArray(1_000))

        val verified = verifyDocument(resolver, expectedSizeBytes = 1_000L)

        assertEquals(1_000L, verified)
        verify(resolver, never()).openInputStream(any())
        verify(cursor).close()
    }

    @Test
    fun `document size inside the provider tolerance is accepted as reported`() {
        val resolver = resolver(cursor = sizeCursor(size = 996L), content = ByteArray(1_000))

        val verified = verifyDocument(resolver, expectedSizeBytes = 1_000L, toleranceBytes = 8L)

        assertEquals(996L, verified)
        verify(resolver, never()).openInputStream(any())
    }

    @Test
    fun `drifted provider size falls back to counting the committed bytes`() {
        val resolver = resolver(cursor = sizeCursor(size = 900L), content = ByteArray(1_000))

        assertEquals(1_000L, verifyDocument(resolver, expectedSizeBytes = 1_000L))
        verify(resolver).openInputStream(uri)
    }

    @Test
    fun `unusable size metadata falls back to counting the committed bytes`() {
        val scenarios = mapOf(
            "missing size column" to sizeCursor(columnIndex = -1),
            "empty cursor" to sizeCursor(hasRow = false),
            "null size" to sizeCursor(isNull = true),
            "negative size" to sizeCursor(size = -1L)
        )

        scenarios.forEach { (scenario, cursor) ->
            val resolver = resolver(cursor = cursor, content = ByteArray(64))

            assertEquals(scenario, 64L, verifyDocument(resolver, expectedSizeBytes = 64L))
            verify(resolver).openInputStream(uri)
            verify(cursor).close()
        }
    }

    @Test
    fun `query failure is reported and the counted size still verifies the commit`() {
        val queryFailure = SecurityException("permission revoked")
        val resolver = resolver(cursor = null, content = ByteArray(32))
        doThrow(queryFailure).`when`(resolver).query(any(), any(), any(), any(), any())
        val queryFailures = mutableListOf<Throwable>()
        val countFailures = mutableListOf<Throwable>()

        val verified = verifyDocument(
            resolver,
            expectedSizeBytes = 32L,
            onQueryFailure = { error -> queryFailures += error },
            onCountFailure = { error -> countFailures += error }
        )

        assertEquals(32L, verified)
        assertEquals(1, queryFailures.size)
        assertSame(queryFailure, queryFailures.single())
        assertTrue(countFailures.isEmpty())
    }

    @Test
    fun `unreadable document fails verification without any observed size`() {
        val countFailure = FileNotFoundException("document vanished")
        val resolver = resolver(cursor = null, content = null)
        doThrow(countFailure).`when`(resolver).openInputStream(any())
        val countFailures = mutableListOf<Throwable>()

        val error = assertThrows(IOException::class.java) {
            verifyDocument(
                resolver,
                expectedSizeBytes = 32L,
                onCountFailure = { failure -> countFailures += failure }
            )
        }

        assertEquals(listOf<Throwable>(countFailure), countFailures)
        assertTrue(error.message!!.endsWith("expected=32, reported=unavailable, counted=unavailable"))
    }

    @Test
    fun `missing input stream leaves a drifted provider size unverified`() {
        val resolver = resolver(cursor = sizeCursor(size = 900L), content = null)

        val error = assertThrows(IOException::class.java) {
            verifyDocument(resolver, expectedSizeBytes = 1_000L)
        }

        assertTrue(error.message!!.endsWith("expected=1000, reported=900, counted=unavailable"))
    }

    private fun verifyDocument(
        resolver: ContentResolver,
        expectedSizeBytes: Long,
        toleranceBytes: Long = 0L,
        onQueryFailure: (Throwable) -> Unit = {},
        onCountFailure: (Throwable) -> Unit = {}
    ): Long {
        return ManagedDownloadCommitIo.verifyDocumentCommittedLength(
            contentResolver = resolver,
            uri = uri,
            expectedSizeBytes = expectedSizeBytes,
            toleranceBytes = toleranceBytes,
            bufferSizeBytes = 16,
            description = "content://tree/song.flac",
            onQueryFailure = onQueryFailure,
            onCountFailure = onCountFailure
        )
    }

    private fun resolver(cursor: Cursor?, content: ByteArray?): ContentResolver {
        val resolver = mock(ContentResolver::class.java)
        doReturn(cursor).`when`(resolver).query(any(), any(), any(), any(), any())
        if (content != null) {
            doAnswer { ByteArrayInputStream(content) }.`when`(resolver).openInputStream(any())
        }
        return resolver
    }

    private fun sizeCursor(
        columnIndex: Int = 0,
        hasRow: Boolean = true,
        isNull: Boolean = false,
        size: Long = 0L
    ): Cursor {
        val cursor = mock(Cursor::class.java)
        doReturn(columnIndex).`when`(cursor).getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        doReturn(hasRow).`when`(cursor).moveToFirst()
        doReturn(isNull).`when`(cursor).isNull(columnIndex)
        doReturn(size).`when`(cursor).getLong(columnIndex)
        return cursor
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** Alternates empty reads with short chunks, like a slow provider pipe. */
    private class StutteringInputStream(
        private val payload: ByteArray,
        private val chunkSize: Int
    ) : InputStream() {
        private var position = 0
        private var emitEmptyRead = true

        override fun read(): Int {
            if (position >= payload.size) return -1
            return payload[position++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= payload.size) return -1
            if (emitEmptyRead) {
                emitEmptyRead = false
                return 0
            }
            emitEmptyRead = true
            val count = minOf(chunkSize, length, payload.size - position)
            payload.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }
}
