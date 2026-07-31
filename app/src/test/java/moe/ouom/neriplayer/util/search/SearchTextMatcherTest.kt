package moe.ouom.neriplayer.util.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextMatcherTest {
    private data class SearchEntry(
        val title: String,
        val artist: String
    )

    @Test
    fun `matches ignores latin case`() {
        assertTrue(SearchTextMatcher.matches("LoFi", "lofi playlist"))
    }

    @Test
    fun `matches Chinese title by full pinyin and initials`() {
        assertTrue(SearchTextMatcher.matches("qingtian", "晴天"))
        assertTrue(SearchTextMatcher.matches("qt", "晴天"))
        assertTrue(SearchTextMatcher.matches("yuyan", "语言"))
        assertTrue(SearchTextMatcher.matches("yy", "语言"))
        assertTrue(SearchTextMatcher.matches("ylzs", "月亮之矢"))
        assertTrue(SearchTextMatcher.matches("lunai", "鹿乃"))
    }

    @Test
    fun `matches fuzzy subsequence tokens`() {
        assertTrue(SearchTextMatcher.matches("bfzt", "播放状态"))
        assertTrue(SearchTextMatcher.matches("drc", "dynamic_reactive_canvas"))
        assertFalse(SearchTextMatcher.matches("github", "download_parallelism"))
    }

    @Test
    fun `requires all query tokens to match`() {
        assertTrue(SearchTextMatcher.matches("usb pcm", "USB exclusive PCM output"))
        assertFalse(SearchTextMatcher.matches("usb lyrics", "USB exclusive PCM output"))
    }

    @Test
    fun `filterAndRank keeps pinyin matches ahead for playlist style lists`() {
        val songs = listOf("夜曲 - 周杰伦", "晴天 - 周杰伦", "普通朋友 - David Tao")

        val ranked = SearchTextMatcher.filterAndRank("qt", songs) { listOf(it) }

        assertEquals(listOf("晴天 - 周杰伦"), ranked)
    }

    @Test
    fun `filterAndRank prefers weighted title match over artist match`() {
        val entries = listOf(
            SearchEntry(title = "别的歌", artist = "鹿乃"),
            SearchEntry(title = "鹿乃", artist = "别的歌")
        )

        val ranked = SearchTextMatcher.filterAndRank("lunai", entries) { entry ->
            listOf(
                SearchTextMatcher.value(entry.title, bias = 0),
                SearchTextMatcher.value(entry.artist, bias = 24)
            )
        }

        assertEquals("鹿乃", ranked.first().title)
    }
}
