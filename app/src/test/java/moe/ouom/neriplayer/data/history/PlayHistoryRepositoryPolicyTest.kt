package moe.ouom.neriplayer.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayHistoryRepositoryPolicyTest {

    @Test
    fun `settled play history sync waits until playback has cooled down`() {
        assertTrue(
            playHistoryAutoSyncDelayMillis(PlayHistorySyncUrgency.SETTLED) >= 15_000L
        )
    }

    @Test
    fun `immediate play history sync keeps destructive mutations eager`() {
        assertEquals(
            0L,
            playHistoryAutoSyncDelayMillis(PlayHistorySyncUrgency.IMMEDIATE)
        )
    }
}
