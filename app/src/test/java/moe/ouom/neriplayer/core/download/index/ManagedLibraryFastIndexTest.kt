package moe.ouom.neriplayer.core.download.index

import java.nio.file.Files
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedLibraryFastIndexTest {
    @Test
    fun `fast index write is blocked when metadata is incomplete`() {
        val snapshot = ManagedDownloadStorage.emptyDownloadLibrarySnapshot().copy(
            audioEntriesWithoutMetadata = listOf(
                ManagedDownloadStorage.StoredEntry(
                    name = "song.mp3",
                    reference = "content://audio/song",
                    mediaUri = "content://audio/song",
                    localFilePath = null,
                    sizeBytes = 1L,
                    lastModifiedMs = 1L
                )
            ),
            rootEntriesComplete = true
        )

        assertEquals(false, ManagedDownloadStorage.shouldPersistFastIndex(snapshot))
    }

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

    @Test
    fun `changed shards only include shards whose entries changed`() {
        val unchanged = entry("same")
        val previous = mapOf(
            "00" to listOf(unchanged),
            "01" to listOf(entry("old"))
        )
        val next = mapOf(
            "00" to listOf(unchanged),
            "01" to listOf(entry("new"))
        )

        assertEquals(
            setOf("01"),
            ManagedLibraryFastIndex.changedShards(previous, next)
        )
    }

    @Test
    fun `root entries are joined in memory without per-entry lookup`() {
        val entries = listOf(entry("a"), entry("b"))

        val joined = ManagedLibraryFastIndex.joinAudioReferences(
            indexEntries = entries,
            rootEntries = listOf(
                ManagedLibraryFastIndex.RootEntry("b.mp3", "content://b"),
                ManagedLibraryFastIndex.RootEntry("a.mp3", "content://a")
            )
        )

        assertEquals(
            mapOf("a.mp3" to "content://a", "b.mp3" to "content://b"),
            joined
        )
        assertTrue("missing root entries must not become preview references", "missing.mp3" !in joined)
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
