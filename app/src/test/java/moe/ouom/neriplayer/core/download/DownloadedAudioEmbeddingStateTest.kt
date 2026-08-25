package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedAudioEmbeddingStateTest {
    @Test
    fun `only verified embedding and explicit user opt out are accepted`() {
        assertTrue(
            isAcceptedDownloadedAudioEmbeddingState(
                DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
            )
        )
        assertTrue(
            isAcceptedDownloadedAudioEmbeddingState(
                DownloadedAudioEmbeddingState.USER_DISABLED
            )
        )
        assertFalse(
            isAcceptedDownloadedAudioEmbeddingState(
                DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER
            )
        )
        assertFalse(
            isAcceptedDownloadedAudioEmbeddingState(
                DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED
            )
        )
        assertFalse(isAcceptedDownloadedAudioEmbeddingState(null))
    }

    @Test
    fun `published audio requires a complete root non pending file and strict metadata`() {
        val verifiedMetadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            downloadFinalized = true,
            metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
        )
        val userDisabledMetadata = verifiedMetadata.copy(
            metadataEmbeddingState = DownloadedAudioEmbeddingState.USER_DISABLED
        )

        assertTrue(
            isFinalizedDownloadedAudioEntry(
                rootEntriesComplete = true,
                isPendingAudioWrite = false,
                metadata = verifiedMetadata
            )
        )
        assertTrue(
            isFinalizedDownloadedAudioEntry(
                rootEntriesComplete = true,
                isPendingAudioWrite = false,
                metadata = userDisabledMetadata
            )
        )
        assertFalse(
            isFinalizedDownloadedAudioEntry(
                rootEntriesComplete = false,
                isPendingAudioWrite = false,
                metadata = verifiedMetadata
            )
        )
        assertFalse(
            isFinalizedDownloadedAudioEntry(
                rootEntriesComplete = true,
                isPendingAudioWrite = true,
                metadata = verifiedMetadata
            )
        )
        assertFalse(
            isFinalizedDownloadedAudioEntry(
                rootEntriesComplete = true,
                isPendingAudioWrite = false,
                metadata = verifiedMetadata.copy(
                    metadataEmbeddingState = DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER
                )
            )
        )
        assertFalse(
            isFinalizedDownloadedAudioEntry(
                rootEntriesComplete = true,
                isPendingAudioWrite = false,
                metadata = null
            )
        )
    }

    @Test
    fun `completion state cannot promote unsupported or legacy metadata`() {
        assertEquals(
            DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER,
            resolvePersistedDownloadedAudioEmbeddingState(
                downloadFinalized = true,
                requestedState = DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER,
                existingState = null
            )
        )
        assertEquals(
            DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED,
            resolvePersistedDownloadedAudioEmbeddingState(
                downloadFinalized = true,
                requestedState = DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED,
                existingState = null
            )
        )
    }

    @Test
    fun `missing completion state persists as legacy unverified instead of success`() {
        assertEquals(
            DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED,
            resolvePersistedDownloadedAudioEmbeddingState(
                downloadFinalized = true,
                requestedState = null,
                existingState = null
            )
        )
    }

    @Test
    fun `unsupported container state survives an unfinalized retry record`() {
        assertEquals(
            DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER,
            resolvePersistedDownloadedAudioEmbeddingState(
                downloadFinalized = false,
                requestedState = DownloadedAudioEmbeddingState.UNSUPPORTED_CONTAINER,
                existingState = null
            )
        )
        assertEquals(
            DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED,
            resolvePersistedDownloadedAudioEmbeddingState(
                downloadFinalized = false,
                requestedState = null,
                existingState = DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED
            )
        )
        assertNull(
            resolvePersistedDownloadedAudioEmbeddingState(
                downloadFinalized = false,
                requestedState = null,
                existingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED
            )
        )
    }

    @Test
    fun `persisted states ignore case and reject unknown values`() {
        assertEquals(
            DownloadedAudioEmbeddingState.USER_DISABLED,
            DownloadedAudioEmbeddingState.fromPersisted(" user_disabled ")
        )
        assertNull(DownloadedAudioEmbeddingState.fromPersisted("future_state"))
        assertNull(DownloadedAudioEmbeddingState.fromPersisted(null))
    }
}
