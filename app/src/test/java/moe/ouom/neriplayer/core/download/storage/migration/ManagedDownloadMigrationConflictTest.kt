package moe.ouom.neriplayer.core.download.storage.migration

import java.io.ByteArrayInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationConflictTest {
    @Test
    fun `same size different bytes are not equivalent migration content`() {
        assertFalse(
            migrationContentMatches(
                ByteArrayInputStream("source".toByteArray()),
                ByteArrayInputStream("target".toByteArray())
            )
        )
    }

    @Test
    fun `identical migration content is equivalent`() {
        assertTrue(
            migrationContentMatches(
                ByteArrayInputStream("same".toByteArray()),
                ByteArrayInputStream("same".toByteArray())
            )
        )
    }
}
