package moe.ouom.neriplayer.common.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionMergeTest {
    private data class Item(val id: Int?, val title: String)

    @Test
    fun `merging keeps first values in input order without modifying either input`() {
        val existing = mutableListOf(Item(2, "first"), Item(1, "old"), Item(2, "duplicate"))
        val incoming = mutableListOf(Item(1, "new"), Item(3, "third"), Item(3, "duplicate"))

        val merged = existing.mergeDistinctBy(incoming) { it.id }

        assertEquals(listOf(Item(2, "first"), Item(1, "old"), Item(3, "third")), merged)
        assertEquals(listOf(Item(2, "first"), Item(1, "old"), Item(2, "duplicate")), existing)
        assertEquals(listOf(Item(1, "new"), Item(3, "third"), Item(3, "duplicate")), incoming)
    }

    @Test
    fun `limit counts unique results and stops consuming the incoming page`() {
        val incoming = Iterable {
            sequence {
                yield(Item(1, "duplicate"))
                yield(Item(2, "second"))
                error("items beyond the limit must not be read")
            }.iterator()
        }

        val merged = listOf(Item(1, "first")).mergeDistinctBy(incoming, limit = 2) { it.id }

        assertEquals(listOf(Item(1, "first"), Item(2, "second")), merged)
    }

    @Test
    fun `a full existing page never opens the incoming iterator`() {
        val incoming = Iterable<Item> { error("incoming page must not be read") }

        val merged = listOf(Item(1, "first"), Item(2, "second"))
            .mergeDistinctBy(incoming, limit = 1) { it.id }

        assertEquals(listOf(Item(1, "first")), merged)
    }

    @Test
    fun `zero limit does not iterate either input`() {
        val unreadable = Iterable<Item> { error("input must not be read") }

        assertTrue(unreadable.mergeDistinctBy(unreadable, limit = 0) { it.id }.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative limits are rejected`() {
        emptyList<Item>().mergeDistinctBy(emptyList(), limit = -1) { it.id }
    }

    @Test
    fun `empty and nullable keys use the same first occurrence rule`() {
        assertTrue(emptyList<Item>().mergeDistinctBy(emptyList()) { it.id }.isEmpty())
        val merged = emptyList<Item>().mergeDistinctBy(
            listOf(Item(null, "first"), Item(null, "second"), Item(1, "identified"))
        ) { it.id }

        assertEquals(listOf(Item(null, "first"), Item(1, "identified")), merged)
    }
}
