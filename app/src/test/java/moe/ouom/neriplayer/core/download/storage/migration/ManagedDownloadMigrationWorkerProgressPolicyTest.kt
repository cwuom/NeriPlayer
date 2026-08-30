package moe.ouom.neriplayer.core.download.storage.migration

import androidx.work.workDataOf
import java.io.IOException
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ManagedLibraryRefreshOutcome
import moe.ouom.neriplayer.core.download.ManagedLibraryRefreshPreserveReason
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.util.UUID

class ManagedDownloadMigrationWorkerProgressPolicyTest {
    @Test
    fun `progress session rejects stale owner cleanup and publishes only current owner`() {
        val session = ManagedDownloadMigrationProgressSession()
        val oldProgress = progress(stage = ManagedDownloadStorage.MigrationStage.FINALIZING)
        val newProgress = progress(stage = ManagedDownloadStorage.MigrationStage.PREPARING)

        assertTrue(session.tryClaim("old-work", oldProgress))
        assertFalse(session.tryClaim("new-work", newProgress))
        assertTrue(session.finish("old-work"))
        assertTrue(session.tryClaim("new-work", newProgress))

        assertFalse(session.publish("old-work", oldProgress))
        assertEquals(newProgress, session.flow.value)
        assertTrue(session.publish("new-work", newProgress.copy(currentFileName = "next")))
        assertEquals("next", session.flow.value?.currentFileName)
        assertTrue(session.finish("new-work"))
        assertNull(session.flow.value)
    }

    @Test
    fun `legacy progress restoration cannot overwrite an active worker session`() {
        val session = ManagedDownloadMigrationProgressSession()
        val progress = progress(stage = ManagedDownloadStorage.MigrationStage.COPYING)

        assertTrue(session.tryClaim("active-work", progress))
        assertFalse(session.restoreIfIdle(progress.copy(currentFileName = "stale")))
        assertEquals(null, session.flow.value?.currentFileName)
    }

    @Test
    fun `shared processing uses cleanup counter while deleting verified files`() {
        val progress = ManagedDownloadStorage.MigrationProgress(
            stage = ManagedDownloadStorage.MigrationStage.CLEANING_UP,
            totalFiles = 2_301,
            processedFiles = 2_301,
            copiedFiles = 2_301,
            copiedBytes = 1L,
            totalBytes = 1L,
            metadataFilesProcessed = 120,
            metadataFilesTotal = 120,
            cleanupFilesProcessed = 37,
            cleanupFilesTotal = 2_301
        )

        assertEquals(
            MigrationSharedProgress(processed = 37, total = 2_301),
            migrationProgressForSharedProcessing(progress)
        )
    }

    private fun progress(
        stage: ManagedDownloadStorage.MigrationStage
    ): ManagedDownloadStorage.MigrationProgress {
        return ManagedDownloadStorage.MigrationProgress(
            stage = stage,
            totalFiles = 2,
            processedFiles = if (stage == ManagedDownloadStorage.MigrationStage.FINALIZING) 2 else 0,
            copiedFiles = if (stage == ManagedDownloadStorage.MigrationStage.FINALIZING) 2 else 0,
            copiedBytes = 2L,
            totalBytes = 2L,
            metadataFilesProcessed = 0,
            metadataFilesTotal = 0,
            cleanupFilesProcessed = 0,
            cleanupFilesTotal = 0
        )
    }

    @Test
    fun `committed replacement journal does not block ordinary startup scan`() {
        val journal = ManagedMigrationReplacementJournal(
            workId = "work",
            fromDirectoryUri = null,
            toDirectoryUri = "content://target",
            backupNamespace = "migration",
            phase = ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED,
            replacements = listOf(
                ManagedMigrationReplacementPlan(
                    sourceReference = "content://source/audio",
                    groupIdentity = "song",
                    subdirectory = null,
                    targetName = "audio.mp3",
                    targetEntry = ManagedDownloadStorage.StoredEntry(
                        name = "audio.mp3",
                        reference = "content://target/audio",
                        mediaUri = "content://target/audio",
                        localFilePath = null,
                        sizeBytes = 1L,
                        lastModifiedMs = 1L
                    ),
                    backupName = "backup.mp3"
                )
            )
        )
        assertFalse(shouldBlockStartupForMigrationRecovery(null, journal))
        assertTrue(
            shouldBlockStartupForMigrationRecovery(
                ManagedMigrationRequest(
                    workId = "work",
                    fromDirectoryUri = null,
                    toDirectoryUri = "content://target",
                    targetLabel = "target",
                    releasePreviousPermission = false,
                    minimumSourceEntryCount = 1
                ),
                journal
            )
        )
    }

