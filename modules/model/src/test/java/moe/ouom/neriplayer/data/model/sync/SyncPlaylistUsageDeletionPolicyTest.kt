package moe.ouom.neriplayer.data.model.sync

import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlaylistUsageDeletionPolicyTest {

    @Test
    fun `deletions merge per trimmed key with normalised tokens`() {
        val merged = SyncPlaylistUsageDeletionPolicy.merge(
            listOf(
                SyncPlaylistUsageDeletion(" b ", listOf(SyncCausalToken("device", 2L)), deletedAt = 10L),
                SyncPlaylistUsageDeletion("a", emptyList(), deletedAt = 5L),
                SyncPlaylistUsageDeletion("b", listOf(SyncCausalToken("device", 1L), SyncCausalToken("device", 2L)), deletedAt = 3L),
                SyncPlaylistUsageDeletion(" ", listOf(SyncCausalToken("device", 1L)), deletedAt = 9L),
                SyncPlaylistUsageDeletion("c", listOf(SyncCausalToken("", 4L)), deletedAt = 0L),
                SyncPlaylistUsageDeletion("d", listOf(SyncCausalToken("device", 7L)), deletedAt = -5L)
            )
        )

        assertEquals(
            listOf(
                SyncPlaylistUsageDeletion("a", listOf(SyncCausalToken("usage-legacy:0061", 5L)), deletedAt = 5L),
                SyncPlaylistUsageDeletion("b", listOf(SyncCausalToken("device", 1L), SyncCausalToken("device", 2L)), deletedAt = 10L),
                SyncPlaylistUsageDeletion("d", listOf(SyncCausalToken("device", 7L)), deletedAt = 0L)
            ),
            merged
        )
    }

    @Test
    fun `legacy deletions keep a causal token even without a timestamp`() {
        assertEquals(
            listOf(SyncPlaylistUsageDeletion("k", listOf(SyncCausalToken("usage-legacy:006b", 1L)), deletedAt = 0L)),
            SyncPlaylistUsageDeletionPolicy.fromLegacy(mapOf(" k " to 0L))
        )
    }

    @Test
    fun `observation requires every required token`() {
        val required = listOf(SyncCausalToken("device", 2L))

        assertTrue(SyncPlaylistUsageDeletionPolicy.observes(null, emptyList()))
        assertFalse(SyncPlaylistUsageDeletionPolicy.observes(null, required))
        assertFalse(SyncPlaylistUsageDeletionPolicy.observes(listOf(SyncCausalToken("device", 1L)), required))
        assertTrue(
            SyncPlaylistUsageDeletionPolicy.observes(
                listOf(SyncCausalToken("device", 2L), SyncCausalToken("other", 1L)),
                required
            )
        )
    }
}
