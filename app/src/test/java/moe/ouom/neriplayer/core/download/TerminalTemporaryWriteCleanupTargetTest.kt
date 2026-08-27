package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.download.storage.backend.ManagedTemporaryWriteCleanupResult
import moe.ouom.neriplayer.core.download.storage.backend.ManagedTemporaryWriteCleanupSkipReason
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupFinalizationPreparation
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupRoot
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupRootType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTemporaryWriteCleanupTargetTest {
    @Test
    fun `cancelled pending pair records audio and pending metadata targets`() {
        val entries = listOf(
            storedEntry("song.mp3$PENDING_AUDIO_WRITE_MARKER.operation.pending"),
            storedEntry("song.mp3.npmeta.pending.json")
        )

        assertEquals(
            listOf(
                "song.mp3$PENDING_AUDIO_WRITE_MARKER.operation.pending",
                "song.mp3",
                "song.mp3.npmeta.pending.json"
            ),
            ManagedDownloadStorage.terminalTemporaryWriteTargetNames(entries)
        )
    }

    @Test
    fun `active or incomplete terminal cleanup remains retryable`() {
        assertEquals(
            1,
            ManagedDownloadStorage.terminalTemporaryWriteCleanupFailureCount(
                result = ManagedTemporaryWriteCleanupResult.Completed(
                    deletedCount = 0,
                    missingCount = 0,
                    retainedActiveCount = 1,
                    failures = emptyList()
                ),
                targetCount = 3
            )
        )
        assertEquals(
            3,
            ManagedDownloadStorage.terminalTemporaryWriteCleanupFailureCount(
                result = ManagedTemporaryWriteCleanupResult.Skipped(
                    ManagedTemporaryWriteCleanupSkipReason.TargetParentMismatch
                ),
                targetCount = 3
            )
        )
    }

    @Test
    fun `legacy finalization token is a durable promotion identity`() {
        val preparation = TerminalTemporaryWriteCleanupFinalizationPreparation(
            root = TerminalTemporaryWriteCleanupRoot(
                type = TerminalTemporaryWriteCleanupRootType.FILE,
                identity = "/storage/emulated/0/Downloads"
            ),
            pendingAudioName = "song.mp3.npdl_pending.legacy.pending",
            finalAudioName = "song.mp3",
            expectedOperationId = null,
            expectedFinalizationToken = "finalization-token",
            targets = emptyList(),
            generationId = "generation"
        )

        assertTrue(
            ManagedDownloadStorage.matchesTerminalTemporaryWriteFinalizationIdentity(
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    terminalTemporaryWriteCleanupToken = "finalization-token"
                ),
                preparation = preparation
            )
        )
        assertFalse(
            ManagedDownloadStorage.matchesTerminalTemporaryWriteFinalizationIdentity(
                metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                    terminalTemporaryWriteCleanupToken = "another-token"
                ),
                preparation = preparation
            )
        )
    }

    private fun storedEntry(name: String) = ManagedDownloadStorage.StoredEntry(
        name = name,
        reference = "file:///downloads/$name",
        mediaUri = "",
        localFilePath = null,
        sizeBytes = 0L,
        lastModifiedMs = 0L
    )
}
