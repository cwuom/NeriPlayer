package moe.ouom.neriplayer.data.backup

import android.content.Context
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.sync.mapping.fromLocalPlaylist
import moe.ouom.neriplayer.data.sync.mapping.fromSongItemOrNull
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.model.sync.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat

internal object BackupMetadataMapper {
    private const val BACKUP_DEVICE_ID = "manual_backup"

    fun toSyncPlaylist(playlist: LocalPlaylist, context: Context): SyncPlaylist {
        val metadata = SyncPlaylist.fromLocalPlaylist(playlist.copy(songs = mutableListOf()), playlist.modifiedAt, context)
        return metadata.copy(songs = playlist.songs.mapNotNull { toBackupSongOrNull(it, context) })
    }

    private fun toBackupSongOrNull(song: SongItem, context: Context): SyncSong? =
        SyncSong.fromSongItemOrNull(song, context)?.copy(
            matchedLyric = song.matchedLyric, matchedTranslatedLyric = song.matchedTranslatedLyric,
            matchedRomanizedLyric = song.matchedRomanizedLyric, originalLyric = song.originalLyric,
            originalTranslatedLyric = song.originalTranslatedLyric, originalRomanizedLyric = song.originalRomanizedLyric,
            lyricSyncEdited = song.lyricSyncEdited,
            lyricSyncRevision = song.lyricSyncRevision
        )

    fun shouldExportHistory(entry: PlayedEntry, context: Context): Boolean {
        return shouldExportRemoteMetadata(entry.localFilePath, entry.album, entry.mediaUri, entry.albumId, context)
    }

    fun shouldExportTrackStat(stat: TrackStat, context: Context): Boolean {
        return shouldExportRemoteMetadata(stat.localFilePath, stat.album, stat.mediaUri, stat.albumId, context)
    }

    fun shouldExportPlaybackStatBucket(bucket: PlaybackStatBucket, context: Context): Boolean {
        return shouldExportRemoteMetadata(bucket.localFilePath, bucket.album, bucket.mediaUri, bucket.albumId, context)
    }

    private fun shouldExportRemoteMetadata(
        localFilePath: String?,
        album: String,
        mediaUri: String?,
        albumId: Long,
        context: Context
    ): Boolean {
        return localFilePath.isNullOrBlank() &&
            !LocalSongSupport.isLocalSong(album, mediaUri, albumId, context)
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
                matchedRomanizedLyric = entry.matchedRomanizedLyric,
                matchedLyricSource = entry.matchedLyricSource?.name,
                matchedSongId = entry.matchedSongId,
                lyricSyncEdited = entry.lyricSyncEdited,
                lyricSyncRevision = entry.lyricSyncRevision,
                userLyricOffsetMs = entry.userLyricOffsetMs,
                customCoverUrl = entry.customCoverUrl,
                customName = entry.customName,
                customArtist = entry.customArtist,
                originalName = entry.originalName,
                originalArtist = entry.originalArtist,
                originalCoverUrl = entry.originalCoverUrl,
                originalLyric = entry.originalLyric,
                originalTranslatedLyric = entry.originalTranslatedLyric,
                originalRomanizedLyric = entry.originalRomanizedLyric,
                syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION
            ),
            playedAt = entry.playedAt,
            deviceId = BACKUP_DEVICE_ID,
            resumePositionMs = entry.resumePositionMs
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
            matchedRomanizedLyric = song.matchedRomanizedLyric,
            matchedLyricSource = song.matchedLyricSource?.let { runCatching { moe.ouom.neriplayer.data.model.music.MusicPlatform.valueOf(it) }.getOrNull() },
            matchedSongId = song.matchedSongId,
            lyricSyncEdited = song.lyricSyncEdited,
            lyricSyncRevision = song.lyricSyncRevision,
            userLyricOffsetMs = song.userLyricOffsetMs,
            customCoverUrl = song.customCoverUrl,
            customName = song.customName,
            customArtist = song.customArtist,
            originalName = song.originalName,
            originalArtist = song.originalArtist,
            originalCoverUrl = song.originalCoverUrl,
            originalLyric = song.originalLyric,
            originalTranslatedLyric = song.originalTranslatedLyric,
            originalRomanizedLyric = song.originalRomanizedLyric,
            resumePositionMs = syncPlay.resumePositionMs,
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
