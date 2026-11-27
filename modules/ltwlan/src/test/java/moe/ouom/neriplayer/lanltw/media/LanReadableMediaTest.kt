package moe.ouom.neriplayer.lanltw.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LanReadableMediaTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun fingerprintDoesNotCreateCopyAndEachReadDetectsChangedBytes() = runBlocking {
        val original = temporary.newFile().apply { writeText("abc") }
        val before = temporary.root.listFiles()!!.toList()
        val (source, hash) = LanReadableMedia.fingerprint(open = original::inputStream)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
        assertEquals(before, temporary.root.listFiles()!!.toList())
        assertEquals("abc", source.open().use { it.readBytes().decodeToString() })
        original.writeText("xyz")
        try { source.open().use { it.readBytes() }; fail("Changed bytes accepted") }
        catch (expected: java.io.IOException) { assertEquals("SOURCE_CHANGED", expected.message) }
        assertTrue(original.exists())
    }
    @Test fun borrowedSourceSurvivesRetirementAndServerStop() = runBlocking {
        val original = temporary.newFile().apply { writeText("original") }
        val (source, _) = LanReadableMedia.fingerprint(open = original::inputStream)
        val server = LanMediaServer()
        val first = java.util.UUID.randomUUID().toString()
        server.publishBorrowed(first, source, "audio/mpeg")
        server.retire(first)
        assertEquals("original", original.readText())
        server.publishBorrowed(java.util.UUID.randomUUID().toString(), source, "audio/mpeg")
        server.start(5000, true); server.stop()
        assertEquals("original", original.readText())
    }
    @Test fun borrowedHttpReadsOriginalAndRejectsSameLengthMutationWithoutDeletingIt() = runBlocking {
        val original = temporary.newFile().apply { writeText("original") }
        val (source, hash) = LanReadableMedia.fingerprint(open = original::inputStream)
        val server = LanMediaServer()
        val id = java.util.UUID.randomUUID().toString()
        server.publishBorrowed(id, source, "audio/mpeg"); server.start(5000, true)
        val client = okhttp3.OkHttpClient()
        val token = server.grant("member")
        val resource = moe.ouom.neriplayer.data.model.lan.LanLocalMedia(id, hash, 8, 1000, "audio/mpeg")
        val directory = temporary.newFolder()
        val endpoint = okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").build()
        try {
            val downloader = LanMediaDownload(client)
            val cached = downloader.fetch(endpoint, server.listeningPort, token, resource, directory)
            assertEquals("original", cached.readText()); cached.delete()
            original.writeText("tampered")
            try { downloader.fetch(endpoint, server.listeningPort, token, resource, directory); fail("Modified source accepted") }
            catch (_: IllegalStateException) { }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally {
            server.stop(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
        assertEquals("tampered", original.readText())
    }
    @Test fun directFingerprintEnforcesThe200MiBBoundaryWithoutDiskCopies() = runBlocking {
        fun bytes(size: Long) = object : java.io.InputStream() {
            var remaining = size
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                val count = minOf(remaining, length.toLong()).toInt()
                buffer.fill(0, offset, offset + count); remaining -= count; return count
            }
        }
        val limit = moe.ouom.neriplayer.data.model.lan.LanLocalMedia.MAX_BYTES
        assertEquals(200L * 1024 * 1024, limit)
        val source = LanReadableMedia.fingerprint { bytes(limit) }.first
        assertEquals(limit, source.byteLength)
        try { LanReadableMedia.fingerprint { bytes(limit + 1) }; fail("Oversized source accepted") }
        catch (expected: IllegalStateException) { assertEquals("MEMORY_LIMIT", expected.message) }
        try { LanReadableMedia.fingerprint { bytes(0) }; fail("Empty source accepted") }
        catch (expected: IllegalStateException) { assertEquals("AUDIO_FORMAT", expected.message) }
    }
    @Test fun shorterSourceAndChangedFileStampAreRejected() = runBlocking {
        val original = temporary.newFile().apply { writeText("abcdef") }
        val (source, _) = LanReadableMedia.fingerprint(open = original::inputStream)
        val fileSource = LanReadableMedia.file(original)
        original.writeText("a")
        try { source.open().use { it.readBytes() }; fail("Truncation accepted") }
        catch (expected: java.io.IOException) { assertEquals("SOURCE_CHANGED", expected.message) }
        try { fileSource.open(); fail("Changed file accepted") }
        catch (expected: IllegalStateException) { assertEquals("SOURCE_CHANGED", expected.message) }
    }
}
