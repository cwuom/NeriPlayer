package moe.ouom.neriplayer.data.local.playlist

import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

class LocalPlaylistSyncMutationMergeTest {
    private val first = LocalPlaylistSyncMutation(
        expectedPrimaryDigest = "a".repeat(64),
        addedSongDeletions = listOf(SyncPlaylistSongDeletion(playlistId = 7, songId = 1, deletedAt = 10)),
        removedSongDeletions = listOf(PlaylistSongDeletionRemoval(8, listOf(SongIdentity(2, "local", null)))),
        deletedPlaylistIds = listOf(11, 12),
        clearedPlaylistDeletionIds = listOf(21),
        restoredPlaylistIds = listOf(31)
    )
    private val second = LocalPlaylistSyncMutation(
        addedSongDeletions = listOf(SyncPlaylistSongDeletion(playlistId = 7, songId = 3, deletedAt = 20)),
        removedSongDeletions = listOf(PlaylistSongDeletionRemoval(9, listOf(SongIdentity(4, "netease", null)))),
        deletedPlaylistIds = listOf(12, 13),
        clearedPlaylistDeletionIds = listOf(21, 22),
        restoredPlaylistIds = listOf(31, 32)
    )

    @Test
    fun `empty mutations are identities for plus`() {
        val empty = LocalPlaylistSyncMutation(expectedPrimaryDigest = "b".repeat(64))

        assertSame(first, empty + first)
        assertSame(first, first + empty)
    }

    @Test
    fun `merged mutations append deletions and dedupe playlist ids`() {
        val merged = first + second

        assertEquals(first.addedSongDeletions + second.addedSongDeletions, merged.addedSongDeletions)
        assertEquals(first.removedSongDeletions + second.removedSongDeletions, merged.removedSongDeletions)
        assertEquals(listOf(11L, 12L, 13L), merged.deletedPlaylistIds)
        assertEquals(listOf(21L, 22L), merged.clearedPlaylistDeletionIds)
        assertEquals(listOf(31L, 32L), merged.restoredPlaylistIds)
    }

    @Test
    fun `secure store applies every part of a mutation`() {
        val storage = mock(SecureTokenStorage::class.java)

        SecureLocalPlaylistSyncMutationStore(storage).apply(first + second)

        val ordered = inOrder(storage)
        ordered.verify(storage).addPlaylistSongDeletions(first.addedSongDeletions + second.addedSongDeletions)
        ordered.verify(storage).removePlaylistSongDeletions(8, listOf(SongIdentity(2, "local", null)))
        ordered.verify(storage).removePlaylistSongDeletions(9, listOf(SongIdentity(4, "netease", null)))
        ordered.verify(storage).addDeletedPlaylistId(11)
        ordered.verify(storage).addDeletedPlaylistId(12)
        ordered.verify(storage).addDeletedPlaylistId(13)
        ordered.verify(storage).removePlaylistSongDeletionsForPlaylist(21)
        ordered.verify(storage).removePlaylistSongDeletionsForPlaylist(22)
        ordered.verify(storage).removeDeletedPlaylistIds(setOf(31L, 32L))
        verifyNoMoreInteractions(storage)
    }

    @Test
    fun `secure store only clears restorations for an empty mutation`() {
        val storage = mock(SecureTokenStorage::class.java)

        SecureLocalPlaylistSyncMutationStore(storage).apply(LocalPlaylistSyncMutation())

        verify(storage).removeDeletedPlaylistIds(emptySet())
        verifyNoMoreInteractions(storage)
    }
}
