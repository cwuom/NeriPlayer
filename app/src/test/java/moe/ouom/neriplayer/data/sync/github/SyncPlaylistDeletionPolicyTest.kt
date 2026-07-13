package moe.ouom.neriplayer.data.sync.github

import com.google.gson.Gson
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
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
    fun `deletion merge unions observed tokens with deterministic legacy mirror`() {
        val first = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 100L,
            deviceId = "a",
            removedTokens = listOf(token("b", 1L), token("a", 2L))
        )
        val latest = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 200L,
            deviceId = "b",
            removedTokens = listOf(token("a", 2L), token("a", 1L))
        )
        val expected = latest.copy(
            removedMembershipTokens = listOf(
                token("a", 1L),
                token("a", 2L),
                token("b", 1L)
            )
        )

        val localFirst = SyncPlaylistDeletionPolicy.mergeDeletions(listOf(first), listOf(latest))
        val remoteFirst = SyncPlaylistDeletionPolicy.mergeDeletions(listOf(latest), listOf(first))

        assertEquals(listOf(expected), localFirst)
        assertEquals(localFirst, remoteFirst)
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
    fun `observed token deletion removes ordinary membership`() {
        val membership = token("device", 1L)
        val song = syncSong(
            id = 11L,
            addedAt = 300L,
            membershipTokens = listOf(membership)
        )
        val deletion = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 100L,
            removedTokens = listOf(membership)
        )

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(7L, listOf(song), listOf(deletion))
        assertTrue(merged.isEmpty())
    }

    @Test
    fun `restored membership survives deletion of old token regardless of timestamp`() {
        val restored = syncSong(
            id = 11L,
            addedAt = 100L,
            membershipTokens = listOf(token("device", 2L))
        )
        val deletion = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 200L,
            removedTokens = listOf(token("device", 1L))
        )

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(
            playlistId = 7L,
            songs = listOf(restored),
            deletions = listOf(deletion)
        )

        assertEquals(listOf(restored), merged)
    }

    @Test
    fun `partial observed deletion strips old token and keeps concurrent membership`() {
        val oldToken = token("device", 1L)
        val concurrentToken = token("other", 1L)
        val song = syncSong(
            id = 11L,
            addedAt = 100L,
            membershipTokens = listOf(oldToken, concurrentToken)
        )
        val deletion = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 200L,
            removedTokens = listOf(oldToken)
        )

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(7L, listOf(song), listOf(deletion))

        assertEquals(
            listOf(song.copy(syncMembershipTokens = listOf(concurrentToken))),
            merged
        )
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
    fun `observed token deletion is retained after a new membership appears`() {
        val deletion = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 200L,
            removedTokens = listOf(token("device", 1L))
        )
        val playlists = listOf(
            SyncPlaylist(
                id = 7L,
                name = "playlist",
                songs = listOf(
                    syncSong(
                        id = 11L,
                        addedAt = 300L,
                        membershipTokens = listOf(token("device", 2L))
                    )
                ),
                createdAt = 0L,
                modifiedAt = 300L
            )
        )

        val pruned = SyncPlaylistDeletionPolicy.pruneResolvedDeletions(
            deletions = listOf(deletion),
            playlists = playlists
        )

        assertEquals(listOf(deletion), pruned)
    }

    @Test
    fun `readd clears legacy deletion but retains observed token deletion`() {
        val identitySong = syncSong(id = 11L, addedAt = 300L)
        val legacyDeletion = deletion(
            playlistId = 7L,
            songId = 11L,
            deletedAt = 100L,
            deviceId = "legacy"
        )
        val causalDeletion = deletion(
            playlistId = 7L,
            songId = 12L,
            deletedAt = 200L,
            removedTokens = listOf(token("device", 1L))
        )

        val retained = SyncPlaylistDeletionPolicy.clearLegacyDeletionsForReaddedSongs(
            deletions = listOf(legacyDeletion, causalDeletion),
            playlistId = 7L,
            identities = listOf(identitySong.identity(), syncSong(12L, 300L).identity())
        )

        assertEquals(listOf(causalDeletion), retained)
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

    @Test
    fun `gson legacy null token lists use timestamp fallback`() {
        val gson = Gson()
        val song = gson.fromJson(
            """{"id":11,"name":"Song","artist":"Artist","album":"netease","addedAt":100}""",
            SyncSong::class.java
        )
        val deletion = gson.fromJson(
            """{"playlistId":7,"songId":11,"album":"netease","deletedAt":200,"deviceId":"legacy"}""",
            SyncPlaylistSongDeletion::class.java
        )

        val merged = SyncPlaylistDeletionPolicy.applyDeletions(7L, listOf(song), listOf(deletion))
        val normalizedDeletion = SyncPlaylistDeletionPolicy
            .mergeDeletions(listOf(deletion), emptyList())
            .single()

        assertTrue(merged.isEmpty())
        assertEquals(emptyList<SyncCausalToken>(), normalizedDeletion.removedMembershipTokens)
    }

    private fun syncSong(
        id: Long,
        addedAt: Long,
        album: String = "netease",
        mediaUri: String? = null,
        membershipTokens: List<SyncCausalToken> = emptyList()
    ): SyncSong {
        return SyncSong(
            id = id,
            name = "Song $id",
            artist = "Artist",
            album = album,
            mediaUri = mediaUri,
            addedAt = addedAt,
            syncMembershipTokens = membershipTokens
        )
    }

    private fun deletion(
        playlistId: Long,
        songId: Long,
        deletedAt: Long,
        album: String = "netease",
        mediaUri: String? = null,
        deviceId: String = "device",
        removedTokens: List<SyncCausalToken> = emptyList()
    ): SyncPlaylistSongDeletion {
        return SyncPlaylistSongDeletion(
            playlistId = playlistId,
            songId = songId,
            album = album,
            mediaUri = mediaUri,
            deletedAt = deletedAt,
            deviceId = deviceId,
            removedMembershipTokens = removedTokens
        )
    }

    private fun token(deviceId: String, counter: Long): SyncCausalToken {
        return SyncCausalToken(deviceId = deviceId, counter = counter)
    }
}
