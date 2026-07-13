package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlaylistSongMergePolicyTest {
    @Test
    fun `local clear wins when local playlist changed after sync`() {
        val remoteSong = syncSong(id = 1L, name = "Song")

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = emptyList(),
            remoteSongs = listOf(remoteSong),
            localModifiedAt = 200L,
            remoteModifiedAt = 100L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = false,
            lastSyncTime = 150L,
            isFavorites = false
        )

        assertTrue(result.songs.isEmpty())
        assertEquals(false, result.isUpdated)
    }

    @Test
    fun `remote clear wins when remote playlist changed after sync`() {
        val localSong = syncSong(id = 1L, name = "Song")

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(localSong),
            remoteSongs = emptyList(),
            localModifiedAt = 100L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = false,
            remoteChangedAfterSync = true,
            lastSyncTime = 150L,
            isFavorites = false
        )

        assertTrue(result.songs.isEmpty())
        assertEquals(true, result.isUpdated)
    }

    @Test
    fun `local clear defers tokenized remote membership to deletion policy`() {
        val remoteSong = syncSong(
            id = 1L,
            name = "Restored",
            membershipTokens = listOf(token("remote", 2L))
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = emptyList(),
            remoteSongs = listOf(remoteSong),
            localModifiedAt = 300L,
            remoteModifiedAt = 100L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = false,
            lastSyncTime = 200L,
            isFavorites = false
        )

        assertEquals(listOf(remoteSong), result.songs)
    }

    @Test
    fun `remote clear defers tokenized local membership to deletion policy`() {
        val localSong = syncSong(
            id = 1L,
            name = "Restored",
            membershipTokens = listOf(token("local", 2L))
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(localSong),
            remoteSongs = emptyList(),
            localModifiedAt = 100L,
            remoteModifiedAt = 300L,
            localChangedAfterSync = false,
            remoteChangedAfterSync = true,
            lastSyncTime = 200L,
            isFavorites = false
        )

        assertEquals(listOf(localSong), result.songs)
    }

    @Test
    fun `same channel audio song is not duplicated when album changes`() {
        val localSong = syncSong(
            id = 1L,
            name = "Song",
            album = "Old Album",
            channelId = "netease",
            audioId = "1"
        )
        val remoteSong = syncSong(
            id = 1L,
            name = "Song",
            album = "New Album",
            channelId = "netease",
            audioId = "1"
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(localSong),
            remoteSongs = listOf(remoteSong),
            localModifiedAt = 200L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = true,
            lastSyncTime = 100L,
            isFavorites = false
        )

        assertEquals(listOf(localSong), result.songs)
    }

    @Test
    fun `duplicate songs in same snapshot are collapsed`() {
        val first = syncSong(id = 1L, name = "Song")
        val duplicate = syncSong(id = 1L, name = "Song")

        val result = SyncPlaylistSongMergePolicy.deduplicateSongs(listOf(first, duplicate))

        assertEquals(listOf(first), result)
    }

    @Test
    fun `single song membership tokens are normalized deterministically`() {
        val song = syncSong(
            id = 1L,
            name = "Song",
            membershipTokens = listOf(
                token("b", 1L),
                token("a", 2L),
                token("a", 1L),
                token("a", 2L)
            )
        )

        val result = SyncPlaylistSongMergePolicy.deduplicateSongs(listOf(song))

        assertEquals(
            listOf(
                song.copy(
                    syncMembershipTokens = listOf(
                        token("a", 1L),
                        token("a", 2L),
                        token("b", 1L)
                    )
                )
            ),
            result
        )
    }

    @Test
    fun `exact duplicate songs union membership tokens and keep first metadata`() {
        val first = syncSong(
            id = 1L,
            name = "Local metadata",
            membershipTokens = listOf(token("b", 1L), token("a", 100L))
        )
        val duplicate = syncSong(
            id = 1L,
            name = "Remote metadata",
            membershipTokens = listOf(token("b", 1L), token("c", 1L))
        )

        val result = SyncPlaylistSongMergePolicy.deduplicateSongs(listOf(first, duplicate))

        assertEquals(
            listOf(
                first.copy(
                    syncMembershipTokens = listOf(
                        token("a", 100L),
                        token("b", 1L),
                        token("c", 1L)
                    )
                )
            ),
            result
        )
    }

    @Test
    fun `two changed endpoints union exact membership tokens with local metadata`() {
        val local = syncSong(
            id = 1L,
            name = "Local metadata",
            membershipTokens = listOf(token("local", 1L))
        )
        val remote = syncSong(
            id = 1L,
            name = "Remote metadata",
            membershipTokens = listOf(token("remote", 1L))
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(local),
            remoteSongs = listOf(remote),
            localModifiedAt = 200L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = true,
            lastSyncTime = 100L,
            isFavorites = false
        )

        assertEquals(
            listOf(
                local.copy(
                    syncMembershipTokens = listOf(
                        token("local", 1L),
                        token("remote", 1L)
                    )
                )
            ),
            result.songs
        )
    }

    @Test
    fun `remote primary keeps metadata while merging exact local token`() {
        val local = syncSong(
            id = 1L,
            name = "Local metadata",
            membershipTokens = listOf(token("local", 1L))
        )
        val remote = syncSong(
            id = 1L,
            name = "Remote metadata",
            membershipTokens = listOf(token("remote", 1L))
        )

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = listOf(local),
            remoteSongs = listOf(remote),
            localModifiedAt = 100L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = false,
            remoteChangedAfterSync = true,
            lastSyncTime = 150L,
            isFavorites = false
        )

        assertEquals(
            listOf(
                remote.copy(
                    syncMembershipTokens = listOf(
                        token("local", 1L),
                        token("remote", 1L)
                    )
                )
            ),
            result.songs
        )
    }

    @Test
    fun `large local clear skips remote refill`() {
        val remoteSongs = (1..2_000).map { index ->
            syncSong(id = index.toLong(), name = "Song $index")
        }

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = emptyList(),
            remoteSongs = remoteSongs,
            localModifiedAt = 300L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = false,
            lastSyncTime = 250L,
            isFavorites = false
        )

        assertTrue(result.songs.isEmpty())
        assertEquals(false, result.isUpdated)
    }

    @Test
    fun `large disjoint lists merge without dropping songs`() {
        val localSongs = (1..2_000).map { index ->
            syncSong(id = index.toLong(), name = "Local $index")
        }
        val remoteSongs = (2_001..4_000).map { index ->
            syncSong(id = index.toLong(), name = "Remote $index")
        }

        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = localSongs,
            remoteSongs = remoteSongs,
            localModifiedAt = 200L,
            remoteModifiedAt = 200L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = true,
            lastSyncTime = 100L,
            isFavorites = false
        )

        assertEquals(4_000, result.songs.size)
        assertEquals(localSongs.first(), result.songs.first())
        assertEquals(remoteSongs.last(), result.songs.last())
    }

    @Test
    fun `fallback merge keeps different source hints`() {
        val neteaseSong = syncSong(
            id = 1L,
            name = "Song",
            album = "netease album",
            channelId = null
        )
        val biliSong = syncSong(
            id = 1L,
            name = "Song",
            album = "bilibili album",
            channelId = null
        )

        val result = SyncPlaylistSongMergePolicy.deduplicateSongs(listOf(neteaseSong, biliSong))

        assertEquals(listOf(neteaseSong, biliSong), result)
    }

    @Test
    fun `empty snapshots are not marked updated`() {
        val result = SyncPlaylistSongMergePolicy.mergeSongs(
            localSongs = emptyList(),
            remoteSongs = emptyList(),
            localModifiedAt = 200L,
            remoteModifiedAt = 100L,
            localChangedAfterSync = true,
            remoteChangedAfterSync = false,
            lastSyncTime = 150L,
            isFavorites = false
        )

        assertTrue(result.songs.isEmpty())
        assertEquals(false, result.isUpdated)
    }

    private fun syncSong(
        id: Long,
        name: String,
        album: String = "Album",
        artist: String = "Artist",
        channelId: String? = null,
        audioId: String? = null,
        membershipTokens: List<SyncCausalToken> = emptyList()
    ): SyncSong {
        return SyncSong(
            id = id,
            name = name,
            artist = artist,
            album = album,
            channelId = channelId,
            audioId = audioId,
            syncMembershipTokens = membershipTokens
        )
    }

    private fun token(deviceId: String, counter: Long): SyncCausalToken {
        return SyncCausalToken(deviceId = deviceId, counter = counter)
    }
}
