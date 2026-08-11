package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

internal const val LOCAL_PLAYLIST_PAYLOAD_SCHEMA_VERSION = 1

@Entity(
    tableName = "local_playlist",
    indices = [
        Index(
            value = ["display_position"],
            orders = [Index.Order.ASC],
            name = "index_local_playlist_display_position"
        ),
        Index(
            value = ["modified_at"],
            orders = [Index.Order.DESC],
            name = "index_local_playlist_modified_at"
        )
    ]
)
internal data class LocalPlaylistEntity(
    @PrimaryKey
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    val name: String,
    @ColumnInfo(name = "display_position")
    val displayPosition: Int,
    @ColumnInfo(name = "custom_cover_url")
    val customCoverUrl: String?,
    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long,
    @ColumnInfo(name = "song_order_version")
    val songOrderVersion: Int,
    @ColumnInfo(name = "is_system")
    val isSystem: Boolean
)

@Entity(
    tableName = "track",
    indices = [
        Index(
            value = ["identity_id", "identity_album", "identity_media_uri"],
            name = "index_track_identity_parts"
        ),
        Index(value = ["name"], name = "index_track_name"),
        Index(value = ["artist"], name = "index_track_artist")
    ]
)
internal data class TrackEntity(
    @PrimaryKey
    @ColumnInfo(name = "identity_key")
    val identityKey: String,
    @ColumnInfo(name = "identity_id")
    val identityId: Long,
    @ColumnInfo(name = "identity_album")
    val identityAlbum: String,
    @ColumnInfo(name = "identity_media_uri")
    val identityMediaUri: String?,
    @ColumnInfo(name = "song_id")
    val songId: Long,
    val name: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "album_id")
    val albumId: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String?,
    @ColumnInfo(name = "media_uri")
    val mediaUri: String?,
    @ColumnInfo(name = "channel_id")
    val channelId: String?,
    @ColumnInfo(name = "audio_id")
    val audioId: String?,
    @ColumnInfo(name = "sub_audio_id")
    val subAudioId: String?,
    @ColumnInfo(name = "source_stable_key")
    val sourceStableKey: String?,
    @ColumnInfo(name = "local_file_name")
    val localFileName: String?,
    @ColumnInfo(name = "local_file_path")
    val localFilePath: String?,
    @ColumnInfo(name = "payload_schema_version")
    val payloadSchemaVersion: Int,
    @ColumnInfo(name = "durable_payload_json")
    val durablePayloadJson: String
)

@Entity(
    tableName = "playlist_member",
    primaryKeys = ["playlist_id", "identity_key"],
    foreignKeys = [
        ForeignKey(
            entity = LocalPlaylistEntity::class,
            parentColumns = ["playlist_id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["identity_key"],
            childColumns = ["identity_key"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = ["playlist_id", "display_position"],
            orders = [Index.Order.ASC, Index.Order.ASC],
            name = "index_playlist_member_display_order"
        ),
        Index(
            value = ["playlist_id", "added_at", "order_tie_break"],
            orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.ASC],
            name = "index_playlist_member_added_order"
        ),
        Index(value = ["identity_key"], name = "index_playlist_member_identity_key")
    ]
)
internal data class PlaylistMemberEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    @ColumnInfo(name = "identity_key")
    val identityKey: String,
    @ColumnInfo(name = "display_position")
    val displayPosition: Int,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
    @ColumnInfo(name = "order_tie_break")
    val orderTieBreak: Int,
    @ColumnInfo(name = "playlist_context_id")
    val playlistContextId: String?,
    @ColumnInfo(name = "member_payload_schema_version")
    val memberPayloadSchemaVersion: Int,
    @ColumnInfo(name = "member_payload_json")
    val memberPayloadJson: String
)

@Entity(
    tableName = "playlist_member_token",
    primaryKeys = ["playlist_id", "identity_key", "device_id", "counter"],
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
            name = "index_playlist_member_token_member"
        )
    ]
)
internal data class PlaylistMemberTokenEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    @ColumnInfo(name = "identity_key")
    val identityKey: String,
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    val counter: Long,
    @ColumnInfo(name = "token_index")
    val tokenIndex: Int
)

internal data class LocalPlaylistSummaryProjection(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    val name: String,
    @ColumnInfo(name = "custom_cover_url")
    val customCoverUrl: String?,
    @ColumnInfo(name = "song_count")
    val songCount: Int,
    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long,
    @ColumnInfo(name = "song_order_version")
    val songOrderVersion: Int
)
