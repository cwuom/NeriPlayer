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
    fun `same operation keeps ownership until every lease is finished`() {
        val ownership = AudioDownloadReferenceOwnership()
        ownership.begin("song", "operation", attemptId = 1L)
        ownership.begin("song", "operation", attemptId = 1L)

        ownership.finish("song", "operation")

        assertTrue(ownership.allows("song", "operation"))
        ownership.finish("song", "operation")
        assertFalse(ownership.allows("song", "operation"))
    }

    @Test
    fun `enrichment claims an idle song but cannot preempt another operation`() {
        val ownership = AudioDownloadReferenceOwnership()

        assertTrue(ownership.claimForEnrichment("song", "enrichment"))
        assertFalse(ownership.claimForEnrichment("song", "replacement"))
        ownership.revoke("song", setOf("enrichment"))
        assertTrue(ownership.claimForEnrichment("song", "replacement"))
    }

    @Test
    fun `revoking one operation does not remove replacement owner`() {
        val ownership = AudioDownloadReferenceOwnership()
        ownership.begin("song", "old-operation", attemptId = 1L)
        ownership.begin("song", "new-operation", attemptId = 2L)

        ownership.revoke("song", setOf("old-operation"))

        assertTrue(ownership.allows("song", "new-operation"))
    }

    @Test
    fun `global revoke removes every stale owner`() {
        val ownership = AudioDownloadReferenceOwnership()
        ownership.begin("song-a", "operation-a", attemptId = 1L)
        ownership.begin("song-b", "operation-b", attemptId = 2L)

        ownership.revokeAll()

        assertFalse(ownership.allows("song-a", "operation-a"))
        assertFalse(ownership.allows("song-b", "operation-b"))
    }

    @Test
    fun `unscoped cleanup remains allowed for user and recovery paths`() {
        val ownership = AudioDownloadReferenceOwnership()

        assertTrue(ownership.allows("song"))
    }
}
