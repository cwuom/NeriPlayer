package moe.ouom.neriplayer.data.local.database.store.stats

import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsDao
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatDailyCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatEntity
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyList
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.doAnswer

/** Read-only primary playback tables answered through the paging and lookup queries of a mocked DAO. */
internal class PrimaryPlaybackTables(
    tracks: List<PlaybackStatEntity> = emptyList(),
    buckets: List<PlaybackStatBucketEntity> = emptyList(),
    private val counters: List<PlaybackStatCounterShardEntity> = emptyList(),
    private val dailyCounters: List<PlaybackStatDailyCounterShardEntity> = emptyList()
) {
    private val tracks = tracks.sortedBy { it.identityKey }
    private val dayOrder = buckets.sortedWith(compareBy({ it.dayStartAt }, { it.identityKey }))
    private val identityOrder = buckets.sortedWith(compareBy({ it.identityKey }, { it.dayStartAt }))

    suspend fun serve(dao: PlaybackStatsDao) {
        doAnswer { call ->
            val after = call.getArgument<String?>(0)
            tracks.filter { after == null || it.identityKey > after }.take(call.getArgument(1))
        }.`when`(dao).identityPage(any(), anyInt())
        doAnswer { call ->
            val keys = call.getArgument<List<String>>(0)
            tracks.filter { it.identityKey in keys }
        }.`when`(dao).tracksByKeys(anyList())
        doAnswer { call ->
            val keys = call.getArgument<List<String>>(0)
            counters.filter { it.identityKey in keys }.sortedWith(compareBy({ it.identityKey }, { it.deviceId }, { it.epochStartedAt }))
        }.`when`(dao).trackCountersByKeys(anyList())
        doAnswer { call ->
            val day = call.getArgument<Long?>(0)
            val identity = call.getArgument<String?>(1)
            dayOrder.filter { day == null || it.dayStartAt > day || (it.dayStartAt == day && it.identityKey > checkNotNull(identity)) }
                .take(call.getArgument(2))
        }.`when`(dao).bucketPage(any(), any(), anyInt())
        doAnswer { call ->
            val identity = call.getArgument<String?>(0)
            val day = call.getArgument<Long?>(1)
            identityOrder.filter { identity == null || it.identityKey > identity || (it.identityKey == identity && it.dayStartAt > checkNotNull(day)) }
                .take(call.getArgument(2))
        }.`when`(dao).bucketIdentityPage(any(), any(), anyInt())
        doAnswer { call ->
            val day = call.getArgument<Long>(0)
            val keys = call.getArgument<List<String>>(1)
            dailyCounters.filter { it.dayStartAt == day && it.identityKey in keys }
                .sortedWith(compareBy({ it.identityKey }, { it.deviceId }, { it.epochStartedAt }))
        }.`when`(dao).dailyCountersByKeys(anyLong(), anyList())
    }
}

/** Primary row of a network song as [remoteTrack] describes it, plus edits only this device made. */
internal fun remoteRow(
    key: String,
    listen: Long,
    plays: Int,
    first: Long,
    last: Long,
    customName: String? = null,
    localFilePath: String? = null
) = PlaybackStatEntity(
    identityKey = key, id = 9, name = "Remote $key", artist = "artist", album = "album", albumId = 5,
    coverUrl = "https://img.example/remote-$key.jpg", durationMs = 200_000, totalListenMs = listen, playCount = plays,
    lastPlayedAt = last, firstPlayedAt = first, mediaUri = "https://music.example/$key", localFilePath = localFilePath,
    localFileName = localFilePath?.substringAfterLast('/'), customName = customName, customArtist = null, customCoverUrl = null
)

internal fun remoteBucketRow(day: Long, key: String, listen: Long, plays: Int, first: Long, last: Long, customName: String? = null) =
    PlaybackStatBucketEntity(
        dayStartAt = day, identityKey = key, id = 9, name = "Remote $key", artist = "artist", album = "album", albumId = 5,
        coverUrl = "https://img.example/remote-$key.jpg", durationMs = 200_000, totalListenMs = listen, playCount = plays,
        lastPlayedAt = last, firstPlayedAt = first, mediaUri = "https://music.example/$key", localFilePath = null,
        localFileName = null, customName = customName, customArtist = null, customCoverUrl = null
    )

/** Primary row of a song stored on this device; such rows never take part in sync. */
internal fun deviceRow(key: String, listen: Long, plays: Int, first: Long, last: Long) = PlaybackStatEntity(
    identityKey = key, id = 3, name = "Local $key", artist = "artist", album = "album", albumId = 0,
    coverUrl = "https://img.example/$key.jpg", durationMs = 120_000, totalListenMs = listen, playCount = plays,
    lastPlayedAt = last, firstPlayedAt = first, mediaUri = null, localFilePath = "/storage/emulated/0/Music/$key.flac",
    localFileName = "$key.flac", customName = "Custom $key", customArtist = null, customCoverUrl = null
)

internal fun deviceBucketRow(day: Long, key: String, listen: Long, plays: Int, first: Long, last: Long) = PlaybackStatBucketEntity(
    dayStartAt = day, identityKey = key, id = 3, name = "Local $key", artist = "artist", album = "album", albumId = 0,
    coverUrl = "https://img.example/$key.jpg", durationMs = 120_000, totalListenMs = listen, playCount = plays,
    lastPlayedAt = last, firstPlayedAt = first, mediaUri = null, localFilePath = "/storage/emulated/0/Music/$key.flac",
    localFileName = "$key.flac", customName = "Custom $key", customArtist = null, customCoverUrl = null
)
