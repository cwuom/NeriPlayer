package moe.ouom.neriplayer.data.sync.github

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
        audioId: String? = null
    ): SyncSong {
        return SyncSong(
            id = id,
            name = name,
            artist = artist,
            album = album,
            channelId = channelId,
            audioId = audioId
        )
    }
}
