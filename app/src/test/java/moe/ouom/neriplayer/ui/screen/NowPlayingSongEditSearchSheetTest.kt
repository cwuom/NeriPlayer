package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.canSearchEditSong
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.musicPlatformLabelResource
import moe.ouom.neriplayer.ui.viewmodel.ManualSearchState
import org.junit.Assert.assertEquals
import org.junit.Test

class NowPlayingSongEditSearchSheetTest {
    @Test
    fun `cloud search requires its session while other platforms remain available`() {
        assertEquals(true, canSearchEditSong(ManualSearchState()))
        assertEquals(false,
            canSearchEditSong(ManualSearchState(selectedPlatform = MusicPlatform.CLOUD_MUSIC))
        )
        assertEquals(
            true,
            canSearchEditSong(
                ManualSearchState(
                    selectedPlatform = MusicPlatform.CLOUD_MUSIC,
                    isCloudMusicAvailable = true
                )
            )
        )
    }

    @Test
    fun `search platform tabs keep their existing labels`() {
        assertEquals(CoreCommonR.string.platform_netease_short,
            musicPlatformLabelResource(MusicPlatform.CLOUD_MUSIC)
        )
        assertEquals(CoreCommonR.string.settings_qq_music, musicPlatformLabelResource(MusicPlatform.QQ_MUSIC))
    }
}
