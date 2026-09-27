package moe.ouom.neriplayer.ui.screen.tab

import androidx.work.Data
import androidx.work.WorkInfo
import java.util.UUID
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SettingsDownloadDirectoryMigrationSnapshotTest {
    @Test
    fun `read failures preserve migration ui even when individual records are unavailable`() {
        val emptyRequest = Result.success<ManagedMigrationRequest?>(null)
        val emptyJournal = Result.success<ManagedMigrationReplacementJournal?>(null)
        val emptyWork = Result.success(emptyList<WorkInfo>())
        val failure = IllegalStateException("provider failed")

        listOf(
            Triple(Result.failure<ManagedMigrationRequest?>(failure), emptyJournal, emptyWork),
            Triple(emptyRequest, Result.failure<ManagedMigrationReplacementJournal?>(failure), emptyWork),
            Triple(emptyRequest, emptyJournal, Result.failure<List<WorkInfo>>(failure))
        ).forEach { (request, journal, work) ->
            val snapshot = composePersistedMigrationReadResults(request, journal, work) { null }
            assertTrue(snapshot.checkpointReadFailed)
            assertTrue(snapshot.shouldPreserveUi)
        }
        val healthy = composePersistedMigrationReadResults(
            emptyRequest, emptyJournal, emptyWork
        ) { null }
        assertFalse(healthy.checkpointReadFailed)
    }

    @Test
    fun `empty durable state produces no recoverable migration`() {
        val snapshot = composePersistedMigrationUiSnapshot(
            request = null,
            journal = null,
            workInfos = emptyList(),
            readProgress = { error("no checkpoint should be read") },
            checkpointReadFailed = false
        )

        assertNull(snapshot.activeWorkId)
        assertNull(snapshot.progress)
        assertFalse(snapshot.hasPersistedRequest)
        assertFalse(snapshot.shouldPreserveUi)
    }

    @Test
    fun `persisted request restores its checkpoint even without a worker row`() {
        val request = ManagedMigrationRequest(
            workId = "work-1",
            fromDirectoryUri = null,
            toDirectoryUri = "content://target",
            targetLabel = "target",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 0
        )
        val checkpoint = ManagedDownloadStorage.MigrationProgress(
            stage = ManagedDownloadStorage.MigrationStage.COPYING,
            totalFiles = 2,
            processedFiles = 1,
            copiedFiles = 1,
            copiedBytes = 50,
            totalBytes = 100,
            metadataFilesProcessed = 0,
            metadataFilesTotal = 2,
            cleanupFilesProcessed = 0,
            cleanupFilesTotal = 2
        )
        val snapshot = composePersistedMigrationUiSnapshot(
            request = request,
            journal = null,
            workInfos = emptyList(),
            readProgress = { workId -> if (workId == request.workId) checkpoint else null },
            checkpointReadFailed = false
        )

        assertNull(snapshot.activeWorkId)
        assertEquals(checkpoint, snapshot.progress)
        assertTrue(snapshot.hasPersistedRequest)
        assertTrue(snapshot.requestAutoResume)
        assertTrue(snapshot.shouldPreserveUi)
        assertTrue(snapshot.shouldResume)
    }

    @Test
    fun `running worker remains visible when its persisted request is missing`() {
        val work = mock(WorkInfo::class.java)
        val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
        `when`(work.id).thenReturn(id)
        `when`(work.state).thenReturn(WorkInfo.State.RUNNING)
        `when`(work.progress).thenReturn(Data.EMPTY)

        val snapshot = composePersistedMigrationUiSnapshot(
            request = null,
            journal = null,
            workInfos = listOf(work),
            readProgress = { null },
            checkpointReadFailed = false
        )

        assertEquals(id.toString(), snapshot.activeWorkId)
        assertEquals(WorkInfo.State.RUNNING, snapshot.activeWorkState)
        assertTrue(snapshot.shouldPreserveUi)
        assertFalse(snapshot.shouldResume)
    }
}
