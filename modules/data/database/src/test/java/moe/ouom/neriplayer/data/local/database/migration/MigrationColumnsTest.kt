package moe.ouom.neriplayer.data.local.database.migration

import moe.ouom.neriplayer.data.local.database.migration.legacy.LegacyMigrationDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationColumnsTest {
    @Test
    fun `existing columns are not altered when replaying a compatible schema`() {
        val fixture = LegacyMigrationDatabase(columns = mapOf("table" to listOf("name", "count")))
        addTextColumnIfMissing(fixture.database, "table", "name")
        addIntegerColumnIfMissing(fixture.database, "table", "count")
        assertTrue(fixture.statements.isEmpty())
    }

    @Test
    fun `missing columns keep their historical nullable SQL definitions`() {
        val fixture = LegacyMigrationDatabase(columns = mapOf("table" to listOf("other")))
        addTextColumnIfMissing(fixture.database, "table", "name")
        addIntegerColumnIfMissing(fixture.database, "table", "count")
        assertEquals(listOf("ALTER TABLE `table` ADD COLUMN `name` TEXT", "ALTER TABLE `table` ADD COLUMN `count` INTEGER"), fixture.statements)
    }

    @Test
    fun `empty column metadata still creates the requested column`() {
        val fixture = LegacyMigrationDatabase()
        addTextColumnIfMissing(fixture.database, "table", "name")
        addIntegerColumnIfMissing(fixture.database, "table", "count")
        assertEquals(2, fixture.statements.size)
    }
}
