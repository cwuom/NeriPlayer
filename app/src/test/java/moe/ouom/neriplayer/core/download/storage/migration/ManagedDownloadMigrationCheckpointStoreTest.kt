package moe.ouom.neriplayer.core.download.storage.migration

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyString
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
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
            autoResume = true
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
}
