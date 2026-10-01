package moe.ouom.neriplayer.core.download.policy.publication

import moe.ouom.neriplayer.data.model.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedAudioCompletionPolicyTest {
    @Test
    fun `unfinalized writes retain only unverified or unsupported embedding evidence`() {
        assertEquals(DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER,
            resolvePersistedDownloadedAudioEmbeddingState(false,
                DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER,
                DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED))
        assertEquals(DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED,
            resolvePersistedDownloadedAudioEmbeddingState(false, null,
                DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED))
        assertEquals(DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER,
            resolvePersistedDownloadedAudioEmbeddingState(false,
                DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED,
                DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER))
        assertNull(resolvePersistedDownloadedAudioEmbeddingState(false,
            DownloadedAudioEmbeddingState.USER_DISABLED, DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED))
        assertNull(resolvePersistedDownloadedAudioEmbeddingState(false, null, null))
    }

    @Test
    fun `finalized writes prefer requested evidence and mark missing evidence as legacy unverified`() {
        assertEquals(DownloadedAudioEmbeddingState.USER_DISABLED,
            resolvePersistedDownloadedAudioEmbeddingState(true,
                DownloadedAudioEmbeddingState.USER_DISABLED, DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED))
        assertEquals(DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED,
            resolvePersistedDownloadedAudioEmbeddingState(true, null, DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED))
        assertEquals(DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED,
            resolvePersistedDownloadedAudioEmbeddingState(true, null, null))
    }

    @Test
    fun `pending audio publication cannot be mistaken for a finalized entry`() {
        val metadata = DownloadedAudioMetadata(downloadFinalized = true,
            metadataEmbeddingState = DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED)
        assertTrue(isFinalizedDownloadedAudioEntry(true, false, metadata))
        assertFalse(isFinalizedDownloadedAudioEntry(false, false, metadata))
        assertFalse(isFinalizedDownloadedAudioEntry(true, true, metadata))
        assertFalse(isFinalizedDownloadedAudioEntry(true, false, metadata.copy(audioPublicationPending = true)))
        assertFalse(isFinalizedDownloadedAudioEntry(true, false, null))
    }

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
