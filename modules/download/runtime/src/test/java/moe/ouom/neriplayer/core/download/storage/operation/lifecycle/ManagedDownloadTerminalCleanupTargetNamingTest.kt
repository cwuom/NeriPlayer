package moe.ouom.neriplayer.core.download.storage.operation.lifecycle

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.PENDING_AUDIO_WRITE_MARKER
import moe.ouom.neriplayer.core.download.storage.recovery.TerminalTemporaryWriteCleanupTarget
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedDownloadTerminalCleanupTargetNamingTest {
    private val storage = ManagedDownloadStorage

    @Test
    fun `cleanup target names must be a single trimmed path segment`() {
        assertEquals("song.flac", storage.normalizeTerminalTemporaryWriteTargetName("  song.flac \n"))
        listOf("", "   ", ".", "..", " .. ", "Lyrics/song.lrc", "..\\song.flac").forEach { rawName ->
            assertNull(rawName, storage.normalizeTerminalTemporaryWriteTargetName(rawName))
        }
    }

    @Test
    fun `cleanup targets keep only a meaningful temporary write owner`() {
        assertEquals(
            TerminalTemporaryWriteCleanupTarget("song.flac", "song.flac\u0000op-1"),
            storage.normalizeTerminalTemporaryWriteCleanupTarget(
                TerminalTemporaryWriteCleanupTarget(" song.flac ", " song.flac\u0000op-1 ")
            )
        )
        assertEquals(
            TerminalTemporaryWriteCleanupTarget("song.flac"),
            storage.normalizeTerminalTemporaryWriteCleanupTarget(TerminalTemporaryWriteCleanupTarget("song.flac", "  "))
        )
        assertEquals(
            TerminalTemporaryWriteCleanupTarget("song.flac"),
            storage.normalizeTerminalTemporaryWriteCleanupTarget(TerminalTemporaryWriteCleanupTarget("song.flac"))
        )
        assertNull(
            storage.normalizeTerminalTemporaryWriteCleanupTarget(TerminalTemporaryWriteCleanupTarget("../song.flac", "owner"))
        )
    }

    @Test
    fun `temporary write owner binds the target name to its operation identity`() {
        assertEquals(
            "song.flac.npmeta.pending.json\u0000op-7",
            storage.temporaryWriteOwnerNameForIdentity(" song.flac.npmeta.pending.json ", " op-7 ")
        )
        assertNull(storage.temporaryWriteOwnerNameForIdentity("song.flac", null))
        assertNull(storage.temporaryWriteOwnerNameForIdentity("song.flac", " "))
        assertNull(storage.temporaryWriteOwnerNameForIdentity("Lyrics/song.flac", "op-7"))
    }

    @Test
    fun `finalization cleanup covers the pending write the final audio and both metadata files`() {
        val pendingName = "song.flac$PENDING_AUDIO_WRITE_MARKER.op-7.pending"

        val targets = storage.terminalTemporaryWriteCleanupTargetsForFinalization(
            pendingAudio = storedEntry(pendingName),
            metadata = DownloadedAudioMetadata(operationId = " op-7 ")
        )

        assertEquals(
            listOf(
                TerminalTemporaryWriteCleanupTarget(pendingName),
                TerminalTemporaryWriteCleanupTarget("song.flac"),
                TerminalTemporaryWriteCleanupTarget("song.flac.npmeta.json"),
                TerminalTemporaryWriteCleanupTarget("song.flac.npmeta.pending.json"),
                TerminalTemporaryWriteCleanupTarget(
                    displayName = "song.flac.npmeta.pending.json",
                    temporaryWriteOwnerName = "song.flac.npmeta.pending.json\u0000op-7"
                )
            ),
            targets
        )
    }

    @Test
    fun `finalization without a pending write or identity targets only the published names`() {
        val targets = storage.terminalTemporaryWriteCleanupTargetsForFinalization(
            pendingAudio = storedEntry("song.flac"),
            metadata = DownloadedAudioMetadata()
        )

        assertEquals(
            listOf(
                TerminalTemporaryWriteCleanupTarget("song.flac"),
                TerminalTemporaryWriteCleanupTarget("song.flac.npmeta.json"),
                TerminalTemporaryWriteCleanupTarget("song.flac.npmeta.pending.json")
            ),
            targets
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
