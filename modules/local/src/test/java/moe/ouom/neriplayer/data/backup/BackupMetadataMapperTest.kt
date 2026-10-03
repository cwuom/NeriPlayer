package moe.ouom.neriplayer.data.backup

import android.content.Context
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.sync.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

class BackupMetadataMapperTest {
    private val context = mock(Context::class.java)

    @Test
    fun historyRoundTripKeepsPlaybackAndEditableMetadata() {
        val entry = PlayedEntry(
            id = 7L, name = "song", artist = "artist", album = "album", albumId = 8L,
            durationMs = 1_000L, resumePositionMs = 123L, coverUrl = "https://cover",
            mediaUri = "https://audio", playedAt = 456L,
            matchedLyric = "matched", matchedTranslatedLyric = "translated",
            customCoverUrl = "https://custom-cover", customName = "custom-name", customArtist = "custom-artist",
            originalName = "original-name", originalArtist = "original-artist", originalCoverUrl = "https://original-cover",
            originalLyric = "original", originalTranslatedLyric = "original-translated",
            matchedRomanizedLyric = "romanized", matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "42", lyricSyncEdited = true, lyricSyncRevision = 20,
            userLyricOffsetMs = 123, originalRomanizedLyric = "original romanized"
        )

        val exported = BackupMetadataMapper.toSyncRecentPlay(entry)

        assertEquals(7L, exported.songId)
        assertEquals("manual_backup", exported.deviceId)
        assertEquals(CURRENT_SYNC_METADATA_VERSION, exported.song.syncMetadataVersion)
        assertEquals(entry, BackupMetadataMapper.toPlayedEntry(exported, context))
    }

    @Test
    fun manualBackupKeepsUnknownLyricsWithoutConfirmingThemForSync() {
        CoverUrlMapper.installForTest(CoverUrlMapper.createForTest())
        try {
            val unknown = SongItem(7, "song", "artist", "netease", 1, 100, null,
                matchedLyric = "unknown old lyrics", matchedTranslatedLyric = "old translation",
                matchedRomanizedLyric = "old romanized", matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "42")
            val backup = BackupMetadataMapper.toSyncPlaylist(LocalPlaylist(8, "playlist", mutableListOf(unknown)), context)
            val song = backup.songs.single()
            assertEquals(unknown.matchedLyric, song.matchedLyric)
            assertEquals(unknown.matchedTranslatedLyric, song.matchedTranslatedLyric)
            assertEquals(unknown.matchedRomanizedLyric, song.matchedRomanizedLyric)
            assertNull(song.lyricSyncEdited)
            assertEquals(0L, song.lyricSyncRevision)
            assertEquals("42", song.matchedSongId)
        } finally {
            CoverUrlMapper.installForTest(null)
        }
    }

    @Test
    fun exportedHistoryRemovesLocalMediaReference() {
        val entry = PlayedEntry(
            id = 7L, name = "song", artist = "artist", album = "album",
            durationMs = 1_000L, coverUrl = null, mediaUri = "content://media/song", playedAt = 456L
        )

        assertNull(BackupMetadataMapper.toSyncRecentPlay(entry).song.mediaUri)
    }

    @Test
    fun restoredHistoryRejectsLocalUrisAndLegacyLocalAlbum() {
        val localSongs = listOf(
            SyncSong(album = "album", albumId = 1L, mediaUri = "file:///music/song"),
            SyncSong(album = "album", albumId = 1L, mediaUri = "content://media/song"),
            SyncSong(album = LocalSongSupport.LOCAL_ALBUM_IDENTITY, albumId = 0L)
        )

        localSongs.forEach { song ->
            assertNull(BackupMetadataMapper.toPlayedEntry(SyncRecentPlay(song = song), context))
        }
    }

