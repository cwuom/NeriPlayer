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
    val id: Long,
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
    @ColumnInfo(name = "matched_lyric")
    val matchedLyric: String?,
    @ColumnInfo(name = "matched_translated_lyric")
    val matchedTranslatedLyric: String?,
    @ColumnInfo(name = "matched_lyric_source")
    val matchedLyricSource: String?,
    @ColumnInfo(name = "matched_song_id")
    val matchedSongId: String?,
    @ColumnInfo(name = "user_lyric_offset_ms")
    val userLyricOffsetMs: Long,
    @ColumnInfo(name = "custom_cover_url")
    val customCoverUrl: String?,
    @ColumnInfo(name = "custom_name")
    val customName: String?,
    @ColumnInfo(name = "custom_artist")
    val customArtist: String?,
    @ColumnInfo(name = "original_name")
    val originalName: String?,
    @ColumnInfo(name = "original_artist")
    val originalArtist: String?,
    @ColumnInfo(name = "original_cover_url")
    val originalCoverUrl: String?,
    @ColumnInfo(name = "original_lyric")
    val originalLyric: String?,
    @ColumnInfo(name = "original_translated_lyric")
    val originalTranslatedLyric: String?,
    @ColumnInfo(name = "local_file_name")
    val localFileName: String?,
    @ColumnInfo(name = "local_file_path")
    val localFilePath: String?,
    @ColumnInfo(name = "channel_id")
    val channelId: String?,
    @ColumnInfo(name = "audio_id")
    val audioId: String?,
    @ColumnInfo(name = "sub_audio_id")
    val subAudioId: String?,
    @ColumnInfo(name = "playlist_context_id")
    val playlistContextId: String?,
    @ColumnInfo(name = "source_stable_key")
    val sourceStableKey: String?,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
    @ColumnInfo(name = "structured_schema_version")
    val structuredSchemaVersion: Int
)

@Entity(
    tableName = "favorite_playlist_song_netease_artist",
    primaryKeys = ["playlist_id", "source", "display_position", "artist_position"],
    foreignKeys = [
        ForeignKey(
            entity = FavoritePlaylistSongEntity::class,
            parentColumns = ["playlist_id", "source", "display_position"],
            childColumns = ["playlist_id", "source", "display_position"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(
            value = ["playlist_id", "source", "display_position"],
            name = "index_favorite_playlist_song_artist_song"
        )
    ]
)
internal data class FavoritePlaylistSongNeteaseArtistEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    val source: String,
    @ColumnInfo(name = "display_position")
    val displayPosition: Int,
    @ColumnInfo(name = "artist_position")
    val artistPosition: Int,
    @ColumnInfo(name = "artist_id")
    val artistId: Long,
    @ColumnInfo(name = "artist_name")
    val artistName: String
)
