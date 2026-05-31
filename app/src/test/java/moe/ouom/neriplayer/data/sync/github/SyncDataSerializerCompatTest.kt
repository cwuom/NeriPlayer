@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.sync.github

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.Assert.assertEquals
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
            )
        )

        val bytes = ProtoBuf.encodeToByteArray(oldData)
        val decoded = ProtoBuf.decodeFromByteArray<SyncData>(bytes)
        val song = decoded.playlists.single().songs.single()

        assertEquals("", song.artist)
        assertEquals("", song.album)
        assertEquals(0L, song.albumId)
        assertEquals(180_000L, song.durationMs)
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
    }

    @Serializable
    private data class OldSyncData(
        @ProtoNumber(1) val version: String = "2.0",
        @ProtoNumber(2) val deviceId: String,
        @ProtoNumber(3) val deviceName: String,
        @ProtoNumber(4) val lastModified: Long = 0L,
        @ProtoNumber(5) val playlists: List<OldSyncPlaylist> = emptyList(),
        @ProtoNumber(6) val favoritePlaylists: List<OldSyncFavoritePlaylist> = emptyList()
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
}
