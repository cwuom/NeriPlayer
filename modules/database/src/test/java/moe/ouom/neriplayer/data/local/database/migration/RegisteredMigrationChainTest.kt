package moe.ouom.neriplayer.data.local.database.migration

import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import org.junit.Assert.assertEquals
import org.junit.Test

class RegisteredMigrationChainTest {
    @Test
    fun `registered migrations upgrade every released version to the final schema`() {
        val steps = NeriUserDataDatabase.allMigrations().map { it.startVersion to it.endVersion }

        assertEquals(
            (1 until NeriUserDataDatabase.FINAL_DB_VERSION).map { it to it + 1 },
            steps
        )
    }
}
