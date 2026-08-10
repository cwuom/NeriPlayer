package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "downloaded_song_catalog",
    indices = [
        Index(
            value = ["root_key", "display_position"],
            orders = [Index.Order.ASC, Index.Order.ASC],
            name = "index_downloaded_song_catalog_root_position"
        ),
        Index(
            value = ["file_path"],
            name = "index_downloaded_song_catalog_file_path"
        ),
        Index(
            value = ["media_uri"],
            name = "index_downloaded_song_catalog_media_uri"
        ),
        Index(
            value = ["stable_key"],
            name = "index_downloaded_song_catalog_stable_key"
        )
    ]
)
internal data class DownloadedSongCatalogEntity(
    @PrimaryKey
    @ColumnInfo(name = "catalog_key")
    val catalogKey: String,
    @ColumnInfo(name = "root_key")
    val rootKey: String,
    @ColumnInfo(name = "display_position")
    val displayPosition: Int,
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "file_path")
    val filePath: String,
    @ColumnInfo(name = "file_size")
    val fileSize: Long,
    @ColumnInfo(name = "download_time")
    val downloadTime: Long,
    @ColumnInfo(name = "cover_path")
    val coverPath: String?,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String?,
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
    @ColumnInfo(name = "media_uri")
    val mediaUri: String?,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "stable_key")
    val stableKey: String?,
    @ColumnInfo(name = "source_identity_album")
    val sourceIdentityAlbum: String?,
    @ColumnInfo(name = "source_media_uri")
    val sourceMediaUri: String?,
    @ColumnInfo(name = "source_channel_id")
    val sourceChannelId: String?,
    @ColumnInfo(name = "source_audio_id")
    val sourceAudioId: String?,
    @ColumnInfo(name = "source_sub_audio_id")
    val sourceSubAudioId: String?,
    @ColumnInfo(name = "source_playlist_context_id")
    val sourcePlaylistContextId: String?
)
