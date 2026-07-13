package moe.ouom.neriplayer.data.backup

import com.google.gson.Gson
import moe.ouom.neriplayer.data.local.playlist.model.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.sync.github.SyncPlaylist
import moe.ouom.neriplayer.data.sync.github.SyncSong
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPlaylistRestorePolicyTest {
    @Test
    fun `new playlist restores every song with fresh membership and newer added time`() {
        val oldTokens = listOf(
            SyncCausalToken("backup-device", 1L),
            SyncCausalToken("backup-device", 2L),
            SyncCausalToken("backup-device", 3L)
        )
        val imported = syncPlaylist(
            syncSong(1L, "first", 30L, listOf(oldTokens[0])),
            syncSong(2L, "second", 20L, listOf(oldTokens[1])),
            syncSong(3L, "third", 10L, listOf(oldTokens[2]))
        )
        val allocator = RecordingTokenAllocator()

        val result = BackupPlaylistRestorePolicy.createPlaylist(
            playlistId = 99L,
            playlistName = "restored",
            imported = imported,
            addedAtFloor = 1_000L,
            modifiedAt = 1_100L,
            allocateTokens = allocator::allocate
        )

        assertEquals(listOf(3), allocator.requestedCounts)
        assertEquals(listOf("first", "second", "third"), result.playlist.songs.map { it.name })
        assertEquals(listOf(1_003L, 1_002L, 1_001L), result.playlist.songs.map { it.addedAt })
        assertTrue(result.playlist.songs.all { it.addedAt > 1_000L })
        assertEquals(
            listOf(1L, 2L, 3L),
            result.playlist.songs.map { it.syncMembershipTokens.orEmpty().single().counter }
        )
        assertTrue(result.playlist.songs.none { song ->
            song.syncMembershipTokens.orEmpty().any(oldTokens::contains)
        })
        assertEquals(1_100L, result.playlist.modifiedAt)
        assertEquals(DISPLAY_ORDER_SONG_ORDER_VERSION, result.playlist.songOrderVersion)
        assertEquals(1_003L, result.maxAssignedAddedAt)
    }

    @Test
    fun `merge allocates membership only for missing songs and keeps display order`() {
        val existingToken = SyncCausalToken("local-device", 40L)
        val existingSong = syncSong(
            id = 1L,
            name = "existing",
            addedAt = 900L,
            tokens = listOf(existingToken)
        ).toSongItem()
        val existing = LocalPlaylist(
            id = 7L,
            name = "playlist",
            songs = mutableListOf(existingSong),
            modifiedAt = 900L
        )
        val imported = syncPlaylist(
            syncSong(2L, "missing-first", 30L),
            syncSong(1L, "existing", 20L),
            syncSong(3L, "missing-second", 10L)
        )
        val allocator = RecordingTokenAllocator()

        val result = BackupPlaylistRestorePolicy.mergePlaylist(
            existing = existing,
            imported = imported,
            addedAtFloor = 2_000L,
            modifiedAt = 2_100L,
            allocateTokens = allocator::allocate
        )

        assertEquals(listOf(2), allocator.requestedCounts)
        assertEquals(
            listOf("missing-first", "missing-second", "existing"),
            result.playlist.songs.map { it.name }
        )
        assertEquals(listOf(2_002L, 2_001L, 900L), result.playlist.songs.map { it.addedAt })
        assertEquals(listOf(existingToken), result.playlist.songs.last().syncMembershipTokens)
        assertEquals(2, result.addedSongs)
        assertTrue(result.hasChanges)
        assertEquals(2_100L, result.playlist.modifiedAt)
    }

    @Test
    fun `repeat import does not allocate another membership token`() {
        val imported = syncPlaylist(syncSong(8L, "restored", 10L))
        val firstAllocator = RecordingTokenAllocator()
        val first = BackupPlaylistRestorePolicy.mergePlaylist(
            existing = LocalPlaylist(id = 8L, name = "playlist"),
            imported = imported,
            addedAtFloor = 3_000L,
            modifiedAt = 3_100L,
            allocateTokens = firstAllocator::allocate
        )

        val repeated = BackupPlaylistRestorePolicy.mergePlaylist(
            existing = first.playlist,
            imported = imported,
            addedAtFloor = first.maxAssignedAddedAt,
            modifiedAt = 3_200L,
            allocateTokens = { error("重复导入不应领取 token") }
        )

        assertEquals(listOf(1), firstAllocator.requestedCounts)
        assertFalse(repeated.hasChanges)
        assertEquals(0, repeated.addedSongs)
        assertEquals(first.playlist, repeated.playlist)
        assertEquals(first.maxAssignedAddedAt, repeated.maxAssignedAddedAt)
    }

    @Test
    fun `restored timestamps stay strictly descending above tombstone floor`() {
        val result = BackupPlaylistRestorePolicy.createPlaylist(
            playlistId = 9L,
            playlistName = "ordered",
            imported = syncPlaylist(
                syncSong(1L, "a", 3L),
                syncSong(2L, "b", 2L),
                syncSong(3L, "c", 1L)
            ),
            addedAtFloor = 9_999L,
            modifiedAt = 10_000L,
            allocateTokens = RecordingTokenAllocator()::allocate
        )
        val restoredTimes = result.playlist.songs.map { it.addedAt }

        assertTrue(restoredTimes.all { it > 9_999L })
        assertTrue(restoredTimes.zipWithNext().all { (left, right) -> left > right })
    }

    @Test
    fun `legacy gson song without membership tokens restores safely`() {
        val legacySong = Gson().fromJson(
            """
            {
              "id": 12,
              "name": "legacy",
              "artist": "artist",
              "album": "NeteaseAlbum",
              "albumId": 1,
              "durationMs": 1000,
              "channelId": "netease",
              "audioId": "12",
              "addedAt": 10
            }
            """.trimIndent(),
            SyncSong::class.java
        )

        val result = BackupPlaylistRestorePolicy.createPlaylist(
            playlistId = 12L,
            playlistName = "legacy",
            imported = syncPlaylist(legacySong),
            addedAtFloor = 4_000L,
            modifiedAt = 4_100L,
            allocateTokens = RecordingTokenAllocator()::allocate
        )

        assertEquals(1, result.playlist.songs.size)
        assertEquals(1, result.playlist.songs.single().syncMembershipTokens.orEmpty().size)
        assertTrue(result.playlist.songs.single().addedAt > 4_000L)
    }

    private fun syncPlaylist(vararg songs: SyncSong): SyncPlaylist {
        return SyncPlaylist(
            id = 1L,
            name = "backup",
            songs = songs.toList(),
            createdAt = 1L,
            modifiedAt = 100L,
            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
        )
    }

    private fun syncSong(
        id: Long,
        name: String,
        addedAt: Long,
        tokens: List<SyncCausalToken> = emptyList()
    ): SyncSong {
        return SyncSong(
            id = id,
            name = name,
            artist = "artist",
            album = "NeteaseAlbum",
            albumId = 1L,
            durationMs = 1_000L,
            channelId = "netease",
            audioId = id.toString(),
            addedAt = addedAt,
            syncMembershipTokens = tokens
        )
    }

    private class RecordingTokenAllocator {
        val requestedCounts = mutableListOf<Int>()
        private var nextCounter = 1L

        fun allocate(count: Int): List<SyncCausalToken> {
            requestedCounts += count
            return List(count) {
                SyncCausalToken("restore-device", nextCounter++)
            }
        }
    }
}
