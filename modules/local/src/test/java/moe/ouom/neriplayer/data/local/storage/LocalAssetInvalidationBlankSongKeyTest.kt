package moe.ouom.neriplayer.data.local.storage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LocalAssetInvalidationBlankSongKeyTest {
    @Before
    fun setUp() {
        LocalAssetInvalidationBus.resetForTest()
    }

    @After
    fun tearDown() {
        LocalAssetInvalidationBus.resetForTest()
    }

    @Test
    fun `blank song keys never create a tracked revision`() {
        LocalAssetInvalidationBus.bumpSong("")
        LocalAssetInvalidationBus.bumpSong("   ")

        assertEquals(0, LocalAssetInvalidationBus.songRevisionEntryCountForTest())
        assertEquals(0L, LocalAssetInvalidationBus.currentSongRevision("   "))
    }

    @Test
    fun `bulk bumps skip blank keys without consuming revisions`() {
        LocalAssetInvalidationBus.bumpSongs(listOf(" ", "song-a", "", "song-b"))

        assertEquals(2, LocalAssetInvalidationBus.songRevisionEntryCountForTest())
        assertEquals(1L, LocalAssetInvalidationBus.currentSongRevision("song-a"))
        assertEquals(2L, LocalAssetInvalidationBus.currentSongRevision("song-b"))
        assertEquals(0L, LocalAssetInvalidationBus.currentSongRevision(""))
    }
}
