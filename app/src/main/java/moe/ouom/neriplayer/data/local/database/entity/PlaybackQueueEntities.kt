package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

internal const val PLAYBACK_QUEUE_MAIN = "main"
internal const val PLAYBACK_QUEUE_SHUFFLE_RESTORE = "shuffle_restore"
internal const val PLAYBACK_QUEUE_STATE_ID = 1

@Entity(
    tableName = "playback_queue_state"
)
internal data class PlaybackQueueStateEntity(
    @PrimaryKey
    val id: Int = PLAYBACK_QUEUE_STATE_ID,
    @ColumnInfo(name = "current_index")
    val currentIndex: Int,
    @ColumnInfo(name = "media_url")
    val mediaUrl: String?,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    @ColumnInfo(name = "should_resume_playback")
    val shouldResumePlayback: Boolean,
    @ColumnInfo(name = "repeat_mode")
    val repeatMode: Int?,
    @ColumnInfo(name = "shuffle_enabled")
    val shuffleEnabled: Boolean?,
    @ColumnInfo(name = "shuffle_restore_index")
    val shuffleRestoreIndex: Int?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)

@Entity(
    tableName = "playback_queue_song",
    primaryKeys = ["queue_id", "position"]
)
internal data class PlaybackQueueSongEntity(
    @ColumnInfo(name = "queue_id")
    val queueId: String,
    val position: Int,
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
    @ColumnInfo(name = "stream_url")
    val streamUrl: String?
)