    @Test
    fun `settings preserves migration ui after missing or finished work row`() {
        assertTrue(
            shouldPreserveMigrationUiAfterWorkInfo(
                workInfoState = null,
                requestAutoResume = true,
                journalPhase = ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED
            )
        )
        assertTrue(
            shouldPreserveMigrationUiAfterWorkInfo(
                workInfoState = androidx.work.WorkInfo.State.SUCCEEDED,
                requestAutoResume = false,
                journalPhase = ManagedMigrationReplacementJournalPhase.TARGETS_VERIFIED
            )
        )
        assertFalse(
            shouldPreserveMigrationUiAfterWorkInfo(
                workInfoState = androidx.work.WorkInfo.State.FAILED,
                requestAutoResume = false,
                journalPhase = ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED
            )
        )
        assertTrue(
            shouldResumePersistedMigrationAfterWorkInfo(
                workInfoState = androidx.work.WorkInfo.State.FAILED,
                requestAutoResume = false,
                journalPhase = ManagedMigrationReplacementJournalPhase.PLANNED
            )
        )
        assertFalse(
            shouldResumePersistedMigrationAfterWorkInfo(
                workInfoState = androidx.work.WorkInfo.State.RUNNING,
                requestAutoResume = true,
                journalPhase = ManagedMigrationReplacementJournalPhase.PLANNED
            )
        )
    }

