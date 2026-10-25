package moe.ouom.neriplayer.data.local.database.migration

import moe.ouom.neriplayer.data.local.database.migration.legacy.LegacyMigrationDatabase
import moe.ouom.neriplayer.data.local.database.migration.library.LocalSourceProvenanceMigration
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSourceProvenanceMigrationTest {
    @Test
    fun `upgrade adds nullable source provenance to history and queue without rewriting existing rows`() {
        val fixture = LegacyMigrationDatabase()

        LocalSourceProvenanceMigration.migrate(fixture.database)

        assertEquals(listOf(
            "ALTER TABLE `play_history` ADD COLUMN `explicit_local_source_reference` TEXT",
            "ALTER TABLE `playback_queue_song` ADD COLUMN `explicit_local_source_reference` TEXT"
        ), fixture.statements)
    }
}
