package moe.ouom.neriplayer.core.download.storage.migration

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
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
    fun `clear removes only the completed work checkpoint`() {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        val key = "${ManagedDownloadMigrationCheckpointStore.KEY_PREFIX}work-4"
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.remove(key)).thenReturn(editor)
        `when`(editor.commit()).thenReturn(true)
        val store = ManagedDownloadMigrationCheckpointStore(preferences)

        assertTrue(store.clear("work-4"))
        verify(editor).remove(key)
        verify(editor).commit()
    }
}
