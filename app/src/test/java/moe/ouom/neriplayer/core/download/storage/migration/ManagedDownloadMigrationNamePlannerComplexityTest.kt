package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationNamePlannerComplexityTest {
    @Test
    fun `one thousand audio names are planned with bounded source enumeration`() {
        val audioEntries = (0 until ENTRY_COUNT).map { index ->
            storedEntry("track-$index.mp3")
        }
        val metadataEntries = audioEntries.map { audioEntry ->
            storedEntry("${audioEntry.name}.npmeta.json")
        }
        val entries = CountingList(
            (audioEntries + metadataEntries).map { entry ->
                ManagedMigrationEntryRef(subdirectory = null, entry = entry)
            }
        )

        val plan = ManagedDownloadMigrationNamePlanner.buildNamePlan(
            entries = entries,
            targetIndex = ManagedMigrationTargetIndex(
                rootEntriesByName = emptyMap(),
                coverEntriesByName = emptyMap(),
                lyricEntriesByName = emptyMap()
            )
        )

        assertEquals(ENTRY_COUNT * 2, plan.targetNamesByReference.size)
        assertTrue(
            "source enumeration must stay linear, reads=${entries.readCount}",
            entries.readCount < MAX_LINEAR_READS
        )
    }

    private fun storedEntry(name: String): ManagedDownloadStorage.StoredEntry {
        val reference = "/source/$name"
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = "file://$reference",
            localFilePath = reference,
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )
    }

    private class CountingList<T>(
        private val values: List<T>
    ) : AbstractList<T>() {
        var readCount: Int = 0
            private set

        override val size: Int
            get() = values.size

        override fun get(index: Int): T {
            readCount += 1
            return values[index]
        }
    }

    private companion object {
        const val ENTRY_COUNT = 1_000
        const val MAX_LINEAR_READS = 50_000
    }
}