    @Test
    fun `terminal request with incomplete journal remains recoverable`() {
        val request = ManagedMigrationRequest(
            workId = "terminal-work",
            fromDirectoryUri = "content://source",
            toDirectoryUri = "content://target",
            targetLabel = "target",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 1,
            autoResume = false
        )
        val journal = ManagedMigrationReplacementJournal(
            workId = request.workId,
            fromDirectoryUri = request.fromDirectoryUri,
            toDirectoryUri = request.toDirectoryUri,
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

        assertTrue(shouldBlockStartupForMigrationRecovery(request, journal))
    }

    @Test
    fun `terminal request without a journal remains stopped`() {
        val request = ManagedMigrationRequest(
            workId = "terminal-work",
            fromDirectoryUri = "content://source",
            toDirectoryUri = "content://target",
            targetLabel = "target",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 1,
            autoResume = false
        )

        assertFalse(shouldBlockStartupForMigrationRecovery(request, null))
    }

    @Test
    fun `complete empty source manifest is known after restart`() {
        val journal = ManagedMigrationReplacementJournal(
            workId = "empty-source",
            fromDirectoryUri = "content://source",
            toDirectoryUri = "content://target",
            backupNamespace = "migration",
            phase = ManagedMigrationReplacementJournalPhase.PLANNED,
            replacements = emptyList(),
            sourceEntryCount = 0,
            sourceEntries = emptyList(),
            sourceEntriesComplete = true
        )

        assertTrue(journal.sourceEntryCountKnown)
        assertTrue(
            shouldRetryActiveMigrationJournal(
                phase = journal.phase,
                sourceRootAvailable = true,
                sourceEntriesEmpty = true,
                cleanupReceiptComplete = true,
                sourceEntryCountKnown = journal.sourceEntryCountKnown
            ).not()
        )
    }

    @Test
    fun `active work binding never replaces a different durable request`() {
        val persisted = ManagedMigrationRequest(
            workId = "persisted-work",
            fromDirectoryUri = "content://source",
            toDirectoryUri = "content://target",
            targetLabel = "target",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 1
        )
        val fallback = persisted.copy(workId = "requested-work")

        assertFalse(
            shouldBindMigrationRequestToActiveWork(
                persisted = persisted,
                fallback = fallback,
                activeWorkId = "active-work"
            )
        )
        assertTrue(
            shouldBindMigrationRequestToActiveWork(
                persisted = persisted,
                fallback = fallback,
                activeWorkId = "persisted-work"
            )
        )
    }

    @Test
    fun `active work binding can restore a request when no durable request exists`() {
        val fallback = ManagedMigrationRequest(
            workId = "active-work",
            fromDirectoryUri = null,
            toDirectoryUri = "content://target",
            targetLabel = "target",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 0
        )

        assertTrue(
            shouldBindMigrationRequestToActiveWork(
                persisted = null,
                fallback = fallback,
                activeWorkId = "active-work"
            )
        )
        assertFalse(
            shouldBindMigrationRequestToActiveWork(
                persisted = null,
                fallback = fallback,
                activeWorkId = "other-work"
            )
        )
    }

    @Test
    fun `active work binding accepts uuid case and surrounding whitespace`() {
        val workId = "22222222-2222-2222-2222-222222222222"
        val persisted = ManagedMigrationRequest(
            workId = workId,
            fromDirectoryUri = "content://source",
            toDirectoryUri = "content://target",
            targetLabel = "target",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 0
        )

        assertTrue(
            shouldBindMigrationRequestToActiveWork(
                persisted = persisted,
                fallback = null,
                activeWorkId = "  ${workId.uppercase()}  "
            )
        )
    }

    @Test
    fun `worker input cannot erase durable migration fields when old work omits them`() {
        val persisted = ManagedMigrationRequest(
            workId = "old-work",
            fromDirectoryUri = "content://source/root",
            toDirectoryUri = "content://target/root",
            targetLabel = "target-label",
            releasePreviousPermission = true,
            minimumSourceEntryCount = 27,
            checkpointWorkId = "old-work"
        )
        val input = ManagedMigrationRequest(
            workId = "new-work",
            fromDirectoryUri = null,
            toDirectoryUri = null,
            targetLabel = "",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 0
        )

        val merged = mergeMigrationRequestForWorker(
            persisted = persisted,
            input = input,
            inputKeys = emptySet()
        )

        assertEquals("content://source/root", merged.fromDirectoryUri)
        assertEquals("content://target/root", merged.toDirectoryUri)
        assertEquals("target-label", merged.targetLabel)
        assertTrue(merged.releasePreviousPermission)
        assertEquals(27, merged.minimumSourceEntryCount)
        assertEquals("old-work", merged.checkpointWorkId)
        assertEquals("new-work", merged.workId)
    }

    @Test
    fun `restart checks the old checkpoint before waiting for migration work`() {
        val persisted = ManagedMigrationRequest(
            workId = "new-work",
            fromDirectoryUri = "content://source/root",
            toDirectoryUri = "content://target/root",
            targetLabel = "target",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 1,
            checkpointWorkId = "old-work"
        )
        val journal = ManagedMigrationReplacementJournal(
            workId = "journal-work",
            fromDirectoryUri = persisted.fromDirectoryUri,
            toDirectoryUri = persisted.toDirectoryUri,
            backupNamespace = "migration",
            phase = ManagedMigrationReplacementJournalPhase.TARGETS_VERIFIED,
            replacements = emptyList()
        )

        assertEquals(
            listOf("new-work", "input-work", "old-work", "journal-work"),
            migrationProgressCheckpointIds(
                currentWorkId = "new-work",
                inputCheckpointWorkId = "input-work",
                persistedRequest = persisted,
                persistedJournal = journal
            )
        )
    }

    @Test
    fun `restart keeps the furthest durable checkpoint when a new work id is stale`() {
        val stale = ManagedDownloadStorage.MigrationProgress(
            stage = ManagedDownloadStorage.MigrationStage.COPYING,
            totalFiles = 10,
            processedFiles = 3,
            copiedFiles = 3,
            copiedBytes = 30L,
            totalBytes = 100L,
            metadataFilesProcessed = 0,
            metadataFilesTotal = 0,
            cleanupFilesProcessed = 0,
            cleanupFilesTotal = 0
        )
        val durable = ManagedDownloadStorage.MigrationProgress(
            stage = ManagedDownloadStorage.MigrationStage.VERIFYING,
            totalFiles = 10,
            processedFiles = 10,
            copiedFiles = 10,
            copiedBytes = 100L,
            totalBytes = 100L,
            metadataFilesProcessed = 0,
            metadataFilesTotal = 0,
            cleanupFilesProcessed = 0,
            cleanupFilesTotal = 0,
            verificationFilesProcessed = 2,
            verificationFilesTotal = 10,
            verifiedBytes = 20L,
            verificationBytesTotal = 100L
        )

        val selected = selectMigrationProgressCheckpoint(
            checkpointIds = listOf("new-work", "old-work"),
            readProgress = { checkpointId ->
                when (checkpointId) {
                    "new-work" -> stale
                    "old-work" -> durable
                    else -> null
                }
            }
        )

        assertEquals(durable, selected)
    }

    @Test
    fun `worker rejects a durable request that points at a different root`() {
        val persisted = ManagedMigrationRequest(
            workId = "old-work",
            fromDirectoryUri = "content://source/root",
            toDirectoryUri = "content://target/root",
            targetLabel = "target",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 1
        )
        val input = ManagedMigrationRequest(
            workId = "new-work",
            fromDirectoryUri = "content://source/root",
            toDirectoryUri = "content://other/root",
            targetLabel = "target",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 1
        )

        assertThrows(ManagedDownloadMigrationException::class.java) {
            mergeMigrationRequestForWorker(
                persisted = persisted,
                input = input,
                inputKeys = setOf(
                    ManagedDownloadMigrationWorker.KEY_TO_DIRECTORY_URI
                )
            )
        }
    }

    @Test
    fun `transient provider and IO failures retry within the bounded budget`() {
        assertTrue(
            shouldRetryMigrationFailure(
                error = ManagedDownloadMigrationException.transient("provider unavailable"),
                runAttemptCount = 1,
                maxRetryAttempts = 2
            )
        )
        assertTrue(
            shouldRetryMigrationFailure(
                error = IOException("provider interrupted"),
                runAttemptCount = 0,
                maxRetryAttempts = 2
            )
        )
        assertTrue(
            shouldRetryMigrationFailure(
                error = ManagedDownloadRootProviderException(
                    "content://provider/tree/root",
                    IOException("provider interrupted")
                ),
                runAttemptCount = 0,
                maxRetryAttempts = 2
            )
        )
        assertFalse(
            shouldRetryMigrationFailure(
                error = IOException("provider interrupted"),
                runAttemptCount = 2,
                maxRetryAttempts = 2
            )
        )
        assertFalse(
            shouldRetryMigrationFailure(
                error = ManagedDownloadMigrationException.permanent("permission revoked"),
                runAttemptCount = 0,
                maxRetryAttempts = 2
            )
        )
    }

    @Test
    fun `retryable migration outcomes become terminal after the retry budget`() {
        assertTrue(shouldRetryMigrationAttempt(runAttemptCount = 0, maxRetryAttempts = 2))
        assertTrue(shouldRetryMigrationAttempt(runAttemptCount = 1, maxRetryAttempts = 2))
        assertFalse(shouldRetryMigrationAttempt(runAttemptCount = 2, maxRetryAttempts = 2))
        assertFalse(shouldRetryMigrationAttempt(runAttemptCount = -1, maxRetryAttempts = 2))
        assertFalse(shouldRetryMigrationAttempt(runAttemptCount = 0, maxRetryAttempts = 0))
    }

    @Test
    fun `startup rearms only while the durable retry budget remains`() {
        assertTrue(
            shouldRearmMigrationWorkOnStartup(
                state = androidx.work.WorkInfo.State.ENQUEUED,
                runAttemptCount = 1,
                retryAttemptOffset = 0,
                maxRetryAttempts = 2
            )
        )
        assertFalse(
            shouldRearmMigrationWorkOnStartup(
                state = androidx.work.WorkInfo.State.ENQUEUED,
                runAttemptCount = 0,
                retryAttemptOffset = 0,
                maxRetryAttempts = 2
            )
        )
        assertFalse(
            shouldRearmMigrationWorkOnStartup(
                state = androidx.work.WorkInfo.State.RUNNING,
                runAttemptCount = 2,
                retryAttemptOffset = 0,
                maxRetryAttempts = 2
            )
        )
        assertFalse(
            shouldRearmMigrationWorkOnStartup(
                state = androidx.work.WorkInfo.State.ENQUEUED,
                runAttemptCount = 1,
                retryAttemptOffset = 1,
                maxRetryAttempts = 2
            )
        )
    }

    @Test
    fun `startup replacement retains consumed retry attempts`() {
        assertEquals(1, migrationRetryAttemptCount(0, 1))
        assertEquals(2, migrationRetryAttemptCount(1, 1))
        assertTrue(shouldRetryMigrationAttempt(runAttemptCount = 1, maxRetryAttempts = 2))
        assertFalse(shouldRetryMigrationAttempt(runAttemptCount = 2, maxRetryAttempts = 2))
    }

    @Test
    fun `replaced worker is identified by the new request checkpoint`() {
        val oldWorkId = "11111111-1111-1111-1111-111111111111"
        val newWorkId = "22222222-2222-2222-2222-222222222222"
        val persisted = ManagedMigrationRequest(
            workId = newWorkId,
            fromDirectoryUri = null,
            toDirectoryUri = "content://target",
            targetLabel = "target",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 0,
            checkpointWorkId = oldWorkId
        )

        assertTrue(shouldAbortSupersededMigrationWorker(persisted, oldWorkId))
        assertFalse(shouldAbortSupersededMigrationWorker(persisted, newWorkId))
        assertTrue(
            shouldAbortSupersededMigrationWorker(
                persisted.copy(checkpointWorkId = "first-work"),
                "33333333-3333-3333-3333-333333333333"
            )
        )
        assertFalse(
            shouldAbortSupersededMigrationWorker(
                persisted.copy(checkpointWorkId = "other-work"),
                "legacy-work"
            )
        )
        assertFalse(
            shouldAbortSupersededMigrationWorker(
                persisted.copy(workId = newWorkId.uppercase()),
                newWorkId.lowercase()
            )
        )
    }

    @Test
    fun `active work selection prefers the durable request id`() {
        val old = mock(androidx.work.WorkInfo::class.java)
        val current = mock(androidx.work.WorkInfo::class.java)
        val oldId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val currentId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        `when`(old.id).thenReturn(oldId)
        `when`(current.id).thenReturn(currentId)
        `when`(old.state).thenReturn(androidx.work.WorkInfo.State.RUNNING)
        `when`(current.state).thenReturn(androidx.work.WorkInfo.State.ENQUEUED)

        assertEquals(
            current,
            selectActiveMigrationWorkInfo(listOf(old, current), currentId.toString())
        )
    }

    @Test
    fun `active work selection matches a durable UUID without case or whitespace`() {
        val current = mock(androidx.work.WorkInfo::class.java)
        val currentId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        `when`(current.id).thenReturn(currentId)
        `when`(current.state).thenReturn(androidx.work.WorkInfo.State.ENQUEUED)

        assertEquals(
            current,
            selectActiveMigrationWorkInfo(
                workInfos = listOf(current),
                preferredWorkId = "  ${currentId.toString().uppercase()}  "
            )
        )
        assertTrue(migrationWorkIdsEqual(currentId.toString(), currentId.toString().uppercase()))
        assertTrue(migrationWorkIdsEqual(" Legacy-Work ", "legacy-work"))
        assertFalse(migrationWorkIdsEqual(" ", currentId.toString()))
    }

    @Test
    fun `active work selection falls back to the old checkpoint when the request is missing`() {
        val old = mock(androidx.work.WorkInfo::class.java)
        val oldId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        `when`(old.id).thenReturn(oldId)
        `when`(old.state).thenReturn(androidx.work.WorkInfo.State.RUNNING)

        assertEquals(
            old,
            selectActiveMigrationWorkInfo(
                workInfos = listOf(old),
                preferredWorkId = "22222222-2222-2222-2222-222222222222",
                fallbackWorkId = "  ${oldId.toString().uppercase()}"
            )
        )
    }

    @Test
    fun `active work selection is independent of WorkManager row order`() {
        val first = mock(androidx.work.WorkInfo::class.java)
        val second = mock(androidx.work.WorkInfo::class.java)
        val blocked = mock(androidx.work.WorkInfo::class.java)
        `when`(first.id).thenReturn(UUID.fromString("11111111-1111-1111-1111-111111111111"))
        `when`(second.id).thenReturn(UUID.fromString("22222222-2222-2222-2222-222222222222"))
        `when`(blocked.id).thenReturn(UUID.fromString("33333333-3333-3333-3333-333333333333"))
        `when`(first.state).thenReturn(androidx.work.WorkInfo.State.ENQUEUED)
        `when`(second.state).thenReturn(androidx.work.WorkInfo.State.ENQUEUED)
        `when`(blocked.state).thenReturn(androidx.work.WorkInfo.State.BLOCKED)

        assertEquals(
            first,
            selectActiveMigrationWorkInfo(listOf(blocked, second, first))
        )
        assertEquals(
            first,
            selectActiveMigrationWorkInfo(listOf(first, blocked, second))
        )
    }

    @Test
    fun `committed receipt audio count ignores metadata and sidecars`() {
        val target = ManagedDownloadStorage.StoredEntry(
            name = "track.mp3",
            reference = "content://target/track",
            mediaUri = "content://target/track",
            localFilePath = null,
            sizeBytes = 1L,
            lastModifiedMs = 1L
        )
        val journal = ManagedMigrationReplacementJournal(
            workId = "receipt-count",
            fromDirectoryUri = null,
            toDirectoryUri = "content://target",
            backupNamespace = "migration",
            phase = ManagedMigrationReplacementJournalPhase.DIRECTORY_COMMITTED,
            replacements = emptyList(),
            cleanupReceipts = listOf(
                ManagedMigrationCleanupReceipt(
                    sourceReference = "content://source/audio",
                    sourceName = "track.mp3",
                    sourceSubdirectory = null,
                    targetEntry = target,
                    targetDigest = "a".repeat(64)
                ),
                ManagedMigrationCleanupReceipt(
                    sourceReference = "content://source/metadata",
                    sourceName = "track.mp3.npmeta.json",
                    sourceSubdirectory = null,
                    targetEntry = target.copy(name = "track.mp3.npmeta.json"),
                    targetDigest = "b".repeat(64)
                ),
                ManagedMigrationCleanupReceipt(
                    sourceReference = "content://source/cover",
                    sourceName = "cover.jpg",
                    sourceSubdirectory = "Covers",
                    targetEntry = target.copy(name = "cover.jpg"),
                    targetDigest = "c".repeat(64)
                )
            )
        )

        assertEquals(1, committedMigrationAudioReceiptCount(journal))
        assertTrue(committedMigrationReceiptsMeetAudioMinimum(journal, 1))
        assertFalse(committedMigrationReceiptsMeetAudioMinimum(journal, 2))
    }

    @Test
    fun `target digest fingerprint fast path requires stable size and timestamp`() {
        assertTrue(canReuseMigrationTargetDigest(10L, 10L, 20L, 20L))
        assertFalse(canReuseMigrationTargetDigest(10L, 11L, 20L, 20L))
        assertFalse(canReuseMigrationTargetDigest(10L, 10L, 0L, 20L))
    }

    @Test
    fun `final scan must publish before migration can complete`() {
        assertFalse(
            shouldRetryAfterMigrationFinalScan(
                ManagedLibraryRefreshOutcome.Published(
                    rootKey = "content://provider/tree/target",
                    songCount = 1_000
                )
            )
        )
        assertTrue(
            shouldRetryAfterMigrationFinalScan(
                ManagedLibraryRefreshOutcome.Preserved(
                    ManagedLibraryRefreshPreserveReason.INCOMPLETE_ROOT_ENUMERATION
                )
            )
        )
        assertTrue(
            shouldRetryAfterMigrationFinalScan(
                ManagedLibraryRefreshOutcome.Failed("provider unavailable")
            )
        )
    }

    @Test
    fun `first progress is published immediately`() {
        val progress = progress(0.1f)

        assertTrue(
            shouldPublishMigrationProgress(
                progress = progress,
                nowMs = 0L,
                state = MigrationProgressThrottleState(),
                minIntervalMs = 750L,
                percentDelta = 1
            )
        )
    }

    @Test
    fun `same stage and percent are throttled`() {
        val progress = progress(0.10f)
        val state = updateMigrationProgressThrottleState(progress, nowMs = 1_000L)

        assertFalse(
            shouldPublishMigrationProgress(
                progress = progress.copy(currentFileName = "next.mp3"),
                nowMs = 1_100L,
                state = state,
                minIntervalMs = 750L,
                percentDelta = 1
            )
        )
    }

    @Test
    fun `stage change is published without waiting for interval`() {
        val state = updateMigrationProgressThrottleState(progress(0.10f), nowMs = 1_000L)

        assertTrue(
            shouldPublishMigrationProgress(
                progress = progress(
                    fraction = 0.10f,
                    stage = ManagedDownloadStorage.MigrationStage.REWRITING_METADATA
                ),
                nowMs = 1_100L,
                state = state,
                minIntervalMs = 750L,
                percentDelta = 1
            )
        )
    }

    @Test
    fun `one percent change is published as a useful persisted update`() {
        val state = updateMigrationProgressThrottleState(progress(0.10f), nowMs = 1_000L)

        assertTrue(
            shouldPublishMigrationProgress(
                progress = progress(0.11f),
                nowMs = 1_100L,
                state = state,
                minIntervalMs = 750L,
                percentDelta = 1
            )
        )
    }

    @Test
    fun `work data round trip preserves every migration progress counter`() {
        ManagedDownloadStorage.MigrationStage.entries.forEach { stage ->
            val progress = ManagedDownloadStorage.MigrationProgress(
                stage = stage,
                totalFiles = 41,
                processedFiles = 23,
                copiedFiles = 19,
                copiedBytes = 8_765_432_109L,
                totalBytes = 9_876_543_210L,
                metadataFilesProcessed = 3,
                metadataFilesTotal = 7,
                cleanupFilesProcessed = 5,
                cleanupFilesTotal = 11,
                currentFileName = "slow-saf-track.mp3",
                verificationFilesProcessed = 13,
                verificationFilesTotal = 17,
                verifiedBytes = 6_543_210_987L,
                verificationBytesTotal = 7_654_321_098L
            )

            assertEquals(
                progress,
                migrationProgressFromWorkData(migrationProgressToWorkData(progress))
            )
        }
        listOf<String?>(null, "").forEach { currentFileName ->
            val progress = progress(0.5f).copy(currentFileName = currentFileName)
            assertEquals(
                progress,
                migrationProgressFromWorkData(migrationProgressToWorkData(progress))
            )
        }
    }

    @Test
    fun `legacy work data remains readable without detailed counters`() {
        val legacyData = workDataOf(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_STAGE to
                ManagedDownloadStorage.MigrationStage.CLEANING_UP.name,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_FRACTION to 0.97f,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_PROCESSED_FILES to 8,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_TOTAL_FILES to 10,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_CURRENT_FILE to "legacy.mp3"
        )

        val restored = migrationProgressFromWorkData(legacyData)

        assertEquals(ManagedDownloadStorage.MigrationStage.CLEANING_UP, restored?.stage)
        assertEquals(8, restored?.processedFiles)
        assertEquals(10, restored?.cleanupFilesTotal)
        assertEquals(5, restored?.cleanupFilesProcessed)
        assertEquals("legacy.mp3", restored?.currentFileName)
    }

    @Test
    fun `verifying work data without detailed counters uses safe legacy defaults`() {
        val legacyData = workDataOf(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_STAGE to
                ManagedDownloadStorage.MigrationStage.VERIFYING.name,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_FRACTION to 0.945f,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_PROCESSED_FILES to 10,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_TOTAL_FILES to 10,
            ManagedDownloadMigrationWorker.KEY_PROGRESS_CURRENT_FILE to "verify.mp3"
        )

        val restored = migrationProgressFromWorkData(legacyData)

        assertEquals(ManagedDownloadStorage.MigrationStage.VERIFYING, restored?.stage)
        assertEquals(5, restored?.verificationFilesProcessed)
        assertEquals(10, restored?.verificationFilesTotal)
        assertEquals(0L, restored?.verifiedBytes)
        assertEquals(0L, restored?.verificationBytesTotal)
    }

    @Test
    fun `unknown persisted migration stage is ignored`() {
        val data = workDataOf(
            ManagedDownloadMigrationWorker.KEY_PROGRESS_STAGE to "REMOVED_STAGE"
        )

        assertNull(migrationProgressFromWorkData(data))
    }

    @Test
    fun `discovered source audio count overrides an empty UI lower bound`() {
        assertEquals(7, resolveMinimumMigrationAudioCount(0, 7))
        assertEquals(9, resolveMinimumMigrationAudioCount(9, 7))
        assertEquals(0, resolveMinimumMigrationAudioCount(-1, -1))
    }

    @Test
    fun `confirmed source deletion lowers the persisted audio minimum`() {
        assertEquals(8, resolveMinimumMigrationAudioCount(9, 7, 1))
        assertEquals(0, resolveMinimumMigrationAudioCount(9, 7, 12))
        assertEquals(9, resolveMinimumMigrationAudioCount(9, 7, -1))
    }

    @Test
    fun `reused target advances copied bytes by the verified source size`() {
        val updates = mutableListOf<ManagedDownloadStorage.MigrationProgress>()
        val tracker = ManagedDownloadMigrationProgressTracker(
            totalFiles = 1,
            totalBytes = 4_096L,
            metadataFilesTotal = 0,
            onProgress = updates::add
        )
        val reusedEntry = ManagedMigrationProgressEntry(
            reference = "content://provider/source/audio",
            name = "audio.mp3",
            sizeBytes = 4_096L
        )

        tracker.startCopy(reusedEntry)
        tracker.completeCopy(reusedEntry)
        tracker.finishAll()

        assertEquals(1, updates.last().copiedFiles)
        assertEquals(4_096L, updates.last().copiedBytes)
        assertEquals(1f, updates.last().fraction)
    }

    @Test
    fun `verification byte progress stays monotonic through cleanup`() {
        val updates = mutableListOf<ManagedDownloadStorage.MigrationProgress>()
        var nowMs = 0L
        val tracker = ManagedDownloadMigrationProgressTracker(
            totalFiles = 1,
            totalBytes = 100L,
            metadataFilesTotal = 1,
            onProgress = updates::add,
            nowMs = { nowMs }
        )
        val copyEntry = ManagedMigrationProgressEntry("source", "audio.mp3", 100L)
        val verificationEntry = ManagedMigrationProgressEntry("target", "audio.mp3", 200L)

        fun advance(action: () -> Unit) {
            nowMs += 300L
            action()
        }

        tracker.startPreparing(copyEntry.name)
        advance { tracker.startCopy(copyEntry) }
        advance { tracker.onCopyProgress(copyEntry, 50L) }
        advance { tracker.onCopyProgress(copyEntry, 20L) }
        advance { tracker.completeCopy(copyEntry) }
        advance { tracker.startRewrite(copyEntry.name) }
        advance { tracker.finishRewrite(copyEntry.name) }
        advance { tracker.startVerification(listOf(verificationEntry)) }
        advance { tracker.startVerificationEntry(verificationEntry) }
        advance { tracker.onVerificationProgress(verificationEntry, 50L) }
        advance { tracker.onVerificationProgress(verificationEntry, 20L) }
        advance { tracker.onVerificationProgress(verificationEntry, 150L) }
        advance { tracker.finishVerification(verificationEntry) }
        advance { tracker.startCleanup(totalEntries = 1, fileName = copyEntry.name) }
        advance { tracker.finishCleanup(copyEntry.name) }
        advance { tracker.finishAll() }

        assertTrue(updates.zipWithNext().all { (before, after) ->
            after.fraction >= before.fraction
        })
        val verificationProgress = updates.last { progress ->
            progress.stage == ManagedDownloadStorage.MigrationStage.VERIFYING
        }
        assertEquals(1, verificationProgress.verificationFilesProcessed)
        assertEquals(1, verificationProgress.verificationFilesTotal)
        assertEquals(200L, verificationProgress.verifiedBytes)
        assertEquals(200L, verificationProgress.verificationBytesTotal)
    }

    @Test
    fun `resume floor keeps preflight reset visible until the worker catches up`() {
        val floor = ManagedDownloadStorage.MigrationProgress(
            stage = ManagedDownloadStorage.MigrationStage.CLEANING_UP,
            totalFiles = 100,
            processedFiles = 96,
            copiedFiles = 100,
            copiedBytes = 10_000L,
            totalBytes = 10_000L,
            metadataFilesProcessed = 20,
            metadataFilesTotal = 20,
            cleanupFilesProcessed = 4,
            cleanupFilesTotal = 10,
            currentFileName = "track-96.mp3"
        )
        val preparing = floor.copy(
            stage = ManagedDownloadStorage.MigrationStage.PREPARING,
            processedFiles = 0,
            copiedFiles = 0,
            copiedBytes = 0L,
            currentFileName = "track-1.mp3"
        )

        val visible = mergeMigrationProgressFloor(floor, preparing)

        assertEquals(ManagedDownloadStorage.MigrationStage.CLEANING_UP, visible.stage)
        assertEquals(96, visible.processedFiles)
        assertEquals(4, visible.cleanupFilesProcessed)
        assertEquals("track-96.mp3", visible.currentFileName)
    }

    @Test
    fun `resume floor yields current stage after retry catches up`() {
        val floor = progress(0.5f).copy(
            stage = ManagedDownloadStorage.MigrationStage.COPYING,
            totalFiles = 10,
            processedFiles = 5,
            copiedFiles = 5,
            copiedBytes = 500L,
            totalBytes = 1_000L
        )
        val caughtUp = floor.copy(
            processedFiles = 6,
            copiedFiles = 6,
            copiedBytes = 600L,
            currentFileName = "next.mp3"
        )

        val visible = mergeMigrationProgressFloor(floor, caughtUp)

        assertEquals(caughtUp, visible)
    }

    @Test
    fun `transient cleanup failure remains durable work instead of succeeding`() {
        assertEquals(
            MigrationCleanupWorkDecision.RETRY,
            migrationCleanupWorkDecision(
                ManagedDownloadStorage.MigrationResult(
                    movedFiles = 4,
                    skippedFiles = 0,
                    cleanupFailedFiles = 1,
                    cleanupRetryableFailedFiles = 1
                )
            )
        )
        assertEquals(
            MigrationCleanupWorkDecision.FAILURE,
            migrationCleanupWorkDecision(
                ManagedDownloadStorage.MigrationResult(
                    movedFiles = 4,
                    skippedFiles = 0,
                    cleanupFailedFiles = 1,
                    cleanupRetryableFailedFiles = 0
                )
            )
        )
        assertEquals(
            MigrationCleanupWorkDecision.COMPLETE,
            migrationCleanupWorkDecision(
                ManagedDownloadStorage.MigrationResult(
                    movedFiles = 4,
                    skippedFiles = 0
                )
            )
        )
    }

    private fun progress(
        fraction: Float,
        stage: ManagedDownloadStorage.MigrationStage =
            ManagedDownloadStorage.MigrationStage.COPYING
    ): ManagedDownloadStorage.MigrationProgress {
        return ManagedDownloadStorage.MigrationProgress(
            stage = stage,
            totalFiles = 100,
            processedFiles = (fraction * 100).toInt(),
            copiedFiles = (fraction * 100).toInt(),
            copiedBytes = 0L,
            totalBytes = 0L,
            metadataFilesProcessed = 0,
            metadataFilesTotal = 0,
            cleanupFilesProcessed = 0,
            cleanupFilesTotal = 0
        )
    }
}
