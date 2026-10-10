package moe.ouom.neriplayer.core.download.storage.migration.plan

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationPolicyDecisionTest {

    @Test
    fun `reattach requires a default source without entries and a populated target`() {
        assertTrue(reattach(from = null, to = TARGET, source = false, target = true))
        assertTrue(reattach(from = "  ", to = TARGET, source = null, target = true))

        assertFalse(reattach(from = SOURCE, to = TARGET, source = false, target = true))
        assertFalse(reattach(from = null, to = null, source = false, target = true))
        assertFalse(reattach(from = null, to = "   ", source = false, target = true))
        assertFalse(reattach(from = null, to = TARGET, source = true, target = true))
        assertFalse(reattach(from = null, to = TARGET, source = false, target = null))
        assertFalse(reattach(from = null, to = TARGET, source = false, target = false))
    }

    @Test
    fun `probed reattachment treats blank directories like the default directory`() = runTest {
        val probes = mutableListOf<String>()

        assertTrue(probeReattach(from = "  ", to = TARGET, probes = probes))
        assertEquals(listOf("source", "target"), probes)

        probes.clear()
        assertFalse(probeReattach(from = null, to = "   ", probes = probes))
        assertEquals(emptyList<String>(), probes)
    }

    @Test
    fun `equivalent directories apply directly without probing either side`() = runTest {
        val probes = mutableListOf<String>()

        assertEquals(
            ManagedDownloadDirectoryChangeDecision.APPLY_DIRECTLY,
            resolve(from = "$SOURCE/", to = SOURCE, probes = probes)
        )
        assertEquals(
            ManagedDownloadDirectoryChangeDecision.APPLY_DIRECTLY,
            resolve(from = null, to = "  ", probes = probes)
        )
        assertEquals(emptyList<String>(), probes)
    }

    @Test
    fun `blank source directory can reattach a populated target`() = runTest {
        val probes = mutableListOf<String>()

        val decision = resolve(from = "  ", to = TARGET, probes = probes)

        assertEquals(ManagedDownloadDirectoryChangeDecision.REATTACH_EXISTING_TARGET, decision)
        assertEquals(listOf("source", "target"), probes)
    }

    @Test
    fun `only root metadata sidecars are written as json documents`() {
        assertEquals("application/json", ManagedDownloadMigrationPolicy.mimeTypeFor(ref(null, "Song.flac.npmeta.json")))
        assertEquals("text/plain", ManagedDownloadMigrationPolicy.mimeTypeFor(ref("Covers", "Song.flac.npmeta.json")))
        assertEquals("text/plain", ManagedDownloadMigrationPolicy.mimeTypeFor(ref(null, "notes.json")))
        assertEquals("audio/flac", ManagedDownloadMigrationPolicy.mimeTypeFor(ref(null, "Song.flac")))
        assertEquals("image/jpeg", ManagedDownloadMigrationPolicy.mimeTypeFor(ref("Covers", "Song.jpg")))
    }

    private fun reattach(from: String?, to: String?, source: Boolean?, target: Boolean?): Boolean {
        return ManagedDownloadMigrationPolicy.shouldReattachExistingManagedDirectory(
            fromDirectoryUri = from,
            toDirectoryUri = to,
            sourceHasManagedEntries = source,
            targetHasManagedEntries = target
        )
    }

    private suspend fun probeReattach(from: String?, to: String?, probes: MutableList<String>): Boolean {
        return ManagedDownloadMigrationPolicy.shouldReattachExistingManagedDirectoryAfterProbes(
            fromDirectoryUri = from,
            toDirectoryUri = to,
            probeSourceHasManagedEntries = {
                probes += "source"
                false
            },
            probeTargetHasManagedEntries = {
                probes += "target"
                true
            }
        )
    }

    private suspend fun resolve(
        from: String?,
        to: String?,
        probes: MutableList<String>
    ): ManagedDownloadDirectoryChangeDecision {
        return ManagedDownloadMigrationPolicy.resolveDirectoryChangeAfterProbes(
            fromDirectoryUri = from,
            toDirectoryUri = to,
            probeSourceHasManagedEntries = {
                probes += "source"
                false
            },
            probeTargetHasManagedEntries = {
                probes += "target"
                true
            },
            probeTargetNonEmpty = {
                probes += "target_non_empty"
                true
            }
        )
    }

    private fun ref(subdirectory: String?, name: String): ManagedMigrationEntryRef {
        return ManagedMigrationEntryRef(
            subdirectory = subdirectory,
            entry = ManagedDownloadStorage.StoredEntry(
                name = name,
                reference = "content://source/$name",
                mediaUri = "content://source/$name",
                localFilePath = null,
                sizeBytes = 1L,
                lastModifiedMs = 1L
            )
        )
    }

    private companion object {
        const val SOURCE = "content://provider/tree/source"
        const val TARGET = "content://provider/tree/target"
    }
}
