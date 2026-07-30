package moe.ouom.neriplayer.ui.screen.playlist

import androidx.media3.common.Player
import moe.ouom.neriplayer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistDetailPlaybackPolicyTest {

    @Test
    fun `display order snapshot returns an isolated copy`() {
        val original = mutableListOf("a", "b", "c")
        val snapshot = snapshotDisplayOrderList(original)

        original += "d"

        assertEquals(listOf("a", "b", "c"), snapshot)
        assertNotSame(original, snapshot)
    }

    @Test
    fun `playing item index accounts for the hero header and actions`() {
        val index = resolveLocalPlaylistPlayingItemIndex(
            songIndex = 0,
            metadataProcessingVisible = false
        )

        assertEquals(2, index)
    }

    @Test
    fun `playing item index accounts for metadata card`() {
        val index = resolveLocalPlaylistPlayingItemIndex(
            songIndex = 0,
            metadataProcessingVisible = true
        )

        assertEquals(3, index)
    }

    @Test
    fun `playing item index returns missing for negative song index`() {
        val index = resolveLocalPlaylistPlayingItemIndex(
            songIndex = -1,
            metadataProcessingVisible = true
        )

        assertEquals(-1, index)
    }

    @Test
    fun `song list index uses the same fixed item offset`() {
        val index = resolveLocalPlaylistSongListIndex(
            songIndex = 2,
            metadataProcessingVisible = true
        )

        assertEquals(5, index)
    }

    @Test
    fun `playback start index returns missing for empty playlist`() {
        val index = resolvePlaylistPlaybackStartIndex(
            songCount = 0,
            shuffleEnabled = false,
            randomIndex = 0
        )

        assertEquals(-1, index)
    }

    @Test
    fun `playback start index begins at first song in order mode`() {
        val index = resolvePlaylistPlaybackStartIndex(
            songCount = 8,
            shuffleEnabled = false,
            randomIndex = 5
        )

        assertEquals(0, index)
    }

    @Test
    fun `playback start index clamps random input in shuffle mode`() {
        val lowIndex = resolvePlaylistPlaybackStartIndex(
            songCount = 4,
            shuffleEnabled = true,
            randomIndex = -8
        )
        val highIndex = resolvePlaylistPlaybackStartIndex(
            songCount = 4,
            shuffleEnabled = true,
            randomIndex = 8
        )

        assertEquals(0, lowIndex)
        assertEquals(3, highIndex)
    }

    @Test
    fun `repeat mode labels map to playlist resources`() {
        assertEquals(
            R.string.playlist_mode_repeat_off,
            localPlaylistRepeatModeLabelRes(Player.REPEAT_MODE_OFF)
        )
        assertEquals(
            R.string.playlist_mode_repeat_all,
            localPlaylistRepeatModeLabelRes(Player.REPEAT_MODE_ALL)
        )
        assertEquals(
            R.string.playlist_mode_repeat_one,
            localPlaylistRepeatModeLabelRes(Player.REPEAT_MODE_ONE)
        )
    }

    @Test
    fun `quick export requires at least one song`() {
        assertFalse(shouldEnableLocalPlaylistQuickExport(0))
        assertTrue(shouldEnableLocalPlaylistQuickExport(1))
    }
}
