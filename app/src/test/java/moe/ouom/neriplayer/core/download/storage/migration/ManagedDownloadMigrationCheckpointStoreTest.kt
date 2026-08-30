package moe.ouom.neriplayer.core.download.storage.migration

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.json.JSONObject

class ManagedDownloadMigrationCheckpointStoreTest {
    @Test
    fun `record keeps the largest discovered audio lower bound`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val key = "${ManagedDownloadMigrationCheckpointStore.KEY_PREFIX}work-1"
        `when`(preferences.getInt(key, 0)).thenReturn(3)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putInt(key, 8)).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)

        val resolved = store.recordMinimumAudioCount("work-1", 8)

        assertEquals(8, resolved)
        verify(editor).putInt(key, 8)
        verify(editor).commit()
    }

    @Test
    fun `record never lowers a persisted audio lower bound`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val key = "${ManagedDownloadMigrationCheckpointStore.KEY_PREFIX}work-2"
        `when`(preferences.getInt(key, 0)).thenReturn(9)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putInt(key, 9)).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)

        assertEquals(9, store.recordMinimumAudioCount("work-2", 2))
        verify(editor).putInt(key, 9)
    }

    @Test
    fun `failed durable checkpoint blocks migration as transient`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val key = "${ManagedDownloadMigrationCheckpointStore.KEY_PREFIX}work-3"
        `when`(preferences.getInt(key, 0)).thenReturn(0)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putInt(key, 4)).thenReturn(editor)
        `when`(editor.commit()).thenReturn(false)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)

        val failure = assertThrows(ManagedDownloadMigrationException::class.java) {
            store.recordMinimumAudioCount("work-3", 4)
        }

        assertTrue(failure.retryable)
    }

    @Test
    fun `target name plan round trips for process death recovery`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val key = "${ManagedDownloadMigrationCheckpointStore.TARGET_NAMES_KEY_PREFIX}work-plan"
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putString(eq(key), anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        val targetNames = linkedMapOf(
            "content://source/audio" to "audio.mp3",
            "content://source/cover" to "cover.jpg"
        )

        store.recordTargetNames("work-plan", targetNames)

        val payloadCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(editor).putString(eq(key), payloadCaptor.capture())
        `when`(preferences.getString(key, null)).thenReturn(payloadCaptor.value)
        assertEquals(targetNames, store.readTargetNames("work-plan"))
    }

    @Test
    fun `progress checkpoint round trips every stage counter`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val key = "${ManagedDownloadMigrationCheckpointStore.PROGRESS_KEY_PREFIX}work-progress"
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putString(eq(key), anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        val progress = moe.ouom.neriplayer.core.download.ManagedDownloadStorage.MigrationProgress(
            stage = moe.ouom.neriplayer.core.download.ManagedDownloadStorage.MigrationStage.CLEANING_UP,
            totalFiles = 42,
            processedFiles = 39,
            copiedFiles = 42,
            copiedBytes = 4_096L,
            totalBytes = 8_192L,
            metadataFilesProcessed = 3,
            metadataFilesTotal = 3,
            cleanupFilesProcessed = 7,
            cleanupFilesTotal = 42,
            currentFileName = "track-7.mp3",
            verificationFilesProcessed = 42,
            verificationFilesTotal = 42,
            verifiedBytes = 8_192L,
            verificationBytesTotal = 8_192L
        )

        store.recordProgress("work-progress", progress)
        val payloadCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(editor).putString(eq(key), payloadCaptor.capture())
        `when`(preferences.getString(key, null)).thenReturn(payloadCaptor.value)

        assertEquals(progress, store.readProgress("work-progress"))
    }

    @Test
    fun `malformed optional progress checkpoint does not block migration recovery`() {
        val preferences = mock(SharedPreferences::class.java)
        val key = "${ManagedDownloadMigrationCheckpointStore.PROGRESS_KEY_PREFIX}bad"
        `when`(preferences.getString(key, null)).thenReturn("{\"stage\":\"REMOVED\"}")
        val store = ManagedDownloadMigrationCheckpointStore(preferences)

        assertEquals(null, store.readProgress("bad"))
    }

    @Test
    fun `copy receipt round trips and can be cleared independently`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(editor.remove(anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        val target = moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry(
            name = "audio.mp3",
            reference = "content://target/audio",
            mediaUri = "content://target/audio",
            localFilePath = null,
            sizeBytes = 12L,
            lastModifiedMs = 9L
        )
        val receipt = ManagedMigrationCopyReceipt(
            sourceReference = "content://source/audio",
            sourceName = "audio.mp3",
            sourceSubdirectory = null,
            sourceSizeBytes = 12L,
            sourceLastModifiedMs = 8L,
            targetEntry = target,
            sourceDigest = "a".repeat(64),
            verifiedTargetDigest = "a".repeat(64),
            createdNew = true,
            sourceAuthoritative = true,
            replacementBackup = null,
            sourceLogicalCreatedAtMs = 1_234L,
            sourceCreatedAtSource = "FILESYSTEM_BIRTH",
            sourceCreatedAtConfidence = "PROVIDER_REPORTED"
        )

        store.recordCopyReceipt("work-copy", receipt)

        val keyCaptor = ArgumentCaptor.forClass(String::class.java)
        val payloadCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(editor, times(2)).putString(keyCaptor.capture(), payloadCaptor.capture())
        val writes = keyCaptor.allValues.zip(payloadCaptor.allValues).toMap()
        val receiptKey = writes.keys.single { key ->
            key.startsWith(ManagedDownloadMigrationCheckpointStore.COPY_RECEIPT_KEY_PREFIX)
        }
        val indexKey =
            "${ManagedDownloadMigrationCheckpointStore.COPY_RECEIPT_INDEX_KEY_PREFIX}work-copy"
        assertTrue(indexKey in writes)
        `when`(preferences.getString(receiptKey, null)).thenReturn(writes.getValue(receiptKey))
        assertEquals(receipt, store.readCopyReceipt("work-copy", receipt.sourceReference))

        clearInvocations(editor)
        assertTrue(store.clearCopyReceipt("work-copy", receipt.sourceReference))
        verify(editor).remove(receiptKey)
        verify(editor).putString(eq(indexKey), eq("[]"))
        verify(editor, times(1)).commit()
    }

    @Test
    fun `indexed copy receipts are read without scanning all preferences`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val indexKey =
            "${ManagedDownloadMigrationCheckpointStore.COPY_RECEIPT_INDEX_KEY_PREFIX}work-indexed"
        `when`(preferences.edit()).thenReturn(editor)
        `when`(preferences.getString(indexKey, null)).thenReturn("[]")
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        val receipt = copyReceiptFixture("content://source/indexed")

        store.recordCopyReceipt("work-indexed", receipt)

        val keyCaptor = ArgumentCaptor.forClass(String::class.java)
        val payloadCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(editor, times(2)).putString(keyCaptor.capture(), payloadCaptor.capture())
        val writes = keyCaptor.allValues.zip(payloadCaptor.allValues).toMap()
        val receiptKey = writes.keys.single { key ->
            key.startsWith(ManagedDownloadMigrationCheckpointStore.COPY_RECEIPT_KEY_PREFIX)
        }
        val indexPayload = writes.getValue(indexKey)
        clearInvocations(preferences)
        `when`(preferences.getString(indexKey, null)).thenReturn(indexPayload)
        `when`(preferences.getString(receiptKey, null)).thenReturn(writes.getValue(receiptKey))

        assertEquals(listOf(receipt), store.readCopyReceipts("work-indexed"))
        verify(preferences, never()).all
    }

    @Test
    fun `empty copy receipt index is read without scanning all preferences`() {
        val preferences = mock(SharedPreferences::class.java)
        val indexKey =
            "${ManagedDownloadMigrationCheckpointStore.COPY_RECEIPT_INDEX_KEY_PREFIX}work-empty"
        `when`(preferences.getString(indexKey, null)).thenReturn("[]")
        val store = ManagedDownloadMigrationCheckpointStore(preferences)

        assertTrue(store.readCopyReceipts("work-empty").isEmpty())
        verify(preferences, never()).all
    }

    @Test
    fun `copy receipts without or with a malformed index fall back to legacy scanning`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val indexKey =
            "${ManagedDownloadMigrationCheckpointStore.COPY_RECEIPT_INDEX_KEY_PREFIX}work-legacy"
        `when`(preferences.edit()).thenReturn(editor)
        `when`(preferences.all).thenReturn(emptyMap())
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        val receipt = copyReceiptFixture("content://source/legacy")

        store.recordCopyReceipt("work-legacy", receipt)

        val keyCaptor = ArgumentCaptor.forClass(String::class.java)
        val payloadCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(editor, times(2)).putString(keyCaptor.capture(), payloadCaptor.capture())
        val writes = keyCaptor.allValues.zip(payloadCaptor.allValues).toMap()
        val receiptKey = writes.keys.single { key ->
            key.startsWith(ManagedDownloadMigrationCheckpointStore.COPY_RECEIPT_KEY_PREFIX)
        }
        val receiptPayload = writes.getValue(receiptKey)
        clearInvocations(preferences)
        `when`(preferences.getString(indexKey, null)).thenReturn(null, "not-json")
        `when`(preferences.all).thenReturn(mapOf(receiptKey to receiptPayload))
        `when`(preferences.getString(receiptKey, null)).thenReturn(receiptPayload)

        assertEquals(listOf(receipt), store.readCopyReceipts("work-legacy"))
        assertEquals(listOf(receipt), store.readCopyReceipts("work-legacy"))
    }

    @Test
    fun `progress checkpoint never regresses after a retry preflight`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val key = "${ManagedDownloadMigrationCheckpointStore.PROGRESS_KEY_PREFIX}retry"
        val durable = JSONObject().apply {
            put("version", ManagedDownloadMigrationCheckpointStore.CURRENT_MIGRATION_PROGRESS_VERSION)
            put("stage", moe.ouom.neriplayer.core.download.ManagedDownloadStorage.MigrationStage.CLEANING_UP.name)
            put("totalFiles", 100)
            put("processedFiles", 96)
            put("copiedFiles", 100)
            put("copiedBytes", 10_000L)
            put("totalBytes", 10_000L)
            put("metadataFilesProcessed", 20)
            put("metadataFilesTotal", 20)
            put("cleanupFilesProcessed", 4)
            put("cleanupFilesTotal", 10)
            put("currentFileName", "track-96.mp3")
        }.toString()
        `when`(preferences.getString(key, null)).thenReturn(durable)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putString(eq(key), anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        val preflight = moe.ouom.neriplayer.core.download.ManagedDownloadStorage.MigrationProgress(
            stage = moe.ouom.neriplayer.core.download.ManagedDownloadStorage.MigrationStage.PREPARING,
            totalFiles = 100,
            processedFiles = 0,
            copiedFiles = 0,
            copiedBytes = 0L,
            totalBytes = 10_000L,
            metadataFilesProcessed = 0,
            metadataFilesTotal = 20,
            cleanupFilesProcessed = 0,
            cleanupFilesTotal = 10,
            currentFileName = "track-1.mp3"
        )

        val recorded = store.recordProgress("retry", preflight)

        assertEquals(
            moe.ouom.neriplayer.core.download.ManagedDownloadStorage.MigrationStage.CLEANING_UP,
            recorded.stage
        )
        assertEquals(96, recorded.processedFiles)
        assertEquals(4, recorded.cleanupFilesProcessed)
        assertEquals("track-96.mp3", recorded.currentFileName)
    }

    @Test
    fun `clear removes only the completed work checkpoint`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val key = "${ManagedDownloadMigrationCheckpointStore.KEY_PREFIX}work-4"
        val targetNamesKey =
            "${ManagedDownloadMigrationCheckpointStore.TARGET_NAMES_KEY_PREFIX}work-4"
        val progressKey =
            "${ManagedDownloadMigrationCheckpointStore.PROGRESS_KEY_PREFIX}work-4"
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.remove(key)).thenReturn(editor)
        `when`(editor.remove(targetNamesKey)).thenReturn(editor)
        `when`(editor.remove(progressKey)).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)

        assertTrue(store.clear("work-4"))
        verify(editor).remove(key)
        verify(editor).remove(targetNamesKey)
        verify(editor).remove(progressKey)
        verify(editor).commit()
    }

    @Test
    fun `replacement journal round trips deterministic backup and phase`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(
            editor.putString(
                eq(ManagedDownloadMigrationCheckpointStore.ACTIVE_REPLACEMENT_JOURNAL_KEY),
                anyString()
            )
        ).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        val target = moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry(
            name = "track.mp3",
            reference = "content://target/track",
            mediaUri = "content://target/track",
            localFilePath = null,
            sizeBytes = 10L,
            lastModifiedMs = 2L
        )
        val plan = ManagedMigrationReplacementPlan(
            sourceReference = "content://source/track",
            groupIdentity = "stableKey:song-1",
            subdirectory = null,
            targetName = target.name,
            targetEntry = target,
            backupName = ".np-migration-backup-abc"
        )
        val journal = ManagedMigrationReplacementJournal(
            workId = "work-journal",
            fromDirectoryUri = "content://source/root",
            toDirectoryUri = "content://target/root",
            backupNamespace = "migration",
            phase = ManagedMigrationReplacementJournalPhase.TARGETS_VERIFIED,
            replacements = listOf(plan),
            cleanupReceipts = listOf(
                ManagedMigrationCleanupReceipt(
                    sourceReference = "content://source/track",
                    sourceName = "track.mp3",
                    sourceSubdirectory = null,
                    targetEntry = target,
                    targetDigest = "a".repeat(64)
                )
            ),
            cleanupComplete = true,
            sourceEntryCount = 1,
            deletedSourceAudioCount = 2,
            sourceEntries = listOf(
                ManagedMigrationSourceEntry(
                    sourceReference = "content://source/track",
                    sourceName = "track.mp3",
                    sourceSubdirectory = null,
                    sizeBytes = 12L,
                    lastModifiedMs = 1L
                )
            )
        )

        store.recordReplacementJournal(journal)
        val payloadCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(editor).putString(
            eq(ManagedDownloadMigrationCheckpointStore.ACTIVE_REPLACEMENT_JOURNAL_KEY),
            payloadCaptor.capture()
        )
        `when`(
            preferences.getString(
                ManagedDownloadMigrationCheckpointStore.ACTIVE_REPLACEMENT_JOURNAL_KEY,
                null
            )
        ).thenReturn(payloadCaptor.value)

        assertEquals(journal, store.readReplacementJournal())
    }

    @Test
    fun `ordinary migration journal may have no replacement plans`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(
            editor.putString(
                eq(ManagedDownloadMigrationCheckpointStore.ACTIVE_REPLACEMENT_JOURNAL_KEY),
                anyString()
            )
        ).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        val journal = ManagedMigrationReplacementJournal(
            workId = "work-ordinary",
            fromDirectoryUri = "content://source/root",
            toDirectoryUri = "content://target/root",
            backupNamespace = "migration",
            phase = ManagedMigrationReplacementJournalPhase.PLANNED,
            replacements = emptyList(),
            sourceEntryCount = 1,
            sourceEntries = listOf(
                ManagedMigrationSourceEntry(
                    sourceReference = "content://source/track",
                    sourceName = "track.mp3",
                    sourceSubdirectory = null,
                    sizeBytes = 12L,
                    lastModifiedMs = 1L
                )
            )
        )

        store.recordReplacementJournal(journal)
        val payloadCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(editor).putString(
            eq(ManagedDownloadMigrationCheckpointStore.ACTIVE_REPLACEMENT_JOURNAL_KEY),
            payloadCaptor.capture()
        )
        `when`(
            preferences.getString(
                ManagedDownloadMigrationCheckpointStore.ACTIVE_REPLACEMENT_JOURNAL_KEY,
                null
            )
        ).thenReturn(payloadCaptor.value)

        assertEquals(journal, store.readReplacementJournal())
    }

    @Test
    fun `empty complete source manifest survives serialization`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(
            editor.putString(
                eq(ManagedDownloadMigrationCheckpointStore.ACTIVE_REPLACEMENT_JOURNAL_KEY),
                anyString()
            )
        ).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        val journal = ManagedMigrationReplacementJournal(
            workId = "work-empty",
            fromDirectoryUri = "content://source/root",
            toDirectoryUri = "content://target/root",
            backupNamespace = "migration",
            phase = ManagedMigrationReplacementJournalPhase.PLANNED,
            replacements = emptyList(),
            sourceEntryCount = 0,
            sourceEntries = emptyList(),
            sourceEntriesComplete = true
        )

        store.recordReplacementJournal(journal)
        val payloadCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(editor).putString(
            eq(ManagedDownloadMigrationCheckpointStore.ACTIVE_REPLACEMENT_JOURNAL_KEY),
            payloadCaptor.capture()
        )
        `when`(
            preferences.getString(
                ManagedDownloadMigrationCheckpointStore.ACTIVE_REPLACEMENT_JOURNAL_KEY,
                null
            )
        ).thenReturn(payloadCaptor.value)

        assertEquals(journal, store.readReplacementJournal())
        assertTrue(requireNotNull(store.readReplacementJournal()).sourceEntryCountKnown)
    }

    @Test
    fun `old checkpoint without replacement journal remains readable`() {
        val preferences = mock(SharedPreferences::class.java)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        `when`(
            preferences.getString(
                ManagedDownloadMigrationCheckpointStore.ACTIVE_REPLACEMENT_JOURNAL_KEY,
                null
            )
        ).thenReturn(null)

        assertEquals(null, store.readReplacementJournal())
    }

    @Test
    fun `durable migration request round trips every input`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(
            editor.putString(
                eq(ManagedDownloadMigrationCheckpointStore.ACTIVE_REQUEST_KEY),
                anyString()
            )
        ).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        val request = ManagedMigrationRequest(
            workId = "work-request",
            fromDirectoryUri = "content://source/root",
            toDirectoryUri = "content://target/root",
            targetLabel = "target",
            releasePreviousPermission = true,
            minimumSourceEntryCount = 42,
            checkpointWorkId = "old-work",
            autoResume = true,
            retryAttemptOffset = 1
        )

        store.recordRequest(request)

        val payloadCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(editor).putString(
            eq(ManagedDownloadMigrationCheckpointStore.ACTIVE_REQUEST_KEY),
            payloadCaptor.capture()
        )
        `when`(
            preferences.getString(
                ManagedDownloadMigrationCheckpointStore.ACTIVE_REQUEST_KEY,
                null
            )
        ).thenReturn(payloadCaptor.value)
        assertEquals(request, store.readRequest())
    }

    @Test
    fun `conditional request write refuses a stale worker`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val key = ManagedDownloadMigrationCheckpointStore.ACTIVE_REQUEST_KEY
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putString(eq(key), anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        val current = ManagedMigrationRequest(
            workId = "new-work",
            fromDirectoryUri = null,
            toDirectoryUri = "content://target",
            targetLabel = "target",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 0
        )
        store.recordRequest(current)
        val payloadCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(editor).putString(eq(key), payloadCaptor.capture())
        clearInvocations(editor)
        `when`(preferences.getString(key, null)).thenReturn(payloadCaptor.value)

        assertEquals(
            false,
            store.recordRequestIfCurrent(
                expectedWorkId = "old-work",
                request = current.copy(workId = "old-work")
            )
        )
        verify(editor, never()).putString(eq(key), anyString())
    }

    @Test
    fun `stale worker cannot resurrect migration checkpoints`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val requestKey = ManagedDownloadMigrationCheckpointStore.ACTIVE_REQUEST_KEY
        val activeRequest = JSONObject().apply {
            put("version", ManagedDownloadMigrationCheckpointStore.CURRENT_MIGRATION_REQUEST_VERSION)
            put("workId", "new-work")
            put("targetLabel", "target")
            put("releasePreviousPermission", false)
            put("minimumSourceEntryCount", 1)
            put("autoResume", true)
        }.toString()
        `when`(preferences.getString(requestKey, null)).thenReturn(activeRequest)
        `when`(preferences.edit()).thenReturn(editor)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        val progress = moe.ouom.neriplayer.core.download.ManagedDownloadStorage.MigrationProgress(
            stage = moe.ouom.neriplayer.core.download.ManagedDownloadStorage.MigrationStage.COPYING,
            totalFiles = 1,
            processedFiles = 1,
            copiedFiles = 1,
            copiedBytes = 1L,
            totalBytes = 1L,
            metadataFilesProcessed = 0,
            metadataFilesTotal = 0,
            cleanupFilesProcessed = 0,
            cleanupFilesTotal = 0
        )

        assertNull(store.recordProgressIfCurrent("old-work", "old-work", progress))
        assertNull(
            store.recordMinimumAudioCountIfCurrent(
                ownerWorkId = "old-work",
                workId = "old-work",
                minimumAudioCount = 2
            )
        )
        assertNull(
            store.recordTargetNamesIfCurrent(
                ownerWorkId = "old-work",
                workId = "old-work",
                targetNames = mapOf("source" to "track.mp3")
            )
        )
        assertNull(
            store.recordCopyReceiptsIfCurrent(
                ownerWorkId = "old-work",
                workId = "old-work",
                receipts = listOf(copyReceiptFixture("content://source/stale"))
            )
        )
        assertNull(
            store.clearCopyReceiptIfCurrent(
                ownerWorkId = "old-work",
                workId = "old-work",
                sourceReference = "content://source/stale"
            )
        )
        assertNull(
            store.recordReplacementJournalIfCurrent(
                ownerWorkId = "old-work",
                journal = ManagedMigrationReplacementJournal(
                    workId = "old-work",
                    fromDirectoryUri = "content://source",
                    toDirectoryUri = "content://target",
                    backupNamespace = "old-work",
                    phase = ManagedMigrationReplacementJournalPhase.PLANNED,
                    replacements = emptyList()
                )
            )
        )
        verify(editor, never()).commit()
    }

    @Test
    fun `clearing an old checkpoint does not clear a replacement request`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val requestKey = ManagedDownloadMigrationCheckpointStore.ACTIVE_REQUEST_KEY
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putString(eq(requestKey), anyString())).thenReturn(editor)
        `when`(editor.remove(anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        `when`(preferences.all).thenReturn(emptyMap())
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        val request = ManagedMigrationRequest(
            workId = "new-work",
            fromDirectoryUri = null,
            toDirectoryUri = "content://target",
            targetLabel = "target",
            releasePreviousPermission = false,
            minimumSourceEntryCount = 0,
            checkpointWorkId = "old-work"
        )
        store.recordRequest(request)
        val payloadCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(editor).putString(eq(requestKey), payloadCaptor.capture())
        clearInvocations(editor)
        `when`(preferences.getString(requestKey, null)).thenReturn(payloadCaptor.value)

        assertTrue(store.clearCompleted(listOf("old-work")))
        verify(editor, never()).remove(eq(requestKey))
    }

    @Test
    fun `owner aware completion clear refuses a superseded worker`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val requestKey = ManagedDownloadMigrationCheckpointStore.ACTIVE_REQUEST_KEY
        val activeRequest = JSONObject().apply {
            put("version", ManagedDownloadMigrationCheckpointStore.CURRENT_MIGRATION_REQUEST_VERSION)
            put("workId", "new-work")
            put("targetLabel", "target")
            put("releasePreviousPermission", false)
            put("minimumSourceEntryCount", 1)
            put("autoResume", true)
        }.toString()
        `when`(preferences.getString(requestKey, null)).thenReturn(activeRequest)
        `when`(preferences.edit()).thenReturn(editor)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)

        assertNull(
            store.clearCompletedIfCurrent(
                ownerWorkId = "old-work",
                workIds = listOf("old-work")
            )
        )
        verify(editor, never()).commit()
    }

    @Test
    fun `owner guarded completion does not run release side effect for superseded worker`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val requestKey = ManagedDownloadMigrationCheckpointStore.ACTIVE_REQUEST_KEY
        val activeRequest = JSONObject().apply {
            put("version", ManagedDownloadMigrationCheckpointStore.CURRENT_MIGRATION_REQUEST_VERSION)
            put("workId", "new-work")
            put("targetLabel", "target")
            put("releasePreviousPermission", true)
            put("minimumSourceEntryCount", 1)
            put("autoResume", true)
        }.toString()
        `when`(preferences.getString(requestKey, null)).thenReturn(activeRequest)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.remove(anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        var sideEffectRan = false

        assertNull(
            store.clearCompletedAndRunIfCurrent(
                ownerWorkId = "old-work",
                workIds = listOf("old-work"),
                beforeClear = { sideEffectRan = true }
            )
        )

        assertFalse(sideEffectRan)
        verify(editor, never()).commit()
    }

    @Test
    fun `owner guarded completion runs side effect while request is current`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val requestKey = ManagedDownloadMigrationCheckpointStore.ACTIVE_REQUEST_KEY
        val activeRequest = JSONObject().apply {
            put("version", ManagedDownloadMigrationCheckpointStore.CURRENT_MIGRATION_REQUEST_VERSION)
            put("workId", "current-work")
            put("targetLabel", "target")
            put("releasePreviousPermission", true)
            put("minimumSourceEntryCount", 1)
            put("autoResume", true)
        }.toString()
        `when`(preferences.getString(requestKey, null)).thenReturn(activeRequest)
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.remove(anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        var sideEffectRan = false

        assertTrue(
            store.clearCompletedAndRunIfCurrent(
                ownerWorkId = "current-work",
                workIds = listOf("current-work"),
                beforeClear = { sideEffectRan = true }
            ) == true
        )

        assertTrue(sideEffectRan)
        verify(editor).commit()
    }

    @Test
    fun `terminal request is not eligible for automatic startup replay`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val key = ManagedDownloadMigrationCheckpointStore.ACTIVE_REQUEST_KEY
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putString(eq(key), anyString())).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)
        val payload = JSONObject().apply {
            put("version", ManagedDownloadMigrationCheckpointStore.CURRENT_MIGRATION_REQUEST_VERSION)
            put("workId", "work-terminal")
            put("fromDirectoryUri", "content://source")
            put("toDirectoryUri", "content://target")
            put("targetLabel", "target")
            put("releasePreviousPermission", false)
            put("minimumSourceEntryCount", 1)
            put("autoResume", false)
        }.toString()
        `when`(preferences.getString(key, null)).thenReturn(payload)

        assertEquals(false, store.readRequest()?.autoResume)
    }

    private fun copyReceiptFixture(sourceReference: String): ManagedMigrationCopyReceipt {
        val targetReference = sourceReference.replace("source", "target")
        val target = moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry(
            name = "audio.mp3",
            reference = targetReference,
            mediaUri = targetReference,
            localFilePath = null,
            sizeBytes = 12L,
            lastModifiedMs = 9L
        )
        return ManagedMigrationCopyReceipt(
            sourceReference = sourceReference,
            sourceName = "audio.mp3",
            sourceSubdirectory = null,
            sourceSizeBytes = 12L,
            sourceLastModifiedMs = 8L,
            targetEntry = target,
            sourceDigest = "a".repeat(64),
            verifiedTargetDigest = "a".repeat(64),
            createdNew = true,
            sourceAuthoritative = true,
            replacementBackup = null,
            sourceLogicalCreatedAtMs = 1_234L,
            sourceCreatedAtSource = "FILESYSTEM_BIRTH",
            sourceCreatedAtConfidence = "PROVIDER_REPORTED"
        )
    }
}
