package moe.ouom.neriplayer.data.local.database

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DatabaseVersionStateTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `missing file is not read`() {
        val state = databaseVersionState(File(temporary.root, "absent.db"), supported = 20) { error("must not open") }

        assertEquals(DatabaseVersionState.Missing, state)
    }

    @Test fun `same and older versions are compatible`() {
        val file = temporary.newFile("user.db")

        assertEquals(DatabaseVersionState.Compatible(20, 20), databaseVersionState(file, supported = 20) { 20 })
        assertEquals(DatabaseVersionState.Compatible(0, 20), databaseVersionState(file, supported = 20) { 0 })
    }

    @Test fun `newer version is reported with both versions`() {
        val file = temporary.newFile("user.db")

        assertEquals(DatabaseVersionState.NewerThanApp(21, 20), databaseVersionState(file, supported = 20) { 21 })
    }

    @Test fun `read failure keeps its cause and leaves the file in place`() {
        val file = temporary.newFile("user.db").apply { writeText("payload") }
        val failure = IOException("locked")

        val state = databaseVersionState(file, supported = 20) { throw failure }

        assertSame(failure, (state as DatabaseVersionState.Unreadable).cause)
        assertTrue(file.exists())
        assertEquals("payload", file.readText())
    }

    @Test fun `default supported version is the final Room schema version`() {
        val file = temporary.newFile("user.db")

        val state = databaseVersionState(file) { NeriUserDataDatabase.FINAL_DB_VERSION + 1 }

        assertEquals(
            DatabaseVersionState.NewerThanApp(NeriUserDataDatabase.FINAL_DB_VERSION + 1, NeriUserDataDatabase.FINAL_DB_VERSION),
            state
        )
    }
}
