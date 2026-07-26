package moe.ouom.neriplayer.core.api.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlayerScriptStoreTest {

    private val nowMs = 1_700_000_000_000L

    private fun entry(name: String, ageMs: Long) =
        PlayerScriptCacheEntry(name = name, lastModifiedMs = nowMs - ageMs)

    @Test
    fun derivesAStableFileSafeKeyFromThePlayerUrl() {
        val url = "https://www.youtube.com/s/player/deadbeef/player_ias.vflset/en_US/base.js"

        val key = youTubePlayerScriptCacheKey(url)

        assertEquals(key, youTubePlayerScriptCacheKey(url))
        assertEquals(64, key.length)
        // 直接拿 URL 当文件名会带上斜杠, 必须是纯十六进制才能落盘
        assertTrue(key.all { it in "0123456789abcdef" })
    }

    @Test
    fun ignoresSurroundingWhitespaceSoAWarmAndAResolveShareOneEntry() {
        assertEquals(
            youTubePlayerScriptCacheKey("https://player/base.js"),
            youTubePlayerScriptCacheKey("  https://player/base.js  ")
        )
    }

    @Test
    fun givesDifferentPlayerVersionsDifferentEntries() {
        assertNotEquals(
            youTubePlayerScriptCacheKey("https://www.youtube.com/s/player/aaa/base.js"),
            youTubePlayerScriptCacheKey("https://www.youtube.com/s/player/bbb/base.js")
        )
    }

    @Test
    fun keepsTheMostRecentlyUsedScriptsAndDropsTheRest() {
        val entries = listOf(
            entry("a.js", ageMs = 1_000L),
            entry("b.js", ageMs = 2_000L),
            entry("c.js", ageMs = 3_000L),
            entry("d.js", ageMs = 4_000L),
            entry("e.js", ageMs = 5_000L)
        )

        val evicted = selectYouTubePlayerScriptEvictions(entries, nowMs = nowMs).map { it.name }

        assertEquals(listOf("d.js", "e.js"), evicted)
    }

    @Test
    fun keepsEverythingWhileUnderTheRetentionCount() {
        val entries = listOf(entry("a.js", ageMs = 1_000L), entry("b.js", ageMs = 2_000L))

        assertTrue(selectYouTubePlayerScriptEvictions(entries, nowMs = nowMs).isEmpty())
    }

    @Test
    fun dropsExpiredScriptsEvenWhenTheyAreAmongTheNewest() {
        val entries = listOf(
            entry("stale.js", ageMs = PLAYER_SCRIPT_CACHE_MAX_AGE_MS),
            entry("fresh.js", ageMs = PLAYER_SCRIPT_CACHE_MAX_AGE_MS - 1L)
        )

        val evicted = selectYouTubePlayerScriptEvictions(entries, nowMs = nowMs).map { it.name }

        // 只有两份, 数量上都该留下, 过期那份仍要走
        assertEquals(listOf("stale.js"), evicted)
    }

    @Test
    fun evictsNothingWhenThereIsNothingCached() {
        assertTrue(selectYouTubePlayerScriptEvictions(emptyList(), nowMs = nowMs).isEmpty())
    }
}
