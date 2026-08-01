package moe.ouom.neriplayer.data.search

import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreSearchHistoryPolicyTest {
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
}
