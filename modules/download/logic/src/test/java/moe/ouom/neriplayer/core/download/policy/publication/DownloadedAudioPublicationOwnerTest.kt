package moe.ouom.neriplayer.core.download.policy.publication

import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadedAudioPublicationOwnerTest {
    @Test
    fun `publication owner takes priority over retry operation and cleanup token`() {
        val metadata = DownloadedAudioMetadata(
            audioPublicationOwnerId = "published",
            operationId = "retry",
            terminalTemporaryWriteCleanupToken = "cleanup",
            stableKey = "song"
        )
        assertEquals("published", metadata.publicationOwnerId())
        assertEquals("retry", metadata.copy(audioPublicationOwnerId = " ").publicationOwnerId())
        assertEquals("cleanup", metadata.copy(audioPublicationOwnerId = null, operationId = " ")
            .publicationOwnerId())
        assertEquals("legacy:song", metadata.copy(audioPublicationOwnerId = null, operationId = null,
            terminalTemporaryWriteCleanupToken = " ").publicationOwnerId())
    }

    @Test
    fun `missing identities stay unknown and existing nonblank identities keep their bytes`() {
        assertNull(DownloadedAudioMetadata().publicationOwnerId())
        assertNull(DownloadedAudioMetadata(stableKey = " ").publicationOwnerId())
        assertEquals(" operation ", DownloadedAudioMetadata(operationId = " operation ").publicationOwnerId())
        assertEquals("legacy: song ", DownloadedAudioMetadata(stableKey = " song ").publicationOwnerId())
    }
}
