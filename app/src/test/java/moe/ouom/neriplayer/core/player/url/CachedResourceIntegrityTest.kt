@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.url

import androidx.media3.datasource.cache.CacheSpan
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedResourceIntegrityTest {

    @Test
    fun `matching span files are treated as a complete resource`() {
        val first = temporaryFile(4)
        val second = temporaryFile(6)
        try {
            val result = inspectCachedResourceSpans(
                spans = listOf(
                    CacheSpan("key", 0L, 4L, 0L, first),
                    CacheSpan("key", 4L, 6L, 0L, second)
                ),
                contentLength = 10L
            )

            assertTrue(result.isComplete)
            assertFalse(result.requiresRepair)
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test
    fun `short span file is marked for repair`() {
        val file = temporaryFile(3)
        try {
            val result = inspectCachedResourceSpans(
                spans = listOf(CacheSpan("key", 0L, 4L, 0L, file)),
                contentLength = 4L
            )

            assertFalse(result.isComplete)
            assertTrue(result.requiresRepair)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `missing span file is marked for repair`() {
        val file = temporaryFile(4)
        val span = CacheSpan("key", 0L, 4L, 0L, file)
        file.delete()

        val result = inspectCachedResourceSpans(
            spans = listOf(span),
            contentLength = 4L
        )

        assertFalse(result.isComplete)
        assertTrue(result.requiresRepair)
    }

    @Test
    fun `overlapping spans are marked for repair`() {
        val first = temporaryFile(6)
        val second = temporaryFile(4)
        try {
            val result = inspectCachedResourceSpans(
                spans = listOf(
                    CacheSpan("key", 0L, 6L, 0L, first),
                    CacheSpan("key", 4L, 4L, 0L, second)
                ),
                contentLength = 8L
            )

            assertFalse(result.isComplete)
            assertTrue(result.requiresRepair)
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test
    fun `span beyond recorded content length is marked for repair`() {
        val file = temporaryFile(5)
        try {
            val result = inspectCachedResourceSpans(
                spans = listOf(CacheSpan("key", 0L, 5L, 0L, file)),
                contentLength = 4L
            )

            assertFalse(result.isComplete)
            assertTrue(result.requiresRepair)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a gap keeps a resource incomplete without discarding valid spans`() {
        val first = temporaryFile(4)
        val second = temporaryFile(2)
        try {
            val result = inspectCachedResourceSpans(
                spans = listOf(
                    CacheSpan("key", 0L, 4L, 0L, first),
                    CacheSpan("key", 6L, 2L, 0L, second)
                ),
                contentLength = 8L
            )

            assertFalse(result.isComplete)
            assertFalse(result.requiresRepair)
            assertTrue(result.coveredLength == 4L)
        } finally {
            first.delete()
            second.delete()
        }
    }

    private fun temporaryFile(length: Long): File {
        return File.createTempFile("neriplayer-cache-test", ".exo").also {
            it.outputStream().use { output ->
                output.write(ByteArray(length.toInt()))
            }
            it.deleteOnExit()
        }
    }
}
