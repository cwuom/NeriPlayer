package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.COVER_SUBDIRECTORY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedDownloadMigrationNamePlannerResumeTest {
    @Test
    fun `source removal restores only the surviving persisted assignment`() {
        val removed = coverEntry("/source-a/cover.jpg")
        val survivor = coverEntry("/source-b/cover.jpg")
        val entries = listOf(survivor)
        val generated = buildPlan(entries)

        val restored = requireNotNull(
            ManagedDownloadMigrationNamePlanner.restorePersistedNamePlan(
                entries = entries,
                targetIndex = emptyTargetIndex,
                generatedPlan = generated,
                persistedTargetNames = mapOf(
                    removed.entry.reference to "cover.jpg",
                    survivor.entry.reference to "cover (1).jpg"
                )
            )
        )

        assertEquals("cover (1).jpg", restored.targetNameFor(survivor))
        assertEquals(setOf(survivor.entry.reference), restored.targetNamesByReference.keys)
    }

    @Test
    fun `new source avoids target snapshot and restored assignment`() {
        val added = coverEntry("/source-a/cover.jpg")
        val survivor = coverEntry("/source-b/cover.jpg")
        val occupiedTarget = storedEntry(
            name = "cover.jpg",
            reference = "content://target/cover",
            sizeBytes = 42L
        )
        val targetIndex = emptyTargetIndex.copy(
            coverEntriesByName = mapOf(occupiedTarget.name to occupiedTarget)
        )
        val entries = listOf(survivor, added)
        val generated = ManagedDownloadMigrationNamePlanner.buildNamePlan(entries, targetIndex)

        val restored = requireNotNull(
            ManagedDownloadMigrationNamePlanner.restorePersistedNamePlan(
                entries = entries,
                targetIndex = targetIndex,
                generatedPlan = generated,
                persistedTargetNames = mapOf(survivor.entry.reference to "cover (1).jpg")
            )
        )

        assertEquals("cover (1).jpg", restored.targetNameFor(survivor))
        assertEquals("cover (2).jpg", restored.targetNameFor(added))
    }

    @Test
    fun `changed persisted target falls back without reusing its bytes`() {
        val source = coverEntry("/source/cover.jpg")
        val changedTarget = storedEntry(
            name = "cover.jpg",
            reference = "content://target/cover",
            sizeBytes = 99L
        )
        val targetIndex = emptyTargetIndex.copy(
            coverEntriesByName = mapOf(changedTarget.name to changedTarget)
        )
        val generated = ManagedDownloadMigrationNamePlanner.buildNamePlan(listOf(source), targetIndex)

        val restored = ManagedDownloadMigrationNamePlanner.restorePersistedNamePlan(
            entries = listOf(source),
            targetIndex = targetIndex,
            generatedPlan = generated,
            persistedTargetNames = mapOf(source.entry.reference to changedTarget.name)
        ) ?: generated

        assertEquals("cover (1).jpg", restored.targetNameFor(source))
        assertNull(restored.reusedTargetFor(source))
    }

    private fun buildPlan(entries: List<ManagedMigrationEntryRef>): ManagedMigrationNamePlan {
        return ManagedDownloadMigrationNamePlanner.buildNamePlan(entries, emptyTargetIndex)
    }

    private fun coverEntry(reference: String): ManagedMigrationEntryRef {
        return ManagedMigrationEntryRef(
            subdirectory = COVER_SUBDIRECTORY,
            entry = storedEntry(name = "cover.jpg", reference = reference, sizeBytes = 42L)
        )
    }

    private fun storedEntry(
        name: String,
        reference: String,
        sizeBytes: Long
    ): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = reference,
            localFilePath = reference.takeIf { path -> path.startsWith('/') },
            sizeBytes = sizeBytes,
            lastModifiedMs = 1L
        )
    }

    private companion object {
        val emptyTargetIndex = ManagedMigrationTargetIndex(
            rootEntriesByName = emptyMap(),
            coverEntriesByName = emptyMap(),
            lyricEntriesByName = emptyMap()
        )
    }
}
