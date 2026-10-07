package moe.ouom.neriplayer.data.local.database.store.stats

import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsDao
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotBucketData
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotCounterData
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDailyCounterData
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotDailyCounterEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotTrackData
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotTrackEntity
import moe.ouom.neriplayer.data.local.database.store.InlineTransactionDatabase
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

internal const val SNAPSHOT_ID = "staged"
internal const val DAY_MS = 86_400_000L

/** Real playback store over an inline-transaction database whose snapshot tables live in memory. */
internal class PlaybackStatsSnapshotHarness {
    val room = InlineTransactionDatabase()
    val staged = InMemoryPlaybackStatsSnapshotDao()
    val primary: PlaybackStatsDao = mock(PlaybackStatsDao::class.java)
    val store: PlaybackStatsRoomStore

    init {
        `when`(primary.observeRevision()).thenReturn(MutableStateFlow(null))
        `when`(primary.observeClearedAt()).thenReturn(MutableStateFlow(null))
        doReturn(primary).`when`(room.database).playbackStatsDao()
        doReturn(staged).`when`(room.database).playbackStatsSnapshotDao()
        store = PlaybackStatsRoomStore(room.database)
    }
}

internal fun stagedSnapshot(clearedAt: Long = 0, epoch: Long = clearedAt, sealed: Boolean = true, isDiff: Boolean = false) =
    PlaybackStatsSnapshotEntity(
        id = SNAPSHOT_ID,
        revision = 3,
        clearedAt = clearedAt,
        counterEpochStartedAt = epoch,
        createdAt = 1,
        sealed = sealed,
        ownerProcessId = "owner",
        isDiff = isDiff
    )

/** Staged track of a network song that also carries edits only this device knows about. */
internal fun stagedTrack(
    key: String,
    listen: Long,
    plays: Int,
    first: Long,
    last: Long,
    album: String = "album",
    albumId: Long = 5,
    mediaUri: String? = "https://music.example/$key",
    localFilePath: String? = null
) = PlaybackStatsSnapshotTrackData(
    identityKey = key,
    id = 7,
    name = "Stored $key",
    artist = "artist",
    album = album,
    albumId = albumId,
    coverUrl = "https://img.example/$key.jpg",
    durationMs = 180_000,
    totalListenMs = listen,
    playCount = plays,
    lastPlayedAt = last,
    firstPlayedAt = first,
    mediaUri = mediaUri,
    localFilePath = localFilePath,
    localFileName = localFilePath?.substringAfterLast('/'),
    customName = "Custom $key",
    customArtist = "Custom artist",
    customCoverUrl = "https://img.example/custom-$key.jpg"
)

internal fun stagedBucket(
    day: Long,
    key: String,
    listen: Long,
    plays: Int,
    first: Long,
    last: Long,
    name: String = "Stored $key",
    album: String = "album",
    albumId: Long = 5,
    mediaUri: String? = "https://music.example/$key",
    localFilePath: String? = null
) = PlaybackStatsSnapshotBucketData(
    dayStartAt = day,
    identityKey = key,
    id = 7,
    name = name,
    artist = "artist",
    album = album,
    albumId = albumId,
    coverUrl = "https://img.example/$key.jpg",
    durationMs = 180_000,
    totalListenMs = listen,
    playCount = plays,
    lastPlayedAt = last,
    firstPlayedAt = first,
    mediaUri = mediaUri,
    localFilePath = localFilePath,
    localFileName = localFilePath?.substringAfterLast('/'),
    customName = "Custom $key",
    customArtist = "Custom artist",
    customCoverUrl = "https://img.example/custom-$key.jpg"
)

internal fun stagedCounter(key: String, device: String, epoch: Long, listen: Long, plays: Int, first: Long, last: Long) =
    PlaybackStatsSnapshotCounterData(key, device, epoch, listen, plays, first, last)

internal fun stagedDailyCounter(day: Long, key: String, device: String, epoch: Long, listen: Long, plays: Int, first: Long, last: Long) =
    PlaybackStatsSnapshotDailyCounterData(day, key, device, epoch, listen, plays, first, last)

internal fun remoteTrack(
    key: String,
    listen: Long,
    plays: Int,
    first: Long,
    last: Long,
    mediaUri: String? = "https://music.example/$key",
    shards: List<SyncPlaybackCounterShard> = emptyList()
) = SyncTrackStat(
    identityKey = key,
    name = "Remote $key",
    artist = "artist",
    album = "album",
    totalListenMs = listen,
    playCount = plays,
    lastPlayedAt = last,
    firstPlayedAt = first,
    coverUrl = "https://img.example/remote-$key.jpg",
    durationMs = 200_000,
    mediaUri = mediaUri,
    id = 9,
    albumId = 5,
    counterShards = shards
)

internal fun remoteBucket(
    day: Long,
    key: String,
    listen: Long,
    plays: Int,
    first: Long,
    last: Long,
    mediaUri: String? = "https://music.example/$key",
    shards: List<SyncPlaybackCounterShard> = emptyList()
) = SyncPlaybackStatBucket(
    dayStartAt = day,
    identityKey = key,
    name = "Remote $key",
    artist = "artist",
    album = "album",
    totalListenMs = listen,
    playCount = plays,
    lastPlayedAt = last,
    firstPlayedAt = first,
    coverUrl = "https://img.example/remote-$key.jpg",
    durationMs = 200_000,
    mediaUri = mediaUri,
    id = 9,
    albumId = 5,
    counterShards = shards
)

internal suspend fun InMemoryPlaybackStatsSnapshotDao.stage(
    tracks: List<PlaybackStatsSnapshotTrackData> = emptyList(),
    buckets: List<PlaybackStatsSnapshotBucketData> = emptyList(),
    counters: List<PlaybackStatsSnapshotCounterData> = emptyList(),
    dailyCounters: List<PlaybackStatsSnapshotDailyCounterData> = emptyList(),
    id: String = SNAPSHOT_ID
) {
    upsertTracks(tracks.map { PlaybackStatsSnapshotTrackEntity(id, it) })
    upsertBuckets(buckets.map { PlaybackStatsSnapshotBucketEntity(id, it) })
    upsertCounters(counters.map { PlaybackStatsSnapshotCounterEntity(id, it) })
    upsertDailyCounters(dailyCounters.map { PlaybackStatsSnapshotDailyCounterEntity(id, it) })
}
