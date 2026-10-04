package moe.ouom.neriplayer.core.player.service.car.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class CarArtworkCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `opaque key preserves identity and versions the cover source`() {
        val first = carArtworkKey("song:a", "https://cover/a?token=private")
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertEquals(first, carArtworkKey("song:a", "https://cover/a?token=private"))
        assertNotEquals(first, carArtworkKey("song:b", "https://cover/a?token=private"))
        assertNotEquals(first, carArtworkKey("song:a", "https://cover/b"))
        assertNotEquals(carArtworkKey("ab", "c"), carArtworkKey("a", "bc"))
    }

    @Test
    fun `only exact versioned hash paths can reach a cache file`() {
        val key = carArtworkKey("song", null)
        assertEquals(key, carArtworkKeyFromPath("/v1/$key"))
        for (path in listOf(null, "", "/$key", "/v2/$key", "/v1/$key/extra", "/v1/../secret", "/v1/%2e%2e", "/v1/${key.uppercase()}")) {
            assertNull(path, carArtworkKeyFromPath(path))
        }
    }

    @Test
    fun `jpeg dimensions preserve aspect ratio with a maximum of 512 pixels`() {
        assertEquals(512 to 256, carArtworkSize(2_048, 1_024))
        assertEquals(256 to 512, carArtworkSize(1_024, 2_048))
        assertEquals(1 to 512, carArtworkSize(1, 10_000))
        assertEquals(100 to 200, carArtworkSize(100, 200))
        assertNull(carArtworkSize(0, 100))
        assertNull(carArtworkSize(100, -1))
    }

    @Test
    fun `cached artwork remains readable by a fresh store without source registration`() {
        val directory = temporary.newFolder()
        val key = carArtworkKey("song", "https://cover/a")
        val data = byteArrayOf(1, 2, 3)
        val first = CarArtworkDiskCache(directory)
        File(directory, "pending-interrupted.tmp").writeText("incomplete")
        assertEquals(data.toList(), first.save(key, data)?.readBytes()?.toList())
        assertEquals(data.toList(), CarArtworkDiskCache(directory).find(key)?.readBytes()?.toList())
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "tmp" })
    }

    @Test
    fun `invalid key and oversized replacement cannot modify an existing image`() {
        val directory = temporary.newFolder()
        val key = carArtworkKey("song", null)
        val cache = CarArtworkDiskCache(directory, maxEntryBytes = 4)
        cache.save(key, byteArrayOf(1, 2))
        assertNull(cache.save("../outside", byteArrayOf(9)))
        assertNull(cache.save(key, byteArrayOf(5, 5, 5, 5, 5)))
        assertNull(cache.save(key, byteArrayOf()))
        assertEquals(listOf<Byte>(1, 2), cache.find(key)?.readBytes()?.toList())
        assertEquals(1, directory.listFiles().orEmpty().size)
    }

    @Test
    fun `cache evicts oldest artwork while preserving the just published entry`() {
        val directory = temporary.newFolder()
        val cache = CarArtworkDiskCache(directory, maxEntries = 2)
        val keys = (1..3).map { carArtworkKey("song:$it", null) }
        cache.save(keys[0], byteArrayOf(1))!!.setLastModified(1)
        cache.save(keys[1], byteArrayOf(2))!!.setLastModified(2)
        cache.save(keys[2], byteArrayOf(3))
        assertNull(cache.find(keys[0]))
        assertTrue(cache.find(keys[1]) != null)
        assertTrue(cache.find(keys[2]) != null)
    }

    @Test
    fun `cache also limits total bytes and does not remove foreign files`() {
        val directory = temporary.newFolder()
        val cache = CarArtworkDiskCache(directory, maxBytes = 5)
        val first = carArtworkKey("first", null)
        val second = carArtworkKey("second", null)
        val foreign = File(directory, "other.txt").apply { writeText("preserved") }
        cache.save(first, byteArrayOf(1, 2, 3))!!.setLastModified(1)
        cache.save(second, byteArrayOf(4, 5, 6))
        assertNull(cache.find(first))
        assertTrue(cache.find(second) != null)
        assertEquals("preserved", foreign.readText())
    }

    @Test
    fun `symlinks cannot expose an external file or redirect the cache directory`() {
        val directory = temporary.newFolder()
        val outside = temporary.newFile().apply { writeText("private") }
        val key = carArtworkKey("song", null)
        Files.createSymbolicLink(File(directory, "$key.jpg").toPath(), outside.toPath())
        assertNull(CarArtworkDiskCache(directory).find(key))
        val link = File(temporary.root, "directory-link")
        Files.createSymbolicLink(link.toPath(), directory.toPath())
        assertNull(CarArtworkDiskCache(link).save(key, byteArrayOf(1)))
        assertEquals("private", outside.readText())
    }

    @Test
    fun `source registration is bounded and recent access preserves a request`() {
        val requests = CarArtworkRequests<String>(2)
        requests.register("a", "first")
        requests.register("b", "second")
        assertEquals("first", requests.get("a"))
        requests.register("c", "third")
        assertNull(requests.get("b"))
        assertEquals("first", requests.get("a"))
        assertEquals("third", requests.get("c"))
    }
}
