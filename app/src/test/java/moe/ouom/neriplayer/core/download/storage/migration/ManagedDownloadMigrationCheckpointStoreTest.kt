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
    fun `clear removes only the completed work checkpoint`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val key = "${ManagedDownloadMigrationCheckpointStore.KEY_PREFIX}work-4"
        val targetNamesKey =
            "${ManagedDownloadMigrationCheckpointStore.TARGET_NAMES_KEY_PREFIX}work-4"
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.remove(key)).thenReturn(editor)
        `when`(editor.remove(targetNamesKey)).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)

        assertTrue(store.clear("work-4"))
        verify(editor).remove(key)
        verify(editor).remove(targetNamesKey)
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
            replacements = listOf(plan)
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
}
