package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.core.download.DownloadedSong
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadManagerScreenSelectionTest {

    @Test
    fun `toggleSelectedDownloadSongKeys uses latest selection state`() {
        val afterFirstSelect = toggleSelectedDownloadSongKeys(
            currentSelection = emptySet(),
            selectionKey = "song-a",
            selected = true
        )
        val afterSecondSelect = toggleSelectedDownloadSongKeys(
            currentSelection = afterFirstSelect,
            selectionKey = "song-b",
            selected = true
        )
        val afterUnselect = toggleSelectedDownloadSongKeys(
            currentSelection = afterSecondSelect,
            selectionKey = "song-a",
            selected = false
        )

        assertEquals(setOf("song-a"), afterFirstSelect)
        assertEquals(setOf("song-a", "song-b"), afterSecondSelect)
        assertEquals(setOf("song-b"), afterUnselect)
    }

    @Test
    fun `captureSongsPendingDelete snapshots selected songs by deletion identity`() {
        val firstSong = DownloadedSong(
            id = 1L,
            name = "First",
            artist = "Artist",
            album = "Album",
            filePath = "/music/first.flac",
            fileSize = 10L,
            downloadTime = 1L,
            mediaUri = "content://downloads/first.flac"
        )
        val secondSong = firstSong.copy(
            id = 2L,
            name = "Second",
            filePath = "/music/second.flac",
            mediaUri = "content://downloads/second.flac"
        )

        val snapshot = captureSongsPendingDelete(
            downloadedSongs = listOf(firstSong, secondSong),
            selectedSongKeys = setOf(secondSong.deletionIdentity())
        )

        assertEquals(listOf(secondSong), snapshot)
    }
}
