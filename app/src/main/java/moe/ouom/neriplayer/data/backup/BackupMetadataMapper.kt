package moe.ouom.neriplayer.data.backup

import android.content.Context
import moe.ouom.neriplayer.data.history.PlayedEntry
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.stats.TrackStat
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.model.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.sync.model.SyncRecentPlay
import moe.ouom.neriplayer.data.sync.model.SyncSong
import moe.ouom.neriplayer.data.sync.model.SyncTrackStat

internal object BackupMetadataMapper {
    private const val BACKUP_DEVICE_ID = "manual_backup"

    fun shouldExportHistory(entry: PlayedEntry, context: Context): Boolean {
        return entry.localFilePath.isNullOrBlank() &&
            !LocalSongSupport.isLocalSong(entry.album, entry.mediaUri, entry.albumId, context)
    }

    fun shouldExportTrackStat(stat: TrackStat, context: Context): Boolean {
        return stat.localFilePath.isNullOrBlank() &&
            !LocalSongSupport.isLocalSong(stat.album, stat.mediaUri, stat.albumId, context)
    }

    fun shouldExportPlaybackStatBucket(bucket: PlaybackStatBucket, context: Context): Boolean {
        return bucket.localFilePath.isNullOrBlank() &&
            !LocalSongSupport.isLocalSong(bucket.album, bucket.mediaUri, bucket.albumId, context)
    }

    fun toSyncRecentPlay(entry: PlayedEntry): SyncRecentPlay {
        return SyncRecentPlay(
            songId = entry.id,
            song = SyncSong(
                id = entry.id,
                name = entry.name,
                artist = entry.artist,
                album = entry.album,
                albumId = entry.albumId,
                durationMs = entry.durationMs,
                coverUrl = entry.coverUrl,
                mediaUri = LocalSongSupport.sanitizeMediaUriForSync(entry.mediaUri),
                matchedLyric = entry.matchedLyric,
                matchedTranslatedLyric = entry.matchedTranslatedLyric,
                customCoverUrl = entry.customCoverUrl,
                customName = entry.customName,
                customArtist = entry.customArtist,
                originalName = entry.originalName,
                originalArtist = entry.originalArtist,
                originalCoverUrl = entry.originalCoverUrl,
                originalLyric = entry.originalLyric,
                originalTranslatedLyric = entry.originalTranslatedLyric,
                syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION
            ),
            playedAt = entry.playedAt,
            deviceId = BACKUP_DEVICE_ID
        )
    }

    fun toPlayedEntry(syncPlay: SyncRecentPlay, context: Context): PlayedEntry? {
        val song = syncPlay.song
        if (LocalSongSupport.isLocalSong(song.album, song.mediaUri, song.albumId, context)) {
            return null
        }
        return PlayedEntry(
            id = song.id,
            name = song.name,
            artist = song.artist,
            album = song.album,
            albumId = song.albumId,
            durationMs = song.durationMs,
            coverUrl = song.coverUrl,
            mediaUri = LocalSongSupport.sanitizeMediaUriForSync(song.mediaUri),
            matchedLyric = song.matchedLyric,
            matchedTranslatedLyric = song.matchedTranslatedLyric,
            customCoverUrl = song.customCoverUrl,
            customName = song.customName,
            customArtist = song.customArtist,
            originalName = song.originalName,
            originalArtist = song.originalArtist,
            originalCoverUrl = song.originalCoverUrl,
            originalLyric = song.originalLyric,
            originalTranslatedLyric = song.originalTranslatedLyric,
            playedAt = syncPlay.playedAt
        )
    }

    fun toSyncTrackStat(stat: TrackStat): SyncTrackStat {
        return SyncTrackStat(
            identityKey = stat.identityKey,
            name = stat.name,
            artist = stat.artist,
            album = stat.album,
            totalListenMs = stat.totalListenMs,
            playCount = stat.playCount,
            lastPlayedAt = stat.lastPlayedAt,
            firstPlayedAt = stat.firstPlayedAt,
            coverUrl = stat.coverUrl,
            durationMs = stat.durationMs,
            mediaUri = LocalSongSupport.sanitizeMediaUriForSync(stat.mediaUri),
            id = stat.id,
            albumId = stat.albumId
        )
    }

    fun toSyncPlaybackStatBucket(bucket: PlaybackStatBucket): SyncPlaybackStatBucket {
        return SyncPlaybackStatMapper.fromPlaybackStatBucket(bucket)
    }

    fun sanitizeTrackStat(stat: SyncTrackStat, context: Context): SyncTrackStat? {
        return SyncPlaybackStatMapper.sanitize(stat, context)
    }

    fun sanitizePlaybackStatBucket(
        bucket: SyncPlaybackStatBucket,
        context: Context
    ): SyncPlaybackStatBucket? {
        return SyncPlaybackStatMapper.sanitize(bucket, context)
    }
}
