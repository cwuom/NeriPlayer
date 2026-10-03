package moe.ouom.neriplayer.data.sync.archive

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncArchiveDownloadedCacheReuseTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `verified downloaded objects can be reused by the first local publish`() {
        val directory = temporary.newFolder()
        val cache = SyncArchiveCache(directory)
        val raw = "downloaded full lyrics".repeat(200).toByteArray()
        val compressed = SyncArchiveCodec.compress(raw)
        val ref = ref(raw, compressed)
        cache.save(ref, compressed)
        val pointer = File(directory, "${ref.rawHash}-false.ref")
        assertTrue("downloaded cache needs a verified raw content pointer", pointer.isFile)
        assertTrue(pointer.setLastModified(1_234_000L))
        assertEquals(ref, cache.store(raw, false))
        assertEquals("reuse must not rewrite or recompress the downloaded object", 1_234_000L, pointer.lastModified())
    }

    @Test fun `invalid downloaded raw checksum cannot create a reusable pointer`() {
        val directory = temporary.newFolder()
        val cache = SyncArchiveCache(directory)
        val raw = "full lyrics".toByteArray()
        val compressed = SyncArchiveCodec.compress(raw)
        val invalid = ref(raw, compressed).copy(rawHash = "0".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { cache.save(invalid, compressed) }
        assertFalse(File(directory, "${invalid.rawHash}-false.ref").exists())
        assertFalse(File(directory, invalid.path).exists())
    }

    @Test fun `corrupt downloaded cache is rebuilt rather than published`() {
        val directory = temporary.newFolder()
        val cache = SyncArchiveCache(directory)
        val raw = "full lyrics".repeat(200).toByteArray()
        val compressed = SyncArchiveCodec.compress(raw)
        val ref = ref(raw, compressed)
        cache.save(ref, compressed)
        File(directory, ref.path).writeBytes(ByteArray(compressed.size))
        val rebuilt = cache.store(raw, false)
        assertEquals(ref, rebuilt)
        assertTrue(raw.contentEquals(cache.readRaw(rebuilt)))
    }

    private fun ref(raw: ByteArray, compressed: ByteArray) =
        SyncArchiveRef(SyncArchiveCodec.digest(compressed), SyncArchiveCodec.digest(raw), raw.size, compressed.size, false)
}
