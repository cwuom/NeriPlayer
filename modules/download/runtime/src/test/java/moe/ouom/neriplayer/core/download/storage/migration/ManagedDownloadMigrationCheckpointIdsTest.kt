package moe.ouom.neriplayer.core.download.storage.migration

import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournal
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationReplacementJournalPhase
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedMigrationRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadMigrationCheckpointIdsTest {
    @Test
    fun `checkpoint ids fall back to the trimmed current work without persisted state`() {
        assertEquals(
            listOf("work-1"),
            migrationProgressCheckpointIds(
                currentWorkId = " work-1 ",
                inputCheckpointWorkId = null,
                persistedRequest = null,
                persistedJournal = null
            )
        )
    }

    @Test
    fun `checkpoint ids skip blank or missing sources and keep the first occurrence`() {
        assertEquals(
            listOf("work-2", "work-1", "journal-1"),
            migrationProgressCheckpointIds(
                currentWorkId = "work-2",
                inputCheckpointWorkId = "  ",
                persistedRequest = request(workId = "work-1", checkpointWorkId = "work-2"),
                persistedJournal = journal(workId = " journal-1 ")
            )
        )
        assertEquals(
            listOf("work-3", "work-1"),
            migrationProgressCheckpointIds(
                currentWorkId = "work-3",
                inputCheckpointWorkId = "work-1",
                persistedRequest = request(workId = "work-1", checkpointWorkId = null),
                persistedJournal = null
            )
        )
    }

    private fun request(workId: String, checkpointWorkId: String?) = ManagedMigrationRequest(
        workId = workId,
        fromDirectoryUri = "content://old/tree/music",
        toDirectoryUri = "content://new/tree/music",
        targetLabel = "Music",
        releasePreviousPermission = false,
        minimumSourceEntryCount = 1,
        checkpointWorkId = checkpointWorkId
    )

    private fun journal(workId: String) = ManagedMigrationReplacementJournal(
        workId = workId,
        fromDirectoryUri = "content://old/tree/music",
        toDirectoryUri = "content://new/tree/music",
        backupNamespace = "migration",
        phase = ManagedMigrationReplacementJournalPhase.PLANNED,
        replacements = emptyList()
    )
}
