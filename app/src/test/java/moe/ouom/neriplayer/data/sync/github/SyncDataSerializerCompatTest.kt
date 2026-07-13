@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.github

import com.google.gson.Gson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import moe.ouom.neriplayer.data.local.playlist.model.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.local.playlist.model.LEGACY_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.model.normalizedSyncCausalTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDataSerializerCompatTest {
    @Test
    fun `protobuf sync song with missing legacy fields uses default values`() {
        val oldData = OldSyncData(
            deviceId = "old-device",
            deviceName = "Old Device",
            playlists = listOf(
                OldSyncPlaylist(
                    id = 1L,
                    name = "legacy",
                    songs = listOf(
                        OldSyncSong(
                            id = 123L,
                            name = "song",
                            durationMs = 180_000L,
                            coverUrl = null
                        )
                    ),
                    createdAt = 10L,
                    modifiedAt = 20L
                )
            ),
            playlistSongDeletions = listOf(
                OldSyncPlaylistSongDeletion(
                    playlistId = 1L,
                    songId = 123L,
                    album = "album",
                    deletedAt = 30L,
                    deviceId = "old-device"
                )
            )
        )

        val bytes = ProtoBuf.encodeToByteArray(oldData)
        val decoded = ProtoBuf.decodeFromByteArray<SyncData>(bytes)
        val song = decoded.playlists.single().songs.single()

        assertEquals("", song.artist)
        assertEquals("", song.album)
        assertEquals(0L, song.albumId)
        assertEquals(180_000L, song.durationMs)
        assertEquals(emptyList<SyncCausalToken>(), song.syncMembershipTokens)
        assertEquals(LEGACY_SONG_ORDER_VERSION, decoded.playlists.single().songOrderVersion)
        assertEquals(
            emptyList<SyncCausalToken>(),
            decoded.playlistSongDeletions.single().removedMembershipTokens
        )
    }

    @Test
    fun `legacy sync playlist restores old display order and cover`() {
        val playlist = SyncPlaylist(
            id = 1L,
            name = "legacy",
            songs = listOf(
                syncSong(name = "oldest", coverUrl = "content://covers/oldest.jpg", addedAt = 11L),
                syncSong(name = "middle", coverUrl = "content://covers/middle.jpg", addedAt = 22L),
                syncSong(name = "newest", coverUrl = null, addedAt = 33L)
            ),
            createdAt = 1L,
            modifiedAt = 100L
        ).toLocalPlaylist()

        assertEquals(DISPLAY_ORDER_SONG_ORDER_VERSION, playlist.songOrderVersion)
        assertEquals(listOf("newest", "middle", "oldest"), playlist.songs.map { it.name })
        assertEquals("content://covers/middle.jpg", playlist.displayCoverUrl())
    }

    @Test
    fun `protobuf favorite playlist with missing legacy fields uses default values`() {
        val oldData = OldSyncData(
            deviceId = "old-device",
            deviceName = "Old Device",
            favoritePlaylists = listOf(
                OldSyncFavoritePlaylist(
                    id = 7L,
                    name = "legacy favorite",
                    coverUrl = null
                )
            )
        )

        val bytes = ProtoBuf.encodeToByteArray(oldData)
        val decoded = ProtoBuf.decodeFromByteArray<SyncData>(bytes)
        val favorite = decoded.favoritePlaylists.single()

        assertEquals(7L, favorite.id)
        assertEquals("legacy favorite", favorite.name)
        assertEquals(null, favorite.coverUrl)
        assertEquals(0, favorite.trackCount)
        assertEquals("", favorite.source)
        assertEquals(emptyList<SyncSong>(), favorite.songs)
        assertEquals(0L, favorite.addedTime)
        assertEquals(0L, favorite.modifiedAt)
        assertEquals(false, favorite.isDeleted)
        assertEquals(0L, favorite.sortOrder)
        assertEquals(emptyList<SyncPlaylistSongDeletion>(), decoded.playlistSongDeletions)
    }

    @Test
    fun `protobuf causal token fields round trip in deterministic order`() {
        val tokens = listOf(
            SyncCausalToken("device-b", 2L),
            SyncCausalToken("device-a", 1L),
            SyncCausalToken("device-b", 2L)
        ).normalizedSyncCausalTokens()
        val song = syncSong(name = "causal", coverUrl = null, addedAt = 1L)
            .copy(syncMembershipTokens = tokens)
        val data = SyncData(
            deviceId = "device-a",
            deviceName = "Device A",
            playlists = listOf(
                SyncPlaylist(
                    id = 1L,
                    name = "playlist",
                    songs = listOf(song),
                    createdAt = 1L,
                    modifiedAt = 2L
                )
            ),
            playlistSongDeletions = listOf(
                SyncPlaylistSongDeletion(
                    playlistId = 1L,
                    songId = song.id,
                    album = song.album,
                    deletedAt = 3L,
                    deviceId = "device-a",
                    removedMembershipTokens = tokens
                )
            )
        )

        val decoded = ProtoBuf.decodeFromByteArray<SyncData>(ProtoBuf.encodeToByteArray(data))

        assertEquals(tokens, decoded.playlists.single().songs.single().syncMembershipTokens)
        assertEquals(tokens, decoded.playlistSongDeletions.single().removedMembershipTokens)
    }

    @Test
    fun `legacy gson models with missing causal tokens map to empty lists`() {
        val legacyJson = """
            {
              "id": 123,
              "name": "legacy",
              "artist": "artist",
              "album": "album",
              "albumId": 1,
              "durationMs": 1000
            }
        """.trimIndent()
        val legacySong = Gson().fromJson(legacyJson, moe.ouom.neriplayer.data.model.SongItem::class.java)
        val legacySyncSong = Gson().fromJson(legacyJson, SyncSong::class.java)
        val legacyDeletion = Gson().fromJson(
            """
                {
                  "playlistId": 1,
                  "songId": 123,
                  "album": "album",
                  "deletedAt": 10,
                  "deviceId": "legacy-device"
                }
            """.trimIndent(),
            SyncPlaylistSongDeletion::class.java
        )

        val syncSong = SyncSong.fromSongItem(legacySong)
        val copiedSyncSong = legacySyncSong.copyWithNormalizedMembershipTokens(addedAt = 20L)
        val copiedDeletion = legacyDeletion.copyWithNormalizedMembershipTokens(
            mediaUri = "https://cdn.example/song.mp3"
        )

        assertEquals(emptyList<SyncCausalToken>(), syncSong.syncMembershipTokens)
        assertEquals(emptyList<SyncCausalToken>(), syncSong.toSongItem().syncMembershipTokens)
        assertEquals(emptyList<SyncCausalToken>(), legacySyncSong.toSongItem().syncMembershipTokens)
        assertEquals(20L, copiedSyncSong.addedAt)
        assertEquals(emptyList<SyncCausalToken>(), copiedSyncSong.syncMembershipTokens)
        assertEquals("https://cdn.example/song.mp3", copiedDeletion.mediaUri)
        assertEquals(emptyList<SyncCausalToken>(), copiedDeletion.removedMembershipTokens)
        assertEquals(
            emptyList<SyncCausalToken>(),
            legacyDeletion.removedMembershipTokens.normalizedSyncCausalTokens()
        )
    }

    @Test
    fun `legacy gson playlist normalizes missing membership tokens before order migration`() {
        val legacyPlaylist = Gson().fromJson(
            """
                {
                  "id": 7,
                  "name": "legacy",
                  "songs": [
                    {
                      "id": 123,
                      "name": "song",
                      "artist": "artist",
                      "album": "album",
                      "addedAt": 10
                    }
                  ],
                  "createdAt": 1,
                  "modifiedAt": 20
                }
            """.trimIndent(),
            SyncPlaylist::class.java
        )

        val normalized = legacyPlaylist.normalizedForDisplayOrder(now = 30L)

        assertEquals(emptyList<SyncCausalToken>(), normalized.songs.single().syncMembershipTokens)
        assertTrue(normalized.songs.single().addedAt > 0L)
    }

    @Serializable
    private data class OldSyncData(
        @ProtoNumber(1) val version: String = "2.0",
        @ProtoNumber(2) val deviceId: String,
        @ProtoNumber(3) val deviceName: String,
        @ProtoNumber(4) val lastModified: Long = 0L,
        @ProtoNumber(5) val playlists: List<OldSyncPlaylist> = emptyList(),
        @ProtoNumber(6) val favoritePlaylists: List<OldSyncFavoritePlaylist> = emptyList(),
        @ProtoNumber(13) val playlistSongDeletions: List<OldSyncPlaylistSongDeletion> = emptyList()
    )

    @Serializable
    private data class OldSyncPlaylist(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val songs: List<OldSyncSong>,
        @ProtoNumber(4) val createdAt: Long,
        @ProtoNumber(5) val modifiedAt: Long
    )

    @Serializable
    private data class OldSyncSong(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(6) val durationMs: Long,
        @ProtoNumber(7) val coverUrl: String?
    )

    @Serializable
    private data class OldSyncFavoritePlaylist(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val coverUrl: String?
    )

    @Serializable
    private data class OldSyncPlaylistSongDeletion(
        @ProtoNumber(1) val playlistId: Long,
        @ProtoNumber(2) val songId: Long,
        @ProtoNumber(3) val album: String,
        @ProtoNumber(4) val mediaUri: String? = null,
        @ProtoNumber(5) val deletedAt: Long,
        @ProtoNumber(6) val deviceId: String
    )

    private fun syncSong(
        name: String,
        coverUrl: String?,
        addedAt: Long
    ): SyncSong {
        return SyncSong(
            id = name.hashCode().toLong(),
            name = name,
            artist = "artist",
            album = "album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = coverUrl,
            addedAt = addedAt
        )
    }
}
