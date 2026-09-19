package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download_pending_queue",
    indices = [
        Index(
            value = ["queue_order"],
            orders = [Index.Order.ASC],
            name = "index_download_pending_queue_order"
        )
    ]
)
internal data class DownloadPendingQueueEntity(
    @PrimaryKey
    @ColumnInfo(name = "stable_key")
    val stableKey: String,
    @ColumnInfo(name = "queue_order")
    val queueOrder: Int,
    @ColumnInfo(name = "queued_at_ms")
    val queuedAtMs: Long,
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
    @ColumnInfo(name = "stream_url")
    val streamUrl: String?
)

@Entity(tableName = "download_cancelled_key")
internal data class DownloadCancelledKeyEntity(
    @PrimaryKey
    @ColumnInfo(name = "stable_key")
    val stableKey: String,
    @ColumnInfo(name = "cancelled_at_ms")
    val cancelledAtMs: Long
)
