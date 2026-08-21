package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "download_snapshot_entry",
    primaryKeys = ["root_key", "bucket", "entry_key"],
    indices = [
        Index(
            value = ["root_key", "bucket", "display_position"],
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.ASC],
            name = "index_download_snapshot_entry_root_bucket_position"
        ),
        Index(
            value = ["root_key", "bucket", "name"],
            name = "index_download_snapshot_entry_root_bucket_name"
        ),
        Index(
            value = ["root_key", "reference"],
            name = "index_download_snapshot_entry_root_reference"
        )
    ]
)
internal data class DownloadSnapshotEntryEntity(
    @ColumnInfo(name = "root_key")
    val rootKey: String,
    val bucket: String,
    @ColumnInfo(name = "entry_key")
    val entryKey: String,
    @ColumnInfo(name = "display_position")
    val displayPosition: Int,
    val name: String,
    val reference: String,
    @ColumnInfo(name = "media_uri")
    val mediaUri: String,
    @ColumnInfo(name = "local_file_path")
    val localFilePath: String?,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "last_modified_ms")
    val lastModifiedMs: Long,
    @ColumnInfo(name = "is_directory")
    val isDirectory: Boolean
)

@Entity(
    tableName = "download_snapshot_metadata",
    primaryKeys = ["root_key", "audio_name"],
    indices = [
        Index(
            value = ["root_key", "stable_key"],
            name = "index_download_snapshot_metadata_root_stable_key"
        ),
        Index(
            value = ["root_key", "song_id"],
            name = "index_download_snapshot_metadata_root_song_id"
        ),
        Index(
            value = ["root_key", "media_uri"],
            name = "index_download_snapshot_metadata_root_media_uri"
        ),
        Index(
            value = ["root_key", "channel_id", "audio_id", "sub_audio_id"],
            name = "index_download_snapshot_metadata_root_remote_track"
        )
    ]
)
internal data class DownloadSnapshotMetadataEntity(
    @ColumnInfo(name = "root_key")
    val rootKey: String,
    @ColumnInfo(name = "audio_name")
    val audioName: String,
    @ColumnInfo(name = "stable_key")
    val stableKey: String?,
    @ColumnInfo(name = "song_id")
    val songId: Long?,
    @ColumnInfo(name = "identity_album")
    val identityAlbum: String?,
    val album: String?,
    val name: String?,
    val artist: String?,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String?,
    @ColumnInfo(name = "matched_lyric")
    val matchedLyric: String?,
    @ColumnInfo(name = "matched_translated_lyric")
    val matchedTranslatedLyric: String?,
    @ColumnInfo(name = "matched_romanized_lyric")
    val matchedRomanizedLyric: String?,
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
    @ColumnInfo(name = "original_romanized_lyric")
    val originalRomanizedLyric: String?,
    @ColumnInfo(name = "media_uri")
    val mediaUri: String?,
    @ColumnInfo(name = "channel_id")
    val channelId: String?,
    @ColumnInfo(name = "audio_id")
    val audioId: String?,
    @ColumnInfo(name = "sub_audio_id")
    val subAudioId: String?,
    @ColumnInfo(name = "playlist_context_id")
    val playlistContextId: String?,
    @ColumnInfo(name = "cover_path")
    val coverPath: String?,
    @ColumnInfo(name = "lyric_path")
    val lyricPath: String?,
    @ColumnInfo(name = "translated_lyric_path")
    val translatedLyricPath: String?,
    @ColumnInfo(name = "romanized_lyric_path")
    val romanizedLyricPath: String?,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "download_finalized")
    val downloadFinalized: Boolean?,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long?,
    @ColumnInfo(name = "created_at_source")
    val createdAtSource: String?
)
