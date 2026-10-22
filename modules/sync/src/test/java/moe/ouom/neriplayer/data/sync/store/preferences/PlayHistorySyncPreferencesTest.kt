package moe.ouom.neriplayer.data.sync.store.preferences

import android.content.Context
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class PlayHistorySyncPreferencesTest {
    @Test
    fun `legacy batched mode maps to ten minute interval`() {
        val mode = PlayHistorySyncPreferences.UpdateMode.fromStoredName("BATCHED")
        val aliasMode = PlayHistorySyncPreferences.UpdateMode.fromStoredName("BATCHED_10")

        assertEquals(PlayHistorySyncPreferences.UpdateMode.EVERY_10_MINUTES, mode)
        assertEquals(PlayHistorySyncPreferences.UpdateMode.EVERY_10_MINUTES, aliasMode)
        assertEquals(10, mode?.intervalMinutes)
    }

    @Test
    fun `new interval modes expose expected minute values`() {
        assertEquals(
            15,
            PlayHistorySyncPreferences.UpdateMode
                .fromStoredName("EVERY_15_MINUTES")
                ?.intervalMinutes
        )
        assertEquals(
            30,
            PlayHistorySyncPreferences.UpdateMode
                .fromStoredName("EVERY_30_MINUTES")
                ?.intervalMinutes
        )
    }

    @Test
    fun `unknown stored mode is ignored`() {
        assertNull(PlayHistorySyncPreferences.UpdateMode.fromStoredName("SOMETHING_ELSE"))
    }

    @Test
    fun `snapshot migrates a legacy interval once and later skips the legacy store`() {
        val preferences = MemorySyncPreferences()
        val context = mock(Context::class.java)
        `when`(context.getSharedPreferences("play_history_sync_preferences", Context.MODE_PRIVATE))
            .thenReturn(preferences.preferences)
        val store = PlayHistorySyncPreferences(context)
        var legacyReads = 0

        val migrated = store.snapshot { ++legacyReads; "BATCHED_30" }
        val later = store.snapshot { error("legacy storage should not be opened after migration") }

        assertEquals("EVERY_30_MINUTES", migrated.playHistoryUpdateMode)
        assertEquals("EVERY_30_MINUTES", later.playHistoryUpdateMode)
        assertEquals(1, legacyReads)
        assertEquals("EVERY_30_MINUTES", preferences.restart().values["play_history_update_mode"])
    }

    @Test
    fun `snapshot keeps the explicit shared interval without opening legacy storage`() {
        val preferences = MemorySyncPreferences(mapOf("play_history_update_mode" to "EVERY_15_MINUTES"))
        val context = mock(Context::class.java)
        `when`(context.getSharedPreferences("play_history_sync_preferences", Context.MODE_PRIVATE))
            .thenReturn(preferences.preferences)

        val snapshot = PlayHistorySyncPreferences(context).snapshot {
            error("legacy storage should not be opened for an explicit shared interval")
        }

        assertEquals("EVERY_15_MINUTES", snapshot.playHistoryUpdateMode)
    }
}
