package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreSearchResultRankingTest {
    @Test
    fun `pinyin query promotes matching Chinese song title`() {
        val songs = listOf(
            song(id = 1, name = "夜曲"),
            song(id = 2, name = "晴天")
        )

        val ranked = rankExploreSongSearchResults("qt", songs)

        assertEquals("晴天", ranked.first().name)
    }

    @Test
    fun `title pinyin match ranks ahead of artist pinyin match`() {
        val songs = listOf(
            song(id = 1, name = "别的歌", artist = "鹿乃"),
            song(id = 2, name = "鹿乃", artist = "别的歌")
        )

        val ranked = rankExploreSongSearchResults("lunai", songs)

        assertEquals("鹿乃", ranked.first().name)
    }

    @Test
    fun `latin search ignores case in song metadata`() {
        val songs = listOf(
            song(id = 1, name = "Intro"),
            song(id = 2, name = "LOFI Dream")
        )

        val ranked = rankExploreSongSearchResults("lofi", songs)

        assertEquals("LOFI Dream", ranked.first().name)
    }

    private fun song(id: Long, name: String, artist: String = "周杰伦"): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = "测试专辑",
            albumId = id,
            durationMs = 180_000L,
            coverUrl = null
        )
    }
}
