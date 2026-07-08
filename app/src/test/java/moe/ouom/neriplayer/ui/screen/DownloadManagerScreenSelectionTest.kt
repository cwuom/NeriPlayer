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

    @Test
    fun `sanitizeDownloadSelectionState keeps empty selection mode when songs exist`() {
        val song = testDownloadedSong(id = 1L, name = "First")

        val state = sanitizeDownloadSelectionState(
            selectionMode = true,
            selectedSongKeys = emptySet(),
            downloadedSongs = listOf(song)
        )

        assertEquals(DownloadSelectionState(selectionMode = true, selectedSongKeys = emptySet()), state)
    }

    @Test
    fun `sanitizeDownloadSelectionState exits selection mode when library is empty`() {
        val state = sanitizeDownloadSelectionState(
            selectionMode = true,
            selectedSongKeys = setOf("stale"),
            downloadedSongs = emptyList()
        )

        assertEquals(DownloadSelectionState(selectionMode = false, selectedSongKeys = emptySet()), state)
    }

    @Test
    fun `sanitizeDownloadSelectionState removes stale selected song keys`() {
        val song = testDownloadedSong(id = 1L, name = "First")

        val state = sanitizeDownloadSelectionState(
            selectionMode = true,
            selectedSongKeys = setOf(song.deletionIdentity(), "stale"),
            downloadedSongs = listOf(song)
        )

        assertEquals(
            DownloadSelectionState(
                selectionMode = true,
                selectedSongKeys = setOf(song.deletionIdentity())
            ),
            state
        )
    }

    private fun testDownloadedSong(
        id: Long,
        name: String
    ): DownloadedSong {
        return DownloadedSong(
            id = id,
            name = name,
            artist = "Artist",
            album = "Album",
            filePath = "/music/$name.flac",
            fileSize = 10L,
            downloadTime = 1L,
            mediaUri = "content://downloads/$name.flac"
        )
    }
}
