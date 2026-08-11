package moe.ouom.neriplayer.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import moe.ouom.neriplayer.data.history.PlayedEntry
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.stableKey

@Entity(
    tableName = "play_history",
    indices = [
        Index(
            value = ["played_at"],
            orders = [Index.Order.DESC],
            name = "index_play_history_played_at"
        ),
        Index(
            value = ["identity_id", "identity_album", "identity_media_uri"],
            name = "index_play_history_identity_parts"
        )
    ]
)
internal data class PlayHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "identity_key")
    val identityKey: String,
    @ColumnInfo(name = "identity_id")
    val identityId: Long,
    @ColumnInfo(name = "identity_album")
    val identityAlbum: String,
    @ColumnInfo(name = "identity_media_uri")
    val identityMediaUri: String?,
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "album_id")
    val albumId: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "resume_position_ms")
    val resumePositionMs: Long,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String?,
    @ColumnInfo(name = "media_uri")
    val mediaUri: String?,
    @ColumnInfo(name = "matched_lyric")
    val matchedLyric: String?,
    @ColumnInfo(name = "matched_translated_lyric")
    val matchedTranslatedLyric: String?,
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
    @ColumnInfo(name = "source_stable_key")
    val sourceStableKey: String?,
    @ColumnInfo(name = "played_at")
    val playedAt: Long
)

internal fun PlayedEntry.toEntity(): PlayHistoryEntity {
    val identity = SongIdentity(
        id = id,
        album = album,
        mediaUri = localFilePath ?: mediaUri
    )
    return PlayHistoryEntity(
        identityKey = identity.stableKey(),
        identityId = identity.id,
        identityAlbum = identity.album,
        identityMediaUri = identity.mediaUri,
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        resumePositionMs = resumePositionMs,
        coverUrl = coverUrl,
        mediaUri = mediaUri,
        matchedLyric = matchedLyric,
        matchedTranslatedLyric = matchedTranslatedLyric,
        customCoverUrl = customCoverUrl,
        customName = customName,
        customArtist = customArtist,
        originalName = originalName,
        originalArtist = originalArtist,
        originalCoverUrl = originalCoverUrl,
        originalLyric = originalLyric,
        originalTranslatedLyric = originalTranslatedLyric,
        localFileName = localFileName,
        localFilePath = localFilePath,
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        sourceStableKey = sourceStableKey,
        playedAt = playedAt
    )
}
