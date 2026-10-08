package moe.ouom.neriplayer.data.search

import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreSearchHistoryDecodeTest {
    @Test
    fun `missing blank or unreadable history decodes as empty`() {
        listOf(null, "", "  ", "not json", """{"history":["a"]}""").forEach { raw ->
            assertEquals(raw.toString(), emptyList<String>(), decodeExploreSearchHistory(raw))
        }
    }

    @Test
    fun `stored history is trimmed deduplicated case insensitively and capped`() {
        val stored = listOf(" Sunny ", "", "sunny", "  ", "Night") + (1..20).map { "q$it" }

        val decoded = decodeExploreSearchHistory(stored.joinToString(prefix = "[", postfix = "]") { "\"$it\"" })

        assertEquals(DEFAULT_EXPLORE_SEARCH_HISTORY_LIMIT, decoded.size)
        assertEquals(listOf("Sunny", "Night", "q1"), decoded.take(3))
        assertEquals("q13", decoded.last())
    }
}
