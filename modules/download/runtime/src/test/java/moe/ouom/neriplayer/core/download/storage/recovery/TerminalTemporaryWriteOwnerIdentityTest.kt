package moe.ouom.neriplayer.core.download.storage.recovery

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalTemporaryWriteOwnerIdentityTest {
    @Test
    fun `an expected operation id matches its own receipt before the finalization token`() {
        val byOperation = preparation(expectedOperationId = "operation-1", expectedFinalizationToken = null)
        val byOperationOrToken = preparation(expectedOperationId = "operation-1", expectedFinalizationToken = "token-1")

        assertTrue(matches(DownloadedAudioMetadata(operationId = "operation-1"), byOperation))
        assertFalse(
            matches(
                DownloadedAudioMetadata(operationId = "operation-2", terminalTemporaryWriteCleanupToken = "token-1"),
                byOperation
            )
        )
        assertTrue(
            matches(
                DownloadedAudioMetadata(operationId = "operation-2", terminalTemporaryWriteCleanupToken = "token-1"),
                byOperationOrToken
            )
        )
        assertFalse(
            matches(
                DownloadedAudioMetadata(operationId = "operation-2", terminalTemporaryWriteCleanupToken = "token-2"),
                byOperationOrToken
            )
        )
    }

    @Test
    fun `only pending receipts produce cleanup targets and identities scope an owner target`() {
        val pendingReceipt = storedEntry("song.mp3.npmeta.pending.json")
        val entries = listOf(storedEntry("song.mp3"), storedEntry("song.mp3.npmeta.json"), pendingReceipt)

        assertEquals(
            listOf(
                TerminalTemporaryWriteCleanupTarget(displayName = "song.mp3"),
                TerminalTemporaryWriteCleanupTarget(displayName = "song.mp3.npmeta.pending.json"),
                TerminalTemporaryWriteCleanupTarget(
                    displayName = "song.mp3.npmeta.pending.json",
                    temporaryWriteOwnerName = "song.mp3.npmeta.pending.json\u0000operation-1"
                )
            ),
            ManagedDownloadStorage.terminalTemporaryWriteCleanupTargets(
                entries = entries,
                temporaryWriteIdentityByMetadataReference = mapOf(pendingReceipt.reference to " operation-1 ")
            )
        )
        assertEquals(
            listOf(
                TerminalTemporaryWriteCleanupTarget(displayName = "song.mp3"),
                TerminalTemporaryWriteCleanupTarget(displayName = "song.mp3.npmeta.pending.json")
            ),
            ManagedDownloadStorage.terminalTemporaryWriteCleanupTargets(
                entries = entries,
                temporaryWriteIdentityByMetadataReference = mapOf(pendingReceipt.reference to "   ")
            )
        )
    }

    private fun matches(
        metadata: DownloadedAudioMetadata,
        preparation: TerminalTemporaryWriteCleanupFinalizationPreparation
    ) = ManagedDownloadStorage.matchesTerminalTemporaryWriteFinalizationIdentity(metadata, preparation)

    private fun preparation(expectedOperationId: String?, expectedFinalizationToken: String?) =
        TerminalTemporaryWriteCleanupFinalizationPreparation(
            root = TerminalTemporaryWriteCleanupRoot(
                type = TerminalTemporaryWriteCleanupRootType.FILE,
                identity = "/storage/emulated/0/Download/NeriPlayer"
            ),
            pendingAudioName = "song.mp3.npdl_pending.operation-1.pending",
            finalAudioName = "song.mp3",
            expectedOperationId = expectedOperationId,
            expectedFinalizationToken = expectedFinalizationToken,
            targets = emptyList(),
            generationId = "generation-1"
        )

    private fun storedEntry(name: String) = ManagedDownloadStorage.StoredEntry(
        name = name,
        reference = "file:///downloads/$name",
        mediaUri = "",
        localFilePath = null,
        sizeBytes = 0L,
        lastModifiedMs = 0L
    )
}
