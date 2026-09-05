package moe.ouom.neriplayer.core.player.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDownloadReferenceOwnershipTest {
    @Test
    fun `old operation loses reference ownership after replacement`() {
        val ownership = AudioDownloadReferenceOwnership()
        ownership.begin("song", "old-operation", attemptId = 1L)
        ownership.begin("song", "new-operation", attemptId = 2L)

        assertFalse(ownership.allows("song", "old-operation"))
        assertFalse(ownership.allows("song", "old-operation", attemptId = 1L))
        assertTrue(ownership.allows("song", "new-operation", attemptId = 2L))
    }

    @Test
    fun `finishing old operation cannot remove replacement owner`() {
        val ownership = AudioDownloadReferenceOwnership()
        ownership.begin("song", "old-operation", attemptId = 1L)
        ownership.begin("song", "new-operation", attemptId = 2L)
        ownership.finish("song", "old-operation")

        assertTrue(ownership.allows("song", "new-operation"))
    }

    @Test
    fun `unscoped cleanup remains allowed for user and recovery paths`() {
        val ownership = AudioDownloadReferenceOwnership()

        assertTrue(ownership.allows("song"))
    }
}
