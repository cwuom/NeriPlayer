package moe.ouom.neriplayer.data.local.database.store

import moe.ouom.neriplayer.data.local.playlist.model.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaylistRoomMapperTest {
    private val mapper = LocalPlaylistRoomMapper()

    @Test
    fun `domain digest keeps legacy empty-state bytes`() {
        assertEquals(
            "fb2c447ba6ceec0b5b0e7856303450290c7250829f9d25df2625a32612f0b937",
            LocalPlaylistRoomStore.domainDigest(emptyList())
        )
    }

    @Test
    fun `round trip keeps playlist order membership tokens and local metadata`() {
        val firstSong = remoteSong(
            id = 101L,
            name = "first",
            addedAt = 7_000L,
            streamUrl = "https://temporary-stream.example/first",
            tokens = listOf(
                SyncCausalToken(deviceId = "device-b", counter = 2L),
                SyncCausalToken(deviceId = "", counter = 0L),
                SyncCausalToken(deviceId = "device-a", counter = 1L)
            )
        )
        val secondSong = localSong(
            name = "local file",
            addedAt = 6_000L,
            streamUrl = "https://temporary-stream.example/local",
            tokens = listOf(SyncCausalToken(deviceId = "device-c", counter = 3L))
        )
        val secondSongInFirstPlaylist = secondSong.copy(customName = "playlist-specific name")
        val playlists = listOf(
            LocalPlaylist(
                id = 20L,
                name = "second playlist",
                songs = mutableListOf(secondSong),
                modifiedAt = 2_000L,
                songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
            ),
            LocalPlaylist(
                id = 10L,
                name = "first playlist",
                songs = mutableListOf(firstSong, secondSongInFirstPlaylist),
                modifiedAt = 1_000L,
                customCoverUrl = "https://cover.example/playlist.jpg",
                songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
            )
        )

        val snapshot = mapper.toSnapshot(playlists, sourceDigest = "digest-1", now = 12_345L)
        val restored = mapper.toDomain(
            playlists = snapshot.playlists,
            tracks = snapshot.tracks,
            members = snapshot.members,
            memberTokens = snapshot.memberTokens
        )

        assertEquals(
            listOf(
                playlists[0].copy(
                    songs = mutableListOf(
                        secondSong.copy(
                            streamUrl = null,
                            syncMembershipTokens = listOf(SyncCausalToken("device-c", 3L))
                        )
                    )
                ),
                playlists[1].copy(
                    songs = mutableListOf(
                        firstSong.copy(
                            streamUrl = null,
                            syncMembershipTokens = listOf(
                                SyncCausalToken("device-a", 1L),
                                SyncCausalToken("device-b", 2L)
                            )
                        ),
                        secondSongInFirstPlaylist.copy(
                            streamUrl = null,
                            syncMembershipTokens = listOf(SyncCausalToken("device-c", 3L))
                        )
                    )
                )
            ),
            restored
        )
        assertEquals(listOf(20L, 10L), restored.map(LocalPlaylist::id))
        assertEquals(listOf("first", "local file"), snapshot.tracks.map { it.name }.sorted())
        assertTrue(snapshot.memberTokens.none { it.deviceId.isBlank() || it.counter <= 0L })
        assertTrue(snapshot.migrationMetadata.any { it.key == "local_playlist_source_digest" })
        assertFalse(snapshot.tracks.any { it.durablePayloadJson.contains("temporary-stream") })
        assertFalse(snapshot.members.any { it.memberPayloadJson.contains("temporary-stream") })
        assertEquals("/storage/emulated/0/Music/local.flac", restored[0].songs.single().localFilePath)
    }

    @Test
    fun `validation accepts mapper equivalent snapshots`() {
        val playlist = LocalPlaylist(
            id = 30L,
            name = "validated",
            songs = mutableListOf(remoteSong(id = 301L, name = "validated song")),
            modifiedAt = 3_000L
        )

        val result = mapper.validateRoundTrip(listOf(playlist))

        assertTrue(result.equivalent)
        assertEquals(1, result.playlistCount)
        assertEquals(1, result.memberCount)
        assertNull(result.firstMismatch)
    }

    private fun remoteSong(
        id: Long,
        name: String,
        addedAt: Long = 1_000L,
        streamUrl: String? = null,
        tokens: List<SyncCausalToken> = emptyList()
    ): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = "artist $id",
            album = "netease",
            albumId = id + 10L,
            durationMs = 180_000L,
            coverUrl = "https://cover.example/$id.jpg",
            mediaUri = null,
            channelId = "netease",
            audioId = id.toString(),
            playlistContextId = "playlist-context-$id",
            streamUrl = streamUrl,
            addedAt = addedAt,
            syncMembershipTokens = tokens
        )
    }

    private fun localSong(
        name: String,
        addedAt: Long,
        streamUrl: String?,
        tokens: List<SyncCausalToken>
    ): SongItem {
        val source = remoteSong(id = 202L, name = "source")
        return SongItem(
            id = 0L,
            name = name,
            artist = "local artist",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 240_000L,
            coverUrl = null,
            mediaUri = "content://media/external/audio/media/202",
            localFileName = "local.flac",
            localFilePath = "/storage/emulated/0/Music/local.flac",
            channelId = "local",
            audioId = "local-202",
            sourceStableKey = source.stableKey(),
            streamUrl = streamUrl,
            addedAt = addedAt,
            syncMembershipTokens = tokens
        )
    }
}
