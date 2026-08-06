package moe.ouom.neriplayer.core.download.catalog

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSongBuilderCoverPolicyTest {

    @Test
    fun `restored remote cover does not fall back to a stale indexed sidecar`() {
        val originalCover = "https://example.com/original-cover.jpg"

        assertFalse(
            shouldUseIndexedDownloadedCoverFallback(
                ManagedDownloadStorage.DownloadedAudioMetadata(
                    coverUrl = originalCover,
                    originalCoverUrl = originalCover,
                    customCoverUrl = null,
                    coverPath = null
                )
            )
        )
    }

    @Test
    fun `ordinary downloads retain indexed cover fallback`() {
        assertTrue(
            shouldUseIndexedDownloadedCoverFallback(
                ManagedDownloadStorage.DownloadedAudioMetadata(
                    coverUrl = "https://example.com/cover.jpg"
                )
            )
        )
    }
}
