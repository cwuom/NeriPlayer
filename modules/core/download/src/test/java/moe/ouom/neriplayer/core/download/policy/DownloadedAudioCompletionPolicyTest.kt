package moe.ouom.neriplayer.core.download.policy

import moe.ouom.neriplayer.data.model.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedAudioCompletionPolicyTest {
    @Test
    fun `metadata without accepted embedding proof remains unfinalized`() {
        assertTrue(
            isUnfinalizedDownloadedMetadata(
                DownloadedAudioMetadata(downloadFinalized = false)
            )
        )
        assertTrue(
            isUnfinalizedDownloadedMetadata(
                DownloadedAudioMetadata(downloadFinalized = true)
            )
        )
        assertTrue(
            isUnfinalizedDownloadedMetadata(
                DownloadedAudioMetadata(downloadFinalized = null)
            )
        )
        assertTrue(
            isUnfinalizedDownloadedMetadata(
                DownloadedAudioMetadata(
                    downloadFinalized = true,
                    metadataEmbeddingState = DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED
                )
            )
        )
        assertTrue(
            isUnfinalizedDownloadedMetadata(
                DownloadedAudioMetadata(
                    downloadFinalized = false,
                    metadataEmbeddingState = DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER
                )
            )
        )
        assertFalse(
            isUnfinalizedDownloadedMetadata(
                DownloadedAudioMetadata(
                    downloadFinalized = true,
                    metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
                )
            )
        )
        assertFalse(
            isUnfinalizedDownloadedMetadata(
                DownloadedAudioMetadata(
                    downloadFinalized = true,
                    metadataEmbeddingState = DownloadedAudioEmbeddingState.USER_DISABLED
                )
            )
        )
        assertTrue(isUnfinalizedDownloadedMetadata(null))
    }

    @Test
    fun `only explicit completion with accepted embedding evidence is finalized`() {
        assertTrue(
            isFinalizedDownloadedMetadata(
                DownloadedAudioMetadata(
                    downloadFinalized = true,
                    metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
                )
            )
        )
        assertTrue(
            isFinalizedDownloadedMetadata(
                DownloadedAudioMetadata(
                    downloadFinalized = true,
                    metadataEmbeddingState = DownloadedAudioEmbeddingState.USER_DISABLED
                )
            )
        )
        assertFalse(
            isFinalizedDownloadedMetadata(
                DownloadedAudioMetadata(
                    downloadFinalized = false,
                    metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
                )
            )
        )
        assertFalse(
            isFinalizedDownloadedMetadata(
                DownloadedAudioMetadata(
                    downloadFinalized = true,
                    metadataEmbeddingState = DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER
                )
            )
        )
        assertFalse(
            isFinalizedDownloadedMetadata(
                DownloadedAudioMetadata(
                    downloadFinalized = true,
                    metadataEmbeddingState = DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED
                )
            )
        )
        assertFalse(
            isFinalizedDownloadedMetadata(
                DownloadedAudioMetadata(downloadFinalized = true)
            )
        )
        assertFalse(isFinalizedDownloadedMetadata(null))
    }
}
