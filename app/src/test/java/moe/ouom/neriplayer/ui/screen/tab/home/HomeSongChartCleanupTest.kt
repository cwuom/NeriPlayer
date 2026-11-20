package moe.ouom.neriplayer.ui.screen.tab.home

import moe.ouom.neriplayer.ui.viewmodel.tab.HomeSectionIdMapping
import moe.ouom.neriplayer.ui.viewmodel.tab.NeteaseHomeSongSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 继续播放清理测试。
 *
 * 首页歌曲小模块的钻取走歌单详情页，退出时会把榜单写进「继续播放」。
 * 现在钻取统一走通用歌曲列表页，不会再写入，
 * 因此首页启动时需要把这 4 个榜单的存量条目清掉。
 */
class HomeSongChartCleanupTest {

    @Test
    fun `cleanup targets the four fixed chart playlists`() {
        assertEquals(
            setOf(
                19_723_756L, // 飙升榜
                3_778_678L, // 热歌榜
                3_779_629L, // 新歌榜
                3_136_952_023L // 私人雷达
            ),
            HomeSectionIdMapping.chartPlaylistIds
        )
    }

    @Test
    fun `chart playlist ids match the known netease ids`() {
        assertEquals(
            19_723_756L,
            HomeSectionIdMapping.playlistIdOf(NeteaseHomeSongSource.TOP_SOARING)
        )
        assertEquals(
            3_778_678L,
            HomeSectionIdMapping.playlistIdOf(NeteaseHomeSongSource.TOP_HOT)
        )
        assertEquals(
            3_779_629L,
            HomeSectionIdMapping.playlistIdOf(NeteaseHomeSongSource.TOP_NEW)
        )
        assertEquals(
            3_136_952_023L,
            HomeSectionIdMapping.playlistIdOf(NeteaseHomeSongSource.PERSONAL_RADAR)
        )
    }

    @Test
    fun `sections without a fixed playlist are not cleanup targets`() {
        assertNull(HomeSectionIdMapping.playlistIdOf(NeteaseHomeSongSource.DAILY_RECOMMEND))
        assertNull(HomeSectionIdMapping.playlistIdOf(NeteaseHomeSongSource.PRIVATE_FM))
        assertNull(HomeSectionIdMapping.playlistIdOf(NeteaseHomeSongSource.PERSONALIZED_NEW_SONGS))
    }
}
