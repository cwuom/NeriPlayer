package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadMigrationProgressTrackerTest {
    @Test
    fun `durable copy seeds are not counted twice when resumed`() {
        val updates = mutableListOf<ManagedDownloadStorage.MigrationProgress>()
        val tracker = ManagedDownloadMigrationProgressTracker(
            totalFiles = 3,
            totalBytes = 30L,
            metadataFilesTotal = 0,
            onProgress = updates::add
        )
        val first = entry("first.mp3", 10L)
        val second = entry("second.mp3", 10L)
        val third = entry("third.mp3", 10L)

        tracker.seedCompletedCopies(listOf(first, second))
        tracker.startCopy(first)
        tracker.onCopyProgress(first, first.sizeBytes)
        tracker.completeCopy(first)

        assertEquals(2, updates.last().copiedFiles)
        assertEquals(20L, updates.last().copiedBytes)

        tracker.startCopy(third)
        tracker.completeCopy(third)
        tracker.finishAll()

        assertEquals(3, updates.last().copiedFiles)
        assertEquals(30L, updates.last().copiedBytes)
    }

    @Test
    fun `durable verification seeds are not counted twice when resumed`() {
        val updates = mutableListOf<ManagedDownloadStorage.MigrationProgress>()
        val tracker = ManagedDownloadMigrationProgressTracker(
            totalFiles = 2,
            totalBytes = 20L,
            metadataFilesTotal = 0,
            onProgress = updates::add
        )
        val first = entry("first.mp3", 10L)
        val second = entry("second.mp3", 10L)

        tracker.startVerification(listOf(first, second))
        tracker.seedVerifiedEntries(listOf(first))
        tracker.startVerificationEntry(first)
        tracker.finishVerification(first)

        assertEquals(1, updates.last().verificationFilesProcessed)
        assertEquals(10L, updates.last().verifiedBytes)

        tracker.startVerificationEntry(second)
        tracker.finishVerification(second)

        assertEquals(2, updates.last().verificationFilesProcessed)
        assertEquals(20L, updates.last().verifiedBytes)
    }

    @Test
    fun `new cleanup batch resets processed count even when totals match`() {
        val updates = mutableListOf<ManagedDownloadStorage.MigrationProgress>()
        val tracker = ManagedDownloadMigrationProgressTracker(
            totalFiles = 4,
            totalBytes = 40L,
            metadataFilesTotal = 0,
            onProgress = updates::add
        )

        tracker.startCleanup(totalEntries = 2, fileName = "backup-a")
        tracker.finishCleanup("backup-a")
        tracker.startCleanup(totalEntries = 2, fileName = "backup-b")
        tracker.finishCleanup("backup-b")
        assertEquals(2, updates.last().cleanupFilesProcessed)

        // null is the cleanup-batch marker used before source cleanup
        tracker.startCleanup(totalEntries = 2, fileName = null)
        assertEquals(0, updates.last().cleanupFilesProcessed)
        tracker.finishCleanup("source-a")
        tracker.finishCleanup("source-b")

        assertEquals(2, updates.last().cleanupFilesProcessed)
        assertEquals(2, updates.last().cleanupFilesTotal)
    }

    @Test
    fun `processed files does not double count metadata across later stages`() {
        val updates = mutableListOf<ManagedDownloadStorage.MigrationProgress>()
        var nowMs = 0L
        val tracker = ManagedDownloadMigrationProgressTracker(
            totalFiles = 4,
            totalBytes = 40L,
            metadataFilesTotal = 1,
            onProgress = updates::add,
            nowMs = { nowMs }
        )
        val copiedEntries = listOf(
            entry("audio.mp3", 10L),
            entry("audio.npmeta.json", 10L),
            entry("cover.jpg", 10L)
        )

        copiedEntries.forEach { copied ->
            nowMs += 1_000L
            tracker.startCopy(copied)
            nowMs += 1_000L
            tracker.completeCopy(copied)
        }
        assertEquals(3, updates.last().processedFiles)

        nowMs += 1_000L
        tracker.startRewrite("audio.npmeta.json")
        nowMs += 1_000L
        tracker.finishRewrite("audio.npmeta.json")
        assertEquals(3, updates.last().processedFiles)
        assertEquals(1, updates.last().stageProcessed)
        assertEquals(1, updates.last().stageTotal)

        nowMs += 1_000L
        tracker.startVerification(copiedEntries)
        assertEquals(3, updates.last().processedFiles)
        assertEquals(0, updates.last().stageProcessed)
        assertEquals(3, updates.last().stageTotal)

        nowMs += 1_000L
        tracker.startCleanup(totalEntries = copiedEntries.size, fileName = null)
        assertEquals(3, updates.last().processedFiles)
        assertEquals(0, updates.last().stageProcessed)
        assertEquals(3, updates.last().stageTotal)
    }

    private fun entry(name: String, sizeBytes: Long): ManagedMigrationProgressEntry {
        return ManagedMigrationProgressEntry(
            reference = "content://source/$name",
            name = name,
            sizeBytes = sizeBytes
        )
    }
}
