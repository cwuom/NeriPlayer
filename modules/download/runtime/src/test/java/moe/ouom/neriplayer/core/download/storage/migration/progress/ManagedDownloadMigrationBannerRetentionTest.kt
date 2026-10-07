package moe.ouom.neriplayer.core.download.storage.migration.progress

import androidx.work.WorkInfo
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournalPhase
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationBannerRetentionTest {
    @Test
    fun `banner stays while work is unfinished or its checkpoint is unreadable`() {
        assertTrue(preserve(state = WorkInfo.State.SUCCEEDED, checkpointReadFailed = true))
        assertTrue(preserve(state = WorkInfo.State.RUNNING))
        assertTrue(preserve(state = WorkInfo.State.ENQUEUED, hasPersistedRequest = true))
    }

    @Test
    fun `finished work keeps the banner only for resumable or uncommitted migrations`() {
        assertTrue(preserve(state = WorkInfo.State.FAILED, requestAutoResume = true))
        assertTrue(preserve(state = null, journalPhase = ManagedMigrationReplacementJournalPhase.PLANNED))
        assertTrue(
            preserve(state = WorkInfo.State.CANCELLED, journalPhase = ManagedMigrationReplacementJournalPhase.TARGETS_VERIFIED)
        )
        assertFalse(
            preserve(state = WorkInfo.State.SUCCEEDED, journalPhase = ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED)
        )
        assertFalse(
            preserve(
                state = null,
                journalPhase = ManagedMigrationReplacementJournalPhase.PLANNED,
                hasPersistedRequest = true
            )
        )
        assertFalse(preserve(state = null))
    }

    @Test
    fun `active work is replaced only by a different persisted request`() {
        val persisted = request(workId = "5F0C2A4E-8B7D-4C3B-9A61-2E8F4D1B7C90")

        assertTrue(shouldReplaceActiveMigrationWork(persisted, "migration-other"))
        assertFalse(shouldReplaceActiveMigrationWork(persisted, "5f0c2a4e-8b7d-4c3b-9a61-2e8f4d1b7c90"))
        assertFalse(shouldReplaceActiveMigrationWork(persisted, null))
        assertFalse(shouldReplaceActiveMigrationWork(null, "migration-other"))
    }

    private fun preserve(
        state: WorkInfo.State?,
        requestAutoResume: Boolean = false,
        journalPhase: ManagedMigrationReplacementJournalPhase? = null,
        hasPersistedRequest: Boolean = false,
        checkpointReadFailed: Boolean = false
    ): Boolean {
        return shouldPreserveMigrationUiAfterWorkInfo(
            workInfoState = state,
            requestAutoResume = requestAutoResume,
            journalPhase = journalPhase,
            hasPersistedRequest = hasPersistedRequest,
            checkpointReadFailed = checkpointReadFailed
        )
    }

    private fun request(workId: String): ManagedMigrationRequest {
        return ManagedMigrationRequest(
            workId = workId,
            fromDirectoryUri = null,
            toDirectoryUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic",
            targetLabel = "Music",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 0
        )
    }
}
