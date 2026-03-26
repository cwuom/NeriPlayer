package moe.ouom.neriplayer.ui.screen.tab

import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicHomeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HomeScreenMappingTest {

    @Test
    fun toPlayableSongItem_keepsHomeItemDuration() {
        val song = YouTubeMusicHomeItem(
            title = "爱你",
            subtitle = "歌曲 • 陈芳语 • 爱你 • 3:27",
            coverUrl = "https://example.com/cover.jpg",
            videoId = "video-aini",
            durationText = "3:27",
            durationMs = 207_000L
        ).toPlayableSongItem(sectionTitle = "猜你喜欢")

        assertNotNull(song)
        assertEquals(207_000L, song?.durationMs)
        assertEquals("陈芳语", song?.artist)
        assertEquals("爱你", song?.album)
    }
}
