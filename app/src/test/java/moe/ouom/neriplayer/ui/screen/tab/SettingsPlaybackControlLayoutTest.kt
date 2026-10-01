package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.playback.NowPlayingControlPlacement
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlSize
import moe.ouom.neriplayer.ui.screen.tab.settings.playback.PlaybackControlLayoutOwner
import moe.ouom.neriplayer.ui.screen.tab.settings.playback.PlaybackControlLayoutSetting
import moe.ouom.neriplayer.ui.screen.tab.settings.playback.nowPlayingControlPlacementLabelRes
import moe.ouom.neriplayer.ui.screen.tab.settings.playback.playbackControlSizeLabelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsPlaybackControlLayoutTest {
    @Test
    fun `control labels match every placement and size`() {
        assertEquals(
            CoreCommonR.string.settings_nowplaying_control_placement_lower,
            nowPlayingControlPlacementLabelRes(NowPlayingControlPlacement.LOWER)
        )
        assertEquals(
            CoreCommonR.string.settings_nowplaying_control_placement_bottom,
            nowPlayingControlPlacementLabelRes(NowPlayingControlPlacement.BOTTOM)
        )
        assertEquals(
            CoreCommonR.string.settings_nowplaying_control_placement_bottom_with_progress,
            nowPlayingControlPlacementLabelRes(NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS)
        )
        assertEquals(
            CoreCommonR.string.settings_playback_control_size_small,
            playbackControlSizeLabelRes(PlaybackControlSize.SMALL)
        )
        assertEquals(
            CoreCommonR.string.settings_playback_control_size_medium,
            playbackControlSizeLabelRes(PlaybackControlSize.MEDIUM)
        )
        assertEquals(
            CoreCommonR.string.settings_playback_control_size_large,
            playbackControlSizeLabelRes(PlaybackControlSize.LARGE)
        )
    }

    @Test
    fun `owner updates only the selected preference and closes the dialog`() {
        val owner = PlaybackControlLayoutOwner()
        val initial = PlaybackControlLayoutPreferences()
        var updated: PlaybackControlLayoutPreferences? = null
        owner.update(initial) { updated = it }

        owner.openPlacement()
        assertEquals(PlaybackControlLayoutSetting.NOW_PLAYING_PLACEMENT, owner.selectedSetting)
        owner.placementAction(NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS)()
        assertEquals(
            initial.copy(nowPlayingPlacement = NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS),
            updated
        )
        assertNull(owner.selectedSetting)

        val changedPlacement = requireNotNull(updated)
        owner.update(changedPlacement) { updated = it }
        owner.openNowPlayingSize()
        assertEquals(PlaybackControlLayoutSetting.NOW_PLAYING_SIZE, owner.selectedSetting)
        owner.nowPlayingSizeAction(PlaybackControlSize.LARGE)()
        assertEquals(changedPlacement.copy(nowPlayingSize = PlaybackControlSize.LARGE), updated)
        assertNull(owner.selectedSetting)

        val changedNowPlayingSize = requireNotNull(updated)
        owner.update(changedNowPlayingSize) { updated = it }
        owner.openLyricsSize()
        assertEquals(PlaybackControlLayoutSetting.LYRICS_SIZE, owner.selectedSetting)
        owner.lyricsSizeAction(PlaybackControlSize.SMALL)()
        assertEquals(changedNowPlayingSize.copy(lyricsSize = PlaybackControlSize.SMALL), updated)
        assertNull(owner.selectedSetting)
    }

    @Test
    fun `owner keeps the latest callback and dismisses without writing`() {
        val owner = PlaybackControlLayoutOwner()
        val initial = PlaybackControlLayoutPreferences()
        var staleWrites = 0
        var latestWrites = 0
        owner.update(initial) { staleWrites++ }
        owner.openPlacement()
        owner.update(initial) { latestWrites++ }
        owner.dismiss()
        assertNull(owner.selectedSetting)
        assertEquals(0, staleWrites)
        assertEquals(0, latestWrites)

        owner.openPlacement()
        owner.selectPlacement(NowPlayingControlPlacement.BOTTOM)
        assertEquals(0, staleWrites)
        assertEquals(1, latestWrites)
    }
}
