package moe.ouom.neriplayer.core.download.storage.tree

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedDownloadNoMediaMarkerCreationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `audio facing subdirectories never receive a nomedia marker`() {
        val lyrics = temporaryFolder.newFolder("Lyrics")
        val ensured = ConcurrentHashMap(mapOf("previous" to true))

        ManagedDownloadMediaScanIsolation.ensureFileDirectory("Lyrics", lyrics, ensured)

        assertFalse(File(lyrics, ".nomedia").exists())
        assertEquals(mapOf("previous" to true), ensured)
    }

    @Test
    fun `cover directories reuse an existing marker or create a missing one`() {
        val existing = temporaryFolder.newFolder("existing", "Covers")
        File(existing, ".nomedia").createNewFile()
        val fresh = temporaryFolder.newFolder("fresh", "Covers")
        val ensured = ConcurrentHashMap<String, Boolean>()

        ManagedDownloadMediaScanIsolation.ensureFileDirectory("Covers", existing, ensured)
        ManagedDownloadMediaScanIsolation.ensureFileDirectory("covers", fresh, ensured)

        assertTrue(File(fresh, ".nomedia").isFile)
        assertEquals(mapOf(existing.absolutePath to true, fresh.absolutePath to true), ensured)
    }

    @Test
    fun `a marker that cannot be created fails and forgets the cached marker`() {
        val missingDirectory = File(temporaryFolder.root, "missing/Covers")
        val ensured = ConcurrentHashMap(mapOf(missingDirectory.absolutePath to true))

        val error = assertThrows(IOException::class.java) {
            ManagedDownloadMediaScanIsolation.ensureFileDirectory("Covers", missingDirectory, ensured)
        }

        assertEquals("无法创建 .nomedia: ${missingDirectory.absolutePath}", error.message)
        assertTrue(ensured.isEmpty())
        assertFalse(missingDirectory.exists())
    }
}
