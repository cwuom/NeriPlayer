package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.LYRIC_SUBDIRECTORY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedDownloadMigrationTargetIndexBuilderTest {
    @Test
    fun `duplicate target names remain occupied but are not selected`() {
        val first = storedEntry(
            name = "song.mp3",
            reference = "content://provider/tree/root/document/first"
        )
        val second = storedEntry(
            name = "song.mp3",
            reference = "content://provider/tree/root/document/second"
        )

        val index = ManagedDownloadMigrationTargetIndexBuilder.build(
            rootEntries = listOf(first, second),
            coverEntries = emptyList(),
            lyricEntries = emptyList()
        )

        assertEquals(setOf("song.mp3"), index.namesFor(null))
        assertNull(index.entryFor(null, "song.mp3"))
        assertNull(index.uniqueEntryForCanonicalName(null, "song.mp3"))
    }

    @Test
    fun `duplicate sidecar names are also treated as ambiguous`() {
        val first = storedEntry(
            name = "song.lrc",
            reference = "content://provider/tree/root/document/first"
        )
        val second = storedEntry(
            name = "song.lrc",
            reference = "content://provider/tree/root/document/second"
        )

        val index = ManagedDownloadMigrationTargetIndexBuilder.build(
            rootEntries = emptyList(),
            coverEntries = emptyList(),
            lyricEntries = listOf(first, second)
        )

        assertEquals(setOf("song.lrc"), index.namesFor(LYRIC_SUBDIRECTORY))
        assertNull(index.entryFor(LYRIC_SUBDIRECTORY, "song.lrc"))
    }

    @Test
    fun `planner allocates a fresh name instead of replacing an ambiguous audio target`() {
        val target = storedEntry(
            name = "song.mp3",
            reference = "content://provider/tree/root/document/target"
        )
        val duplicate = target.copy(
            reference = "content://provider/tree/root/document/duplicate"
        )
        val targetIndex = ManagedDownloadMigrationTargetIndexBuilder.build(
            rootEntries = listOf(target, duplicate),
            coverEntries = emptyList(),
            lyricEntries = emptyList()
        )
        val source = ManagedMigrationEntryRef(
            subdirectory = null,
            entry = storedEntry(
                name = "song.mp3",
                reference = "content://source/tree/root/document/source"
            )
        )

        val plan = ManagedDownloadMigrationNamePlanner.buildNamePlan(
            entries = listOf(source),
            targetIndex = targetIndex
        )

        assertEquals("song (1).mp3", plan.targetNameFor(source))
        assertNull(plan.replacementFor(source))
    }

    private fun storedEntry(name: String, reference: String): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = reference,
            mediaUri = reference,
            localFilePath = null,
            sizeBytes = 42L,
            lastModifiedMs = 7L
        )
    }
}
