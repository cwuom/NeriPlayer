package moe.ouom.neriplayer.core.download.storage.migration.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationSourceRetentionTest {
    @Test
    fun `source is kept while the copied target size is unknown or empty`() {
        assertTrue(keepSource(sourceSize = 100L, copiedSize = 0L))
        assertTrue(keepSource(sourceSize = 0L, copiedSize = -1L))
    }

    @Test
    fun `source is released only when the copy matches within the saf tolerance`() {
        assertFalse(keepSource(sourceSize = 100L, copiedSize = 100L))
        assertFalse(keepSource(sourceSize = 101L, copiedSize = 100L))
        assertTrue(keepSource(sourceSize = 102L, copiedSize = 100L))
        assertTrue(keepSource(sourceSize = 100L, copiedSize = 102L))
    }

    private fun keepSource(sourceSize: Long, copiedSize: Long): Boolean =
        ManagedDownloadMigrationFinalizer.shouldKeepSourceForMigrationSize(sourceSize, copiedSize)
}
