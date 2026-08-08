package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "playlist_member_netease_artist",
    primaryKeys = ["playlist_id", "identity_key", "artist_position"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistMemberEntity::class,
            parentColumns = ["playlist_id", "identity_key"],
            childColumns = ["playlist_id", "identity_key"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = ["playlist_id", "identity_key"],
            name = "index_playlist_member_artist_member"
        )
    ]
)
internal data class PlaylistMemberNeteaseArtistEntity(
    @androidx.room.ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    @androidx.room.ColumnInfo(name = "identity_key")
    val identityKey: String,
    @androidx.room.ColumnInfo(name = "artist_position")
    val artistPosition: Int,
    @androidx.room.ColumnInfo(name = "artist_id")
    val artistId: Long,
    @androidx.room.ColumnInfo(name = "artist_name")
    val artistName: String
)
