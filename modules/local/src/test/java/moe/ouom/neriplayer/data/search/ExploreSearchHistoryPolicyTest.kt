package moe.ouom.neriplayer.data.search

import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreSearchHistoryPolicyTest {
    @Test
    fun disabledHistoryIsHiddenWithoutChangingStoredEntries() {
        val history = listOf("晴天", "夜曲")

        assertEquals(history, exploreSearchHistoryForDisplay(enabled = true, history = history))
        assertEquals(emptyList<String>(), exploreSearchHistoryForDisplay(enabled = false, history = history))
    }

    @Test
    fun disabledHistoryDoesNotResolveSearchAliases() {
        val availableHistory = exploreSearchHistoryForDisplay(
            enabled = false,
            history = listOf("晴天", "夜曲")
        )

        assertEquals("qt", resolveExploreSearchKeyword("qt", availableHistory))
    }

    @Test
    fun `history recording follows the setting and ignores blank queries`() {
        assertEquals(true, shouldRecordExploreSearchHistory("晴天", enabled = true))
        assertEquals(false, shouldRecordExploreSearchHistory("晴天", enabled = false))
        assertEquals(false, shouldRecordExploreSearchHistory("  ", enabled = true))
    }

    @Test
    fun `record keyword resolves aliases only when history is enabled`() {
        val history = listOf("晴天", "夜曲")

        assertEquals(
            "晴天",
            exploreSearchHistoryRecordKeyword(query = "qt", enabled = true, history = history)
        )
        assertEquals(null, exploreSearchHistoryRecordKeyword(query = "qt", enabled = false, history = history))
        assertEquals(null, exploreSearchHistoryRecordKeyword(query = "  ", enabled = true, history = history))
    }

    @Test
    fun `recorded query moves to front and deduplicates ignoring case`() {
        val next = updatedExploreSearchHistory(
            current = listOf("晴天", "夜曲", "QingTian"),
            query = " qingtian "
        )

        assertEquals(listOf("qingtian", "晴天", "夜曲"), next)
    }

    @Test
    fun `history respects limit`() {
        val next = updatedExploreSearchHistory(
            current = listOf("a", "b", "c"),
            query = "d",
            limit = 3
        )

        assertEquals(listOf("d", "a", "b"), next)
    }

    @Test
    fun `blank query only applies the limit and retains stored entries verbatim`() {
        val current = listOf("  Song ", "song", "", "tail")

        assertEquals(listOf("  Song ", "song", ""), updatedExploreSearchHistory(current, " \t ", limit = 3))
        assertEquals(current, updatedExploreSearchHistory(current, "", limit = 10))
    }

    @Test
    fun `zero and negative limits discard history for both blank and valid queries`() {
        listOf("", "new").forEach { query ->
            listOf(0, -1, Int.MIN_VALUE).forEach { limit ->
                assertEquals(emptyList<String>(), updatedExploreSearchHistory(listOf("old"), query, limit))
            }
        }
    }

    @Test
    fun `recording removes empty entries and retains the first spelling of other duplicates`() {
        val next = updatedExploreSearchHistory(
            current = listOf("", " \t ", "q", " Q ", " First ", "first", "Second", " second "),
            query = " q "
        )

        assertEquals(listOf("q", "First", "Second"), next)
    }

    @Test
    fun `case insensitive Unicode variants share the recorded query identity`() {
        val next = updatedExploreSearchHistory(
            current = listOf("İSTANBUL", "ıstanbul", "other"),
            query = "istanbul"
        )

        assertEquals(listOf("istanbul", "other"), next)
    }

    @Test
    fun `recording the first query starts an empty history`() {
        assertEquals(listOf("new"), updatedExploreSearchHistory(emptyList(), " new "))
    }

    @Test
    fun `default history limit keeps fifteen entries`() {
        val next = updatedExploreSearchHistory(
            current = (1..20).map { "old$it" },
            query = "new"
        )

        assertEquals(15, next.size)
        assertEquals("new", next.first())
        assertEquals("old14", next.last())
    }

    @Test
    fun `latin pinyin query can resolve to matching Chinese history`() {
        val history = listOf("晴天", "夜曲")

        assertEquals("晴天", resolveExploreSearchKeyword("qt", history))
        assertEquals("晴天", resolveExploreSearchKeyword("qingtian", history))
    }
}
