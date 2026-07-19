package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.model.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.model.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlaylistObservedRemoveIntegrationTest {
    @Test
    fun `restored membership survives old clear and remains deletable`() {
        val oldMembership = SyncCausalToken("device", 1L)
        val restoredMembership = SyncCausalToken("device", 2L)
        val restoredSong = SyncSong(
            id = 11L,
            name = "restored",
            artist = "artist",
            album = "netease",
            addedAt = 100L,
            syncMembershipTokens = listOf(restoredMembership)
        )

        val mergedSongs = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(restoredSong),
            remoteSongs = emptyList(),
            localModifiedAt = 100L,
            remoteModifiedAt = 10_000L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = true,
            lastSyncTime = 50L,
            isFavorites = false
        ).songs
        val oldDeletion = deletion(removedTokens = listOf(oldMembership))

        val restoredResult = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = mergedSongs,
            deletions = listOf(oldDeletion)
        )

        assertEquals(listOf(restoredSong), restoredResult)

        val laterDeletion = deletion(
            deletedAt = 20_000L,
            removedTokens = listOf(restoredMembership)
        )
        val deletedResult = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = restoredResult,
            deletions = listOf(oldDeletion, laterDeletion)
        )

        assertTrue(deletedResult.isEmpty())
    }

    private fun deletion(
        deletedAt: Long = 200L,
        removedTokens: List<SyncCausalToken>
    ): SyncPlaylistSongDeletion {
        return SyncPlaylistSongDeletion(
            playlistId = 7L,
            songId = 11L,
            album = "netease",
            deletedAt = deletedAt,
            deviceId = "device",
            removedMembershipTokens = removedTokens
        )
    }
}
