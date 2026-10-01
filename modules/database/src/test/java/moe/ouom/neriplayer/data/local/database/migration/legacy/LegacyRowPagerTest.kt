package moe.ouom.neriplayer.data.local.database.migration.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyRowPagerTest {
    private fun fixture(count: Int): LegacyMigrationDatabase = LegacyMigrationDatabase(
        mapOf("legacy" to (1..count).map { mapOf("value" to it.toLong()) })
    )

    @Test
    fun `keyset pages visit every row exactly once across a full batch`() {
        val fixture = fixture(129)
        val visited = mutableListOf<Long>()
        forEachLegacyBatch(fixture.database, "legacy") { cursor, _ -> visited += cursorLong(cursor, "value")!! }
        assertEquals((1L..129L).toList(), visited)
        assertEquals(3, fixture.queries.size)
        assertTrue(fixture.queries.drop(1).all { it.contains("WHERE rowid > ?") })
    }

    @Test
    fun `legacy table without rowid falls back to offset without losing rows`() {
        val fixture = fixture(129).apply {
            queryFailure = { if (it.contains("rowid AS")) IllegalStateException("no such column: rowid") else null }
        }
        val visited = mutableListOf<Long>()
        forEachLegacyBatch(fixture.database, "legacy") { cursor, _ -> visited += cursorLong(cursor, "value")!! }
        assertEquals((1L..129L).toList(), visited)
        assertTrue(fixture.queries.any { it.endsWith("OFFSET 64") })
        assertTrue(fixture.queries.any { it.endsWith("OFFSET 128") })
    }

    @Test
    fun `rowid failure after a successful page resumes at processed row count`() {
        val fixture = fixture(65).apply {
            queryFailure = { if (it.contains("WHERE rowid > ?")) IllegalStateException("rowid failed") else null }
        }
        val visited = mutableListOf<Long>()
        forEachLegacyBatch(fixture.database, "legacy") { cursor, _ -> visited += cursorLong(cursor, "value")!! }
        assertEquals((1L..65L).toList(), visited)
        assertTrue(fixture.queries.last().endsWith("OFFSET 64"))
    }

    @Test
    fun `failure in compatibility paging propagates instead of acknowledging partial migration`() {
        val fixture = fixture(65).apply { queryFailure = { IllegalStateException("cannot read database") } }
        assertThrows(IllegalStateException::class.java) {
            forEachLegacyBatch(fixture.database, "legacy") { _, _ -> error("must not read") }
        }
        assertEquals(2, fixture.queries.size)
    }

    @Test
    fun `read failure on a later compatibility page stops migration`() {
        val fixture = fixture(65).apply {
            queryFailure = {
                if (it.contains("rowid AS") || it.endsWith("OFFSET 64")) IllegalStateException("cannot read page")
                else null
            }
        }
        val visited = mutableListOf<Long>()
        assertThrows(IllegalStateException::class.java) {
            forEachLegacyBatch(fixture.database, "legacy") { cursor, _ -> visited += cursorLong(cursor, "value")!! }
        }
        assertEquals((1L..64L).toList(), visited)
    }
}
