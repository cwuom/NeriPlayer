package moe.ouom.neriplayer.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControlLayoutPreferencesTest {
    @Test
    fun `missing values restore the current layout defaults`() {
        assertEquals(
            PlaybackControlLayoutPreferences(),
            resolvePlaybackControlLayoutPreferences(
                nowPlayingPlacementValue = null,
                nowPlayingSizeValue = null,
                lyricsSizeValue = null
            )
        )
    }

    @Test
    fun `invalid persisted values fall back without changing valid values`() {
        assertEquals(
            PlaybackControlLayoutPreferences(
                nowPlayingPlacement = NowPlayingControlPlacement.LOWER,
                nowPlayingSize = PlaybackControlSize.LARGE,
                lyricsSize = PlaybackControlSize.MEDIUM
            ),
            resolvePlaybackControlLayoutPreferences(
                nowPlayingPlacementValue = Int.MAX_VALUE,
                nowPlayingSizeValue = PlaybackControlSize.LARGE.ordinal,
                lyricsSizeValue = PlaybackControlSize.MEDIUM.ordinal
            )
        )
    }

    @Test
    fun `bottom with progress restores a continuous bottom playback region`() {
        val preferences = resolvePlaybackControlLayoutPreferences(
            nowPlayingPlacementValue = NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS.ordinal,
            nowPlayingSizeValue = PlaybackControlSize.MEDIUM.ordinal,
            lyricsSizeValue = PlaybackControlSize.MEDIUM.ordinal
        )

        assertEquals(NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS, preferences.nowPlayingPlacement)
        assertTrue(preferences.nowPlayingPlacement.placesControlsAtBottom)
        assertTrue(preferences.nowPlayingPlacement.placesProgressAtBottom)
        assertFalse(NowPlayingControlPlacement.BOTTOM.placesProgressAtBottom)
    }
}
