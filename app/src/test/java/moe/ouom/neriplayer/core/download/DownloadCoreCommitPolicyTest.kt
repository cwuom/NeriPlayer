package moe.ouom.neriplayer.core.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCoreCommitPolicyTest {
    @Test
    fun `cancel before core commit may rollback operation owned audio`() {
        assertTrue(
            shouldRollbackCancelledAudio(
                DownloadCoreCommitPhase.STAGING
            )
        )
        assertFalse(
            shouldRollbackCancelledAudio(
                DownloadCoreCommitPhase.COMMITTING
            )
        )
    }

    @Test
    fun `late cancel after core commit preserves playable audio`() {
        assertFalse(
            shouldRollbackCancelledAudio(
                DownloadCoreCommitPhase.CORE_COMMITTED
            )
        )
    }

    @Test
    fun `metadata state protects durable audio from late cancellation`() {
        assertTrue(shouldPreserveAudioAfterCancellation(false, "CORE_COMMITTED"))
        assertTrue(shouldPreserveAudioAfterCancellation(false, "ASSETS_ENRICHING"))
        assertTrue(shouldPreserveAudioAfterCancellation(true, "COMMITTING"))
        assertFalse(shouldPreserveAudioAfterCancellation(false, "COMMITTING"))
        assertFalse(shouldPreserveAudioAfterCancellation(false, null))
    }
}
