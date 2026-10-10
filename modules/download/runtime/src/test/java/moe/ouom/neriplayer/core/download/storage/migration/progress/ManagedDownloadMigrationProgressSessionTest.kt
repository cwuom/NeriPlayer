package moe.ouom.neriplayer.core.download.storage.migration.progress

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationProgressSessionTest {
    private val session = ManagedDownloadMigrationProgressSession()

    @Test
    fun `ensure keeps the current owner and refuses a different worker`() {
        val preparing = progress(ManagedDownloadStorage.MigrationStage.PREPARING)
        val copying = progress(ManagedDownloadStorage.MigrationStage.COPYING)

        assertTrue(session.ensure("work-1", preparing))
        assertTrue(session.ensure(" work-1 ", copying))
        assertFalse(session.ensure("work-2", preparing))

        assertEquals(copying, session.flow.value)
        assertTrue(session.isOwner("work-1"))
        assertFalse(session.isOwner("work-2"))
        assertThrows(IllegalStateException::class.java) {
            session.ensure("   ", preparing)
        }
    }

    @Test
    fun `blank or missing owners only match an unowned session`() {
        val verifying = progress(ManagedDownloadStorage.MigrationStage.VERIFYING)
        val finalizing = progress(ManagedDownloadStorage.MigrationStage.FINALIZING)

        assertFalse(session.isOwner("  "))
        assertTrue(session.publish(null, verifying))
        assertTrue(session.publish(" ", finalizing))
        assertEquals(finalizing, session.flow.value)

        session.ensure("work-1", verifying)
        assertFalse(session.publish(null, finalizing))
        assertFalse(session.finish(" "))
        assertTrue(session.finish(" work-1 "))
        assertNull(session.flow.value)
        assertTrue(session.finish(null))
    }

    private fun progress(stage: ManagedDownloadStorage.MigrationStage) = ManagedDownloadStorage.MigrationProgress(
        stage = stage,
        totalFiles = 2,
        processedFiles = 1,
        copiedFiles = 1,
        copiedBytes = 10L,
        totalBytes = 20L,
        metadataFilesProcessed = 0,
        metadataFilesTotal = 0,
        cleanupFilesProcessed = 0,
        cleanupFilesTotal = 0
    )
}
