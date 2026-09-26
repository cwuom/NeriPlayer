package moe.ouom.neriplayer.data.local.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAssetInvalidationBusTest {
    @After
    fun tearDown() {
        LocalAssetInvalidationBus.resetForTest()
    }

    @Test
    fun `song revisions stay bounded without forgetting evicted invalidations`() {
        repeat(4_097) { index ->
            LocalAssetInvalidationBus.bumpSong("song-$index")
        }

        assertEquals(4_096, LocalAssetInvalidationBus.songRevisionEntryCountForTest())
        assertTrue(LocalAssetInvalidationBus.currentSongRevision("song-0") > 0L)
        assertEquals(4_097L, LocalAssetInvalidationBus.currentSongRevision("song-4096"))
    }
}
