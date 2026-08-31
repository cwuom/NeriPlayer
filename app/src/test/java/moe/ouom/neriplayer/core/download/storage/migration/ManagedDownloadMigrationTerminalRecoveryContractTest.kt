package moe.ouom.neriplayer.core.download.storage.migration

import android.content.SharedPreferences
import androidx.work.WorkInfo
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyString
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.io.File

class ManagedDownloadMigrationTerminalRecoveryContractTest {
    @Test
    fun `terminal request with incomplete journal does not auto resume`() {
        assertFalse(
            shouldResumePersistedMigrationAfterWorkInfo(
                workInfoState = WorkInfo.State.FAILED,
                requestAutoResume = false,
                journalPhase = ManagedMigrationReplacementJournalPhase.TARGETS_VERIFIED,
                hasPersistedRequest = true
            )
        )
        assertFalse(
            shouldResumePersistedMigrationAfterWorkInfo(
                workInfoState = WorkInfo.State.SUCCEEDED,
                requestAutoResume = false,
                journalPhase = ManagedMigrationReplacementJournalPhase.PLANNED,
                hasPersistedRequest = true
            )
        )
    }

    @Test
    fun `journal only recovery remains eligible for automatic resume`() {
        assertTrue(
            shouldResumePersistedMigrationAfterWorkInfo(
                workInfoState = WorkInfo.State.FAILED,
                requestAutoResume = false,
                journalPhase = ManagedMigrationReplacementJournalPhase.TARGETS_VERIFIED,
                hasPersistedRequest = false
            )
        )
    }

    @Test
    fun `terminal request does not preserve ui or block startup from incomplete journal`() {
        val request = terminalRequest()
        val journal = incompleteJournal(request.workId)

        assertFalse(
            shouldPreserveMigrationUiAfterWorkInfo(
                workInfoState = WorkInfo.State.FAILED,
                requestAutoResume = false,
                journalPhase = journal.phase,
                hasPersistedRequest = true
            )
        )
        assertFalse(shouldBlockStartupForMigrationRecovery(request, journal))
    }

    @Test
    fun `journal only recovery still preserves ui and blocks startup`() {
        val journal = incompleteJournal("journal-only")

        assertTrue(
            shouldPreserveMigrationUiAfterWorkInfo(
                workInfoState = WorkInfo.State.FAILED,
                requestAutoResume = false,
                journalPhase = journal.phase,
                hasPersistedRequest = false
            )
        )
        assertTrue(shouldBlockStartupForMigrationRecovery(null, journal))
    }

    @Test
    fun `terminal checkpoint persists auto resume as false`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val requestKey = ManagedDownloadMigrationCheckpointStore.ACTIVE_REQUEST_KEY
        val workId = "terminal-work"
        val requestPayload = JSONObject().apply {
            put(
                "version",
                ManagedDownloadMigrationCheckpointStore.CURRENT_MIGRATION_REQUEST_VERSION
            )
            put("workId", workId)
            put("fromDirectoryUri", "content://source")
            put("toDirectoryUri", "content://target")
            put("targetLabel", "target")
            put("releasePreviousPermission", false)
            put("minimumSourceEntryCount", 1)
            put("autoResume", true)
        }.toString()
        `when`(preferences.getString(requestKey, null)).thenReturn(requestPayload)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putString(eq(requestKey), anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)

        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        assertTrue(store.markRequestTerminal(workId))

        val payloadCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(editor).putString(eq(requestKey), payloadCaptor.capture())
        assertFalse(JSONObject(payloadCaptor.value).getBoolean("autoResume"))
    }

    @Test
    fun `startup recovery uses conditional writes for every rearm branch`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/migration/" +
                "ManagedDownloadMigrationWorker.kt"
        ).readText()
        val body = source.substringAfter("suspend fun resumePersistedRequestIfNeeded")
            .substringBefore("suspend fun hasPersistedMigrationRecovery")

        assertFalse(body.contains("checkpointStore.recordRequest(resumed)"))
        assertTrue(
            Regex("checkpointStore\\.recordRequestIfCurrent\\(")
                .findAll(body)
                .count() == 3
        )
        assertTrue(body.contains("expectedAutoResume = persisted?.autoResume"))
    }

    @Test
    fun `worker start write checks the persisted resume state`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/migration/" +
                "ManagedDownloadMigrationWorker.kt"
        ).readText()
        val body = source.substringAfter("private suspend fun runMigration(): Result")
            .substringBefore("private fun isPendingArtifactPreflightFailure")

        assertTrue(body.contains("expectedAutoResume = persistedRequestAtStart?.autoResume"))
        assertTrue(body.contains("shouldAbortTerminalMigrationWorker"))
    }

    @Test
    fun `terminal request with a legacy work id remains stopped after merge`() {
        val persisted = terminalRequest().copy(workId = "legacy-work")
        val effective = mergeMigrationRequestForWorker(
            persisted = persisted,
            input = persisted.copy(workId = "new-work"),
            inputKeys = emptySet()
        )

        assertTrue(shouldAbortTerminalMigrationWorker(persisted, effective))
    }

    @Test
    fun `retryable recovery cannot reopen terminal requests`() {
        val source = locateProjectFile(
            "app/src/main/java/moe/ouom/neriplayer/core/download/storage/migration/" +
                "ManagedDownloadMigrationCheckpointStore.kt"
        ).readText()
        val body = source.substringAfter("fun markRequestRetryable")
            .substringBefore("@SuppressLint(\"UseKtx\")\n    fun clearRequest")

        assertTrue(body.contains("if (!current.autoResume) return@synchronized false"))
    }

    private fun locateProjectFile(relativePath: String): File {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $relativePath")
    }

    private fun terminalRequest(): ManagedMigrationRequest {
        return ManagedMigrationRequest(
            workId = "terminal-work",
            fromDirectoryUri = "content://source",
            toDirectoryUri = "content://target",
            targetLabel = "target",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 1,
            autoResume = false
        )
    }

    private fun incompleteJournal(workId: String): ManagedMigrationReplacementJournal {
        return ManagedMigrationReplacementJournal(
            workId = workId,
            fromDirectoryUri = "content://source",
            toDirectoryUri = "content://target",
            backupNamespace = "migration",
            phase = ManagedMigrationReplacementJournalPhase.TARGETS_VERIFIED,
            replacements = emptyList(),
            sourceEntryCount = 1,
            sourceEntries = listOf(
                ManagedMigrationSourceEntry(
                    sourceReference = "content://source/track",
                    sourceName = "track.mp3",
                    sourceSubdirectory = null,
                    sizeBytes = 1L,
                    lastModifiedMs = 1L
                )
            )
        )
    }
}
