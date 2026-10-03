package moe.ouom.neriplayer.data.sync.policy

import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SyncMetadataAllocationTest {
    @Test
    fun `clean songs preserve their instance and every cover field is still sanitized`() {
        val clean = SyncSong(id = 1L, coverUrl = "https://example.org/cover.jpg")
        assertSame(clean, clean.sanitizeCoverUrlsForSync())
        val cases = listOf(
            clean.copy(coverUrl = "file:///cover.jpg"),
            clean.copy(customCoverUrl = "content://cover"),
            clean.copy(originalCoverUrl = "/private/cover.jpg")
        )
        for (song in cases) {
            val sanitized = song.sanitizeCoverUrlsForSync()
            assertEquals(null, sanitized.customCoverUrl)
            assertEquals(null, sanitized.originalCoverUrl)
            assertEquals(if (song.coverUrl?.startsWith("file:") == true) null else clean.coverUrl, sanitized.coverUrl)
            assertEquals(song.id, sanitized.id)
        }
    }

    @Test
    fun `normalized membership is reused but explicit metadata overrides remain effective`() {
        val token = SyncCausalToken("device", 1L)
        val song = SyncSong(id = 1L, mediaUri = "remote", addedAt = 10L, legacyAddedAt = 5L)
        assertSame(song, song.copyWithNormalizedMembershipTokens())
        val observed = song.copy(syncMembershipTokens = listOf(token))
        assertSame(observed, observed.copyWithNormalizedMembershipTokens())
        assertEquals("updated", song.copyWithNormalizedMembershipTokens(mediaUri = "updated").mediaUri)
        assertEquals(20L, song.copyWithNormalizedMembershipTokens(addedAt = 20L).addedAt)
        assertEquals(null, song.copyWithNormalizedMembershipTokens(legacyAddedAt = null).legacyAddedAt)
        val dirty = observed.copy(syncMembershipTokens = listOf(token, SyncCausalToken(), token))
        assertEquals(listOf(token), dirty.copyWithNormalizedMembershipTokens().syncMembershipTokens)
    }

    @Test
    fun `deletion normalization preserves clean records and removes invalid or duplicate tokens`() {
        val token = SyncCausalToken("device", 1L)
        val deletion = SyncPlaylistSongDeletion(playlistId = 1L, songId = 2L, mediaUri = "remote", deletedAt = 20L)
        assertSame(deletion, deletion.copyWithNormalizedMembershipTokens())
        val observed = deletion.copy(removedMembershipTokens = listOf(token))
        assertSame(observed, observed.copyWithNormalizedMembershipTokens())
        assertEquals(null, observed.copyWithNormalizedMembershipTokens(mediaUri = null).mediaUri)
        val dirty = observed.copy(removedMembershipTokens = listOf(token, SyncCausalToken(), token))
        assertEquals(listOf(token), dirty.copyWithNormalizedMembershipTokens().removedMembershipTokens)
    }
}
