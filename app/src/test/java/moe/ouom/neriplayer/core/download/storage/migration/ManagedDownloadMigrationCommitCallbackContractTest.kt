package moe.ouom.neriplayer.core.download.storage.migration

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationCommitCallbackContractTest {
    @Test
    fun `normal migration switches directory only after cleanup commit`() {
        val source = readStorageSource()
        val migrationBody = source
            .substringAfter("suspend fun migrateManagedDownloads(")
            .substringBefore("private suspend fun applyDeletedSourceCopyReceiptRecoveryPlan(")
        val cleanupIndex = migrationBody.indexOf(
            "val cleanupResult = cleanupMigratedEntriesDetailed("
        )
        val committedJournalIndex = migrationBody.indexOf(
            "phase = ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED",
            cleanupIndex
        )
        val callbackIndex = migrationBody.indexOf("onTargetVerified()", committedJournalIndex)

        assertTrue(cleanupIndex >= 0)
        assertTrue(committedJournalIndex > cleanupIndex)
        assertTrue(callbackIndex > committedJournalIndex)
        assertTrue(
            migrationBody.substring(cleanupIndex, callbackIndex).contains(
                "replacementJournal?.let { onReplacementJournalUpdated(it) }"
            )
        )
    }

    @Test
    fun `directory callback failure keeps committed data and returns transient`() {
        val source = readStorageSource()
        val migrationBody = source
            .substringAfter("suspend fun migrateManagedDownloads(")
            .substringBefore("private suspend fun applyDeletedSourceCopyReceiptRecoveryPlan(")
        val callbackIndex = migrationBody.lastIndexOf("onTargetVerified()")
        val finishIndex = migrationBody.indexOf("progressTracker.finishAll()", callbackIndex)
        val callbackBlock = migrationBody.substring(callbackIndex, finishIndex)

        assertTrue(callbackIndex >= 0)
        assertTrue(finishIndex > callbackIndex)
        assertTrue(callbackBlock.contains("NPLogger.w("))
        assertTrue(callbackBlock.contains("ManagedDownloadMigrationException.transient("))
        assertFalse(callbackBlock.contains("invalidateCopyReceipts("))
        assertFalse(callbackBlock.contains("rollbackMigratedEntries("))
    }

    @Test
    fun `committed journal recovery still invokes directory callback`() {
        val source = readStorageSource()
        val migrationBody = source
            .substringAfter("suspend fun migrateManagedDownloads(")
            .substringBefore("private suspend fun applyDeletedSourceCopyReceiptRecoveryPlan(")
        val committedBranch = migrationBody
            .substringAfter(
                "if (persistedPhase == ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED)"
            )
            .substringBefore("if (shouldRetryActiveMigrationJournal(")

        assertTrue(committedBranch.contains("onTargetVerified()"))
    }

    private fun readStorageSource(): String {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(
                directory,
                "app/src/main/java/moe/ouom/neriplayer/core/download/ManagedDownloadStorage.kt"
            )
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: ManagedDownloadStorage.kt")
    }
}
