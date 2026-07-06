package moe.ouom.neriplayer.data.sync.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlaylistDeletionPolicyTest {
    @Test
    fun `latest deletion wins for same playlist song`() {
        val first = deletion(playlistId = 7L, songId = 11L, deletedAt = 100L, deviceId = "a")
        val latest = deletion(playlistId = 7L, songId = 11L, deletedAt = 200L, deviceId = "b")

        val merged = SyncPlaylistDeletionPolicy.mergeDeletions(
            local = listOf(first),
            remote = listOf(latest)
        )

        assertEquals(listOf(latest), merged)
    }

    @Test
    fun `stale song is filtered by deletion`() {
        val songs = listOf(syncSong(id = 11L, addedAt = 100L))
        val deletions = listOf(deletion(playlistId = 7L, songId = 11L, deletedAt = 200L))

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = songs,
            deletions = deletions
        )

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `readded song survives newer than deletion`() {
        val readded = syncSong(id = 11L, addedAt = 300L)
        val deletions = listOf(deletion(playlistId = 7L, songId = 11L, deletedAt = 200L))

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = listOf(readded),
            deletions = deletions
        )

        assertEquals(listOf(readded), merged)
    }

    @Test
    fun `deletion only affects matching playlist`() {
        val song = syncSong(id = 11L, addedAt = 100L)
        val deletions = listOf(deletion(playlistId = 8L, songId = 11L, deletedAt = 200L))

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = listOf(song),
            deletions = deletions
        )

        assertEquals(listOf(song), merged)
    }

    @Test
    fun `resolved readd prunes deletion`() {
        val deletions = listOf(deletion(playlistId = 7L, songId = 11L, deletedAt = 200L))
        val playlists = listOf(
            SyncPlaylist(
                id = 7L,
                name = "playlist",
                songs = listOf(syncSong(id = 11L, addedAt = 300L)),
                createdAt = 0L,
                modifiedAt = 300L
            )
        )

        val pruned = SyncPlaylistDeletionPolicy.pruneResolvedDeletions(
            deletions = deletions,
            playlists = playlists
        )

        assertTrue(pruned.isEmpty())
    }

    @Test
    fun `legacy song without addedAt still respects deletion`() {
        val merged = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = listOf(syncSong(id = 11L, addedAt = 0L)),
            deletions = listOf(deletion(playlistId = 7L, songId = 11L, deletedAt = 200L))
        )

        assertTrue(merged.isEmpty())
    }

    private fun syncSong(
        id: Long,
        addedAt: Long,
        album: String = "netease",
        mediaUri: String? = null
    ): SyncSong {
        return SyncSong(
            id = id,
            name = "Song $id",
            artist = "Artist",
            album = album,
            mediaUri = mediaUri,
            addedAt = addedAt
        )
    }

    private fun deletion(
        playlistId: Long,
        songId: Long,
        deletedAt: Long,
        album: String = "netease",
        mediaUri: String? = null,
        deviceId: String = "device"
    ): SyncPlaylistSongDeletion {
        return SyncPlaylistSongDeletion(
            playlistId = playlistId,
            songId = songId,
            album = album,
            mediaUri = mediaUri,
            deletedAt = deletedAt,
            deviceId = deviceId
        )
    }
}
