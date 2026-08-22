package moe.ouom.neriplayer.core.download.index

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedLibraryFastIndexTest {
    @Test
    fun `index round trips and sorts entries`() {
        val entries = listOf(
            entry("z-song"),
            entry("a-song")
        )
        val raw = ManagedLibraryFastIndex.encode("library", "03", entries, 42L)

        val restored = ManagedLibraryFastIndex.decode(raw)

        assertNotNull(restored)
        assertEquals(listOf("a-song", "z-song"), restored?.entries?.map { it.stableKey })
        assertEquals("library", restored?.libraryId)
    }

    @Test
    fun `checksum corruption is rejected and atomic writer creates parent`() {
        val directory = Files.createTempDirectory("neriplayer-index").toFile()
        val file = directory.resolve("NeriIndex/03.json")
        ManagedLibraryFastIndex.writeAtomically(file, "library", "03", listOf(entry("song")), 1L)

        assertTrue(file.isFile)
        assertNotNull(ManagedLibraryFastIndex.decode(file.readText()))
        assertNull(
            ManagedLibraryFastIndex.decode(
                file.readText().replace("song", "changed")
            )
        )
        directory.deleteRecursively()
    }

    private fun entry(stableKey: String): ManagedLibraryIndexEntry {
        return ManagedLibraryIndexEntry(
            stableKey = stableKey,
            artifactId = "artifact:$stableKey",
            audioName = "$stableKey.mp3",
            audioReference = "content://audio/$stableKey",
            metadataName = "$stableKey.mp3.npmeta.json",
            state = "CORE_COMMITTED",
            downloadTimeMs = 1L,
            updatedAtMs = 2L
        )
    }
}
