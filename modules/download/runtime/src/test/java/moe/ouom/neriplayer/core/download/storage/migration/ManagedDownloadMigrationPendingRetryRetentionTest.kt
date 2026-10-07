package moe.ouom.neriplayer.core.download.storage.migration

import java.io.IOException
import moe.ouom.neriplayer.core.download.storage.MIGRATION_PENDING_ARTIFACT_BLOCKED_ERROR_CODE
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationPendingRetryRetentionTest {
    private val blockedMessage = "$MIGRATION_PENDING_ARTIFACT_BLOCKED_ERROR_CODE: song.flac.pending"

    @Test
    fun `a retryable pending artifact block deep in the cause chain keeps the retry`() {
        val error = IllegalStateException(
            "worker failed",
            IOException("copy failed", ManagedDownloadMigrationException(blockedMessage, retryable = true))
        )

        assertTrue(shouldRetainMigrationPendingRetry(error))
    }

    @Test
    fun `other migration failures do not keep the pending retry`() {
        listOf(
            ManagedDownloadMigrationException(blockedMessage, retryable = false),
            ManagedDownloadMigrationException("target directory is read only", retryable = true),
            IOException(blockedMessage),
            IllegalStateException("plain failure")
        ).forEach { error ->
            assertFalse(error.toString(), shouldRetainMigrationPendingRetry(error))
        }
    }
}
