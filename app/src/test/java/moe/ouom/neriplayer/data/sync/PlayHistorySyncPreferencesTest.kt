package moe.ouom.neriplayer.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