    @Test
    fun trackStatExportPreservesCountersAndIdentityWhileRemovingLocalUri() {
        val stat = TrackStat(
            id = 7L, name = "song", artist = "artist", album = "album", albumId = 8L,
            coverUrl = "https://cover", durationMs = 1_000L, totalListenMs = 2_000L, playCount = 3,
            lastPlayedAt = 100L, firstPlayedAt = 10L, mediaUri = "file:///music/song",
            localFilePath = null, localFileName = null, customName = null, customArtist = null,
            customCoverUrl = null, identityKey = "song:7"
        )

        assertEquals(
            SyncTrackStat(
                identityKey = "song:7", name = "song", artist = "artist", album = "album",
                totalListenMs = 2_000L, playCount = 3, lastPlayedAt = 100L, firstPlayedAt = 10L,
                coverUrl = "https://cover", durationMs = 1_000L, mediaUri = null, id = 7L, albumId = 8L
            ),
            BackupMetadataMapper.toSyncTrackStat(stat)
        )
    }

    @Test
    fun dailyBucketExportKeepsDayAndCounterBase() {
        val bucket = PlaybackStatBucket(
            dayStartAt = 50L, id = 7L, name = "song", artist = "artist", album = "album", albumId = 8L,
            coverUrl = "https://cover", durationMs = 1_000L, totalListenMs = 2_000L, playCount = 3,
            lastPlayedAt = 100L, firstPlayedAt = 60L, mediaUri = "https://audio",
            localFilePath = null, localFileName = null, customName = null, customArtist = null,
            customCoverUrl = null, identityKey = "song:7"
        )

        assertEquals(
            SyncPlaybackStatBucket(
                dayStartAt = 50L, identityKey = "song:7", name = "song", artist = "artist", album = "album",
                totalListenMs = 2_000L, playCount = 3, lastPlayedAt = 100L, firstPlayedAt = 60L,
                coverUrl = "https://cover", durationMs = 1_000L, mediaUri = "https://audio", id = 7L, albumId = 8L,
                counterBaseListenMs = 2_000L, counterBasePlayCount = 3
            ),
            BackupMetadataMapper.toSyncPlaybackStatBucket(bucket)
        )
    }

    @Test
    fun restoreSanitizationRejectsLocalStatsAndNormalizesRemoteCounters() {
        val stat = SyncTrackStat(
            identityKey = "song:7", album = "album", albumId = 8L, mediaUri = "https://audio",
            totalListenMs = -10L, playCount = -1, lastPlayedAt = 100L, firstPlayedAt = 200L,
            durationMs = -5L, coverUrl = "file:///cover", counterBaseListenMs = -1L, counterBasePlayCount = -1
        )
        val bucket = SyncPlaybackStatBucket(
            dayStartAt = 50L, identityKey = "song:7", album = "album", albumId = 8L, mediaUri = "https://audio",
            totalListenMs = -10L, playCount = -1, lastPlayedAt = 100L, firstPlayedAt = 200L,
            durationMs = -5L, coverUrl = "file:///cover", counterBaseListenMs = -1L, counterBasePlayCount = -1
        )

        assertEquals(
            stat.copy(
                totalListenMs = 0L, playCount = 0, firstPlayedAt = 100L, durationMs = 0L,
                coverUrl = null, counterBaseListenMs = 0L, counterBasePlayCount = 0
            ),
            BackupMetadataMapper.sanitizeTrackStat(stat, context)
        )
        assertEquals(
            bucket.copy(
                totalListenMs = 0L, playCount = 0, firstPlayedAt = 100L, durationMs = 0L,
                coverUrl = null, counterBaseListenMs = 0L, counterBasePlayCount = 0
            ),
            BackupMetadataMapper.sanitizePlaybackStatBucket(bucket, context)
        )
        assertNull(BackupMetadataMapper.sanitizeTrackStat(stat.copy(mediaUri = "content://media/song"), context))
        assertNull(BackupMetadataMapper.sanitizePlaybackStatBucket(bucket.copy(mediaUri = "file:///music/song"), context))
    }
}
