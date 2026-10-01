package moe.ouom.neriplayer.data.backup

import android.content.Context
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.mock

@RunWith(Parameterized::class)
class BackupMetadataExportPolicyTest(
    private val description: String,
    private val localFilePath: String?,
    private val album: String,
    private val mediaUri: String?,
    private val albumId: Long,
    private val expected: Boolean
) {
    private val context = mock(Context::class.java)

    @Test
    fun historyFiltersLocalMetadata() {
        val entry = PlayedEntry(
            id = 1L, name = "song", artist = "artist", album = album, albumId = albumId,
            durationMs = 1_000L, coverUrl = null, playedAt = 10L,
            mediaUri = mediaUri, localFilePath = localFilePath
        )

        assertEquals(description, expected, BackupMetadataMapper.shouldExportHistory(entry, context))
    }

    @Test
    fun trackStatsFilterLocalMetadata() {
        val stat = TrackStat(
            id = 1L, name = "song", artist = "artist", album = album, albumId = albumId,
            coverUrl = null, durationMs = 1_000L, totalListenMs = 2_000L, playCount = 2,
            lastPlayedAt = 10L, firstPlayedAt = 1L, mediaUri = mediaUri,
            localFilePath = localFilePath, localFileName = null, customName = null,
            customArtist = null, customCoverUrl = null, identityKey = "song:1"
        )

        assertEquals(description, expected, BackupMetadataMapper.shouldExportTrackStat(stat, context))
    }

    @Test
    fun dailyBucketsFilterLocalMetadata() {
        val bucket = PlaybackStatBucket(
            dayStartAt = 0L, id = 1L, name = "song", artist = "artist", album = album, albumId = albumId,
            coverUrl = null, durationMs = 1_000L, totalListenMs = 2_000L, playCount = 2,
            lastPlayedAt = 10L, firstPlayedAt = 1L, mediaUri = mediaUri,
            localFilePath = localFilePath, localFileName = null, customName = null,
            customArtist = null, customCoverUrl = null, identityKey = "song:1"
        )

        assertEquals(description, expected, BackupMetadataMapper.shouldExportPlaybackStatBucket(bucket, context))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any?>> = listOf(
            arrayOf("local path", "/music/song.mp3", "album", "https://audio/song", 1L, false),
            arrayOf("no local path", null, "album", "https://audio/song", 1L, true),
            arrayOf("empty local path", "", "album", "https://audio/song", 1L, true),
            arrayOf("blank local path", " \t", "album", "https://audio/song", 1L, true),
            arrayOf("file URI", null, "album", "file:///music/song.mp3", 1L, false),
            arrayOf("content URI", null, "album", "content://media/song", 1L, false),
            arrayOf("resource URI", null, "album", "android.resource://app/song", 1L, false),
            arrayOf("absolute media path", null, "album", "/music/song.mp3", 1L, false),
            arrayOf("local album identity", null, LocalSongSupport.LOCAL_ALBUM_IDENTITY, null, 0L, false),
            arrayOf("legacy Chinese local album", null, "本地文件", "", 0L, false),
            arrayOf("legacy English local album", null, "Local Files", " \t", 0L, false),
            arrayOf("remote album id with local label", null, LocalSongSupport.LOCAL_ALBUM_IDENTITY, null, 1L, true),
            arrayOf("remote URI with local label", null, LocalSongSupport.LOCAL_ALBUM_IDENTITY, "https://audio/song", 0L, true),
            arrayOf("normal album without URI", null, "album", null, 0L, true),
            arrayOf("blank album without URI", null, "", null, 0L, true)
        )
    }
}
