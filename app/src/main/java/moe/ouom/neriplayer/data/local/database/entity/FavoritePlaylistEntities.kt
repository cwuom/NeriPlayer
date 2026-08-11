package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "favorite_playlist",
    primaryKeys = ["playlist_id", "source"],
    indices = [
        Index(
            value = ["sort_order", "modified_at"],
            orders = [Index.Order.DESC, Index.Order.DESC],
            name = "index_favorite_playlist_sort"
        ),
        Index(
            value = ["is_deleted", "sort_order"],
            orders = [Index.Order.ASC, Index.Order.DESC],
            name = "index_favorite_playlist_visibility"
        )
    ]
)
internal data class FavoritePlaylistEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    val source: String,
    val name: String,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String?,
    @ColumnInfo(name = "track_count")
    val trackCount: Int,
    @ColumnInfo(name = "browse_id")
    val browseId: String?,
    @ColumnInfo(name = "remote_playlist_id")
    val remotePlaylistId: String?,
    val subtitle: String?,
    @ColumnInfo(name = "added_time")
    val addedTime: Long,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Long,
    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long,
    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean
)

@Entity(
    tableName = "favorite_playlist_song",
    primaryKeys = ["playlist_id", "source", "display_position"],
    foreignKeys = [
        ForeignKey(
            entity = FavoritePlaylistEntity::class,
            parentColumns = ["playlist_id", "source"],
            childColumns = ["playlist_id", "source"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = ["playlist_id", "source", "display_position"],
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.ASC],
            name = "index_favorite_playlist_song_order"
        )
    ]
)
internal data class FavoritePlaylistSongEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    val source: String,
    @ColumnInfo(name = "display_position")
    val displayPosition: Int,
    @ColumnInfo(name = "song_payload_json")
    val songPayloadJson: String
)
