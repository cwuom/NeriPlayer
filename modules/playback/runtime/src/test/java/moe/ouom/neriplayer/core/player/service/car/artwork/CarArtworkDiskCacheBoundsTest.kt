package moe.ouom.neriplayer.core.player.service.car.artwork

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CarArtworkDiskCacheBoundsTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val key = carArtworkKey("song", "https://cover/a")

    @Test
    fun `every cache limit must be positive`() {
        val directory = temporary.newFolder()

        assertThrows(IllegalArgumentException::class.java) { CarArtworkDiskCache(directory, maxEntries = 0) }
        assertThrows(IllegalArgumentException::class.java) { CarArtworkDiskCache(directory, maxBytes = 0L) }
        assertThrows(IllegalArgumentException::class.java) { CarArtworkDiskCache(directory, maxEntryBytes = 0) }

        val tight = CarArtworkDiskCache(directory, maxEntries = 1, maxBytes = 1L, maxEntryBytes = 1)
        assertEquals(listOf<Byte>(7), tight.save(key, byteArrayOf(7))?.readBytes()?.toList())
    }

    @Test
    fun `lookups ignore malformed keys missing files and directories`() {
        val directory = temporary.newFolder()
        val cache = CarArtworkDiskCache(directory)

        assertNull(cache.find("../$key"))
        assertNull(cache.find(key))

        File(directory, "$key.jpg").mkdirs()
        assertNull(cache.find(key))
    }

    @Test
    fun `lookups reject empty and oversized entries`() {
        val directory = temporary.newFolder()
        val cache = CarArtworkDiskCache(directory, maxEntryBytes = 4)
        val entry = File(directory, "$key.jpg")

        entry.writeBytes(ByteArray(0))
        assertNull(cache.find(key))

        entry.writeBytes(ByteArray(5))
        assertNull(cache.find(key))
    }

    @Test
    fun `a hit refreshes the entry so eviction keeps it`() {
        val directory = temporary.newFolder()
        val cache = CarArtworkDiskCache(directory, maxEntryBytes = 4)
        val entry = File(directory, "$key.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
            setLastModified(1_000L)
        }

        val found = cache.find(key)

        assertEquals(entry.canonicalFile, found?.canonicalFile)
        assertTrue(entry.lastModified() > 1_000L)
    }
}
