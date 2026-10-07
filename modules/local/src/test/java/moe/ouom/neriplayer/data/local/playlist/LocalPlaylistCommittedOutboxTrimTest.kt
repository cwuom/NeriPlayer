package moe.ouom.neriplayer.data.local.playlist

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPlaylistCommittedOutboxTrimTest : LocalPlaylistRepositoryTestSupport() {

    @Test
    fun `mutations after the last committed digest are dropped`() {
        val repository = repository()
        val outbox = outbox("digest-a", "digest-b", "digest-c")

        val trimmed = repository.trimCommittedSyncMutationOutbox(outbox, committedDomainDigest = "digest-b", legacyPrimaryText = null)

        assertEquals(outbox("digest-a", "digest-b"), trimmed)
    }

    @Test
    fun `a legacy primary digest also marks mutations as committed`() {
        val repository = repository()
        val legacyPrimary = "[{\"id\":1,\"name\":\"Road trip\"}]"
        val outbox = outbox("digest-a", sha256(legacyPrimary), "digest-c")

        val byLegacy = repository.trimCommittedSyncMutationOutbox(outbox, "digest-z", legacyPrimaryText = legacyPrimary)
        val byLatest = repository.trimCommittedSyncMutationOutbox(outbox, "digest-c", legacyPrimaryText = legacyPrimary)

        assertEquals(outbox("digest-a", sha256(legacyPrimary)), byLegacy)
        assertEquals(outbox, byLatest)
    }

    @Test
    fun `outboxes without a committed mutation are discarded`() {
        val repository = repository()

        assertNull(repository.trimCommittedSyncMutationOutbox(outbox("digest-a"), "digest-z", legacyPrimaryText = "[]"))
        assertNull(repository.trimCommittedSyncMutationOutbox(outbox("digest-a"), "digest-z", legacyPrimaryText = null))
        assertNull(repository.trimCommittedSyncMutationOutbox(outbox(), "digest-a", legacyPrimaryText = null))
    }

    private fun outbox(vararg expectedDigests: String) = LocalPlaylistSyncMutationOutbox(
        mutations = expectedDigests.mapIndexed { index, digest ->
            LocalPlaylistSyncMutation(expectedPrimaryDigest = digest, deletedPlaylistIds = listOf(index + 1L))
        }
    )

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun repository() = LocalPlaylistRepository.createForTest(
        context = mockContext(),
        file = File(tempFolder.root, "outbox_trim.json"),
        storage = RecordingStorage(primary = null)
    )
}
