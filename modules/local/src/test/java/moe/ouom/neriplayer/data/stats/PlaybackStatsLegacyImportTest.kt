package moe.ouom.neriplayer.data.stats

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.dao.stats.PlaybackStatsDao
import moe.ouom.neriplayer.data.local.database.entity.stats.toEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toSnapshotData
import moe.ouom.neriplayer.data.local.database.store.InlineTransactionDatabase
import moe.ouom.neriplayer.data.local.database.store.expectFailure
import moe.ouom.neriplayer.data.local.database.store.stats.InMemoryPlaybackStatsSnapshotDao
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.CLEARED_AT_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.COUNTER_EPOCH_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore.Companion.REVISION_METADATA_KEY
import moe.ouom.neriplayer.data.local.database.store.stats.toDailyEntity
import moe.ouom.neriplayer.data.local.database.store.stats.toTrackEntity
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.stats.playbackStatsDayStartAt
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException

class PlaybackStatsLegacyImportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val gson = Gson()
    private val room = InlineTransactionDatabase()
    private val staged = spy(InMemoryPlaybackStatsSnapshotDao())
    private val primary = mock(PlaybackStatsDao::class.java)
    private lateinit var importer: PlaybackStatsLegacyImporter

    @Before
    fun setUp() = runTest {
        `when`(primary.observeRevision()).thenReturn(MutableStateFlow(null))
        `when`(primary.observeClearedAt()).thenReturn(MutableStateFlow(null))
        doReturn(primary).`when`(room.database).playbackStatsDao()
        doReturn(staged).`when`(room.database).playbackStatsSnapshotDao()
        doReturn(0L).`when`(staged).pendingDeltaCount()
        doReturn(Unit).`when`(staged).deleteLegacyOrphanCounters(anyString())
        doReturn(Unit).`when`(staged).deleteLegacyOrphanDailyCounters(anyString())
        doReturn(Unit).`when`(staged).publishTrack(anyString())
        doReturn(Unit).`when`(staged).publishBucket(anyString())
        doReturn(Unit).`when`(staged).publishCounter(anyString())
        doReturn(Unit).`when`(staged).publishDailyCounter(anyString())
        doReturn(Unit).`when`(staged).deleteTrack(anyString())
        doReturn(Unit).`when`(staged).deleteBucket(anyString())
        doReturn(Unit).`when`(staged).deleteCounter(anyString())
        doReturn(Unit).`when`(staged).deleteDailyCounter(anyString())
        doReturn(Unit).`when`(staged).deleteSnapshot(anyString())
        val context = mock(Context::class.java).also { context ->
            doReturn(temporaryFolder.root).`when`(context).filesDir
        }
        importer = PlaybackStatsLegacyImporter(context, gson, PlaybackStatsRoomStore(room.database))
    }

    @Test
    fun `a committed snapshot in the metadata file wins over the legacy table files`() = runTest {
        val track = track("song|1", listen = 9_000, plays = 3)
        val bucket = bucket(track)
        val trackShard = SyncPlaybackCounterShard("phone", 300, 4_000, 2, FIRST_PLAYED_AT, LAST_PLAYED_AT)
        val dailyShard = SyncPlaybackCounterShard("phone", 300, 1_000, 1, FIRST_PLAYED_AT, LAST_PLAYED_AT)
        write("playback_stats.json", gson.toJson(listOf(track("song|2", listen = 1, plays = 1))))
        write(
            "playback_stats_meta.json",
            """{"clearedAt":5,"unknown":[1,2],"snapshot":{""" +
                """"stats":${gson.toJson(listOf(track))},"dailyStats":${gson.toJson(listOf(bucket))},""" +
                """"counterSnapshot":{"epochStartedAt":99,""" +
                """"trackShardsByIdentity":{"song|1":${gson.toJson(listOf(trackShard))}},""" +
                """"dailyShardsByBucketKey":{"$DAY_START|song|1":${gson.toJson(listOf(dailyShard))}},"legacy":true},""" +
                """"counterEpochStartedAt":300,"clearedAt":200,"future":{"flag":1}}}"""
        )

        importer.migrate()

        val sealed = staged.snapshots.values.single()
        assertTrue(sealed.sealed)
        assertEquals(listOf(200L, 300L), listOf(sealed.clearedAt, sealed.counterEpochStartedAt))
        assertEquals(listOf(track.toEntity().toSnapshotData()), staged.storedTracks(sealed.id))
        assertEquals(listOf(bucket.toEntity().toSnapshotData()), staged.storedBuckets(sealed.id))
        assertEquals(listOf(trackShard.toTrackEntity("song|1").toSnapshotData()), staged.storedCounters(sealed.id))
        assertEquals(
            listOf(dailyShard.toDailyEntity(DAY_START, "song|1").toSnapshotData()),
            staged.storedDailyCounters(sealed.id)
        )
        assertEquals(listOf("1", "200", "300"), committedMetadata())
        verify(staged).publishTrack(sealed.id)
        verify(staged).deleteSnapshot(sealed.id)
    }

    @Test
    fun `legacy table files are imported in pages when the metadata holds no committed snapshot`() = runTest {
        val tracks = (1..257).map { index -> track("song|$index", listen = 1_000L * index, plays = index) }
        val bucket = bucket(tracks.first())
        val shard = SyncPlaybackCounterShard("phone", 70, 500, 1, FIRST_PLAYED_AT, LAST_PLAYED_AT)
        write("playback_stats_meta.json", """{"clearedAt":50,"snapshot":null}""")
        write("playback_stats.json", gson.toJson(tracks))
        write("playback_stats_daily.json", gson.toJson(listOf(bucket)))
        write(
            "playback_stats_counters.json",
            """{"epochStartedAt":70,"trackShardsByIdentity":{"song|1":${gson.toJson(listOf(shard))}},"note":"x"}"""
        )

        importer.migrate()

        val sealed = staged.snapshots.values.single()
        assertEquals(listOf(50L, 70L), listOf(sealed.clearedAt, sealed.counterEpochStartedAt))
        assertEquals(
            tracks.map { it.toEntity().toSnapshotData() }.sortedBy { it.identityKey },
            staged.storedTracks(sealed.id).sortedBy { it.identityKey }
        )
        assertEquals(listOf(bucket.toEntity().toSnapshotData()), staged.storedBuckets(sealed.id))
        assertEquals(listOf(shard.toTrackEntity("song|1").toSnapshotData()), staged.storedCounters(sealed.id))
        assertEquals(listOf("1", "50", "70"), committedMetadata())
    }

    @Test
    fun `without a daily file only single day legacy tracks get a rebuilt bucket`() = runTest {
        val sameDay = track("song|1", listen = 6_000, plays = 2)
        val multiDay = track("song|2", listen = 8_000, plays = 4, firstPlayedAt = FIRST_PLAYED_AT - 3 * DAY_MS)
        write("playback_stats.json", gson.toJson(listOf(sameDay, multiDay)))

        importer.migrate()

        val sealed = staged.snapshots.values.single()
        assertEquals(listOf(0L, 0L), listOf(sealed.clearedAt, sealed.counterEpochStartedAt))
        assertEquals(
            listOf(Triple("song|1", DAY_START, 2)),
            staged.storedBuckets(sealed.id).map { Triple(it.identityKey, it.dayStartAt, it.playCount) }
        )
        assertEquals(listOf("song|1", "song|2"), staged.storedTracks(sealed.id).map { it.identityKey })
        assertTrue(staged.storedCounters(sealed.id).isEmpty())
    }

    @Test
    fun `malformed committed snapshots abort the import and release the staged copy`() = runTest {
        val complete = """"stats":[],"dailyStats":[],"counterSnapshot":{},"counterEpochStartedAt":1,"clearedAt":1"""
        val failures = linkedMapOf(
            """{"snapshot":{$complete},"snapshot":{$complete}}""" to "Duplicate playback snapshot",
            """{"snapshot":{"stats":[],"stats":[]}}""" to "Duplicate playback snapshot field",
            """{"snapshot":{"stats":[],"counterSnapshot":{},"counterEpochStartedAt":1,"clearedAt":1}}""" to
                "Incomplete committed playback snapshot",
            """{"snapshot":{"stats":[null]}}""" to "Null playback record"
        )

        for ((metadata, message) in failures) {
            write("playback_stats_meta.json", metadata)
            assertEquals(message, expectFailure<IOException> { importer.migrate() }.message)
        }

        verify(staged, times(failures.size)).deleteSnapshot(anyString())
        verify(staged, never()).publishTrack(anyString())
    }

    private fun committedMetadata(): List<String?> =
        listOf(REVISION_METADATA_KEY, CLEARED_AT_METADATA_KEY, COUNTER_EPOCH_METADATA_KEY).map(room.metadata::value)

    private fun write(name: String, json: String) {
        File(temporaryFolder.root, name).writeText(json)
    }

    private fun track(
        key: String,
        listen: Long,
        plays: Int,
        firstPlayedAt: Long = FIRST_PLAYED_AT
    ): TrackStat {
        val id = key.substringAfter('|').toLong()
        return TrackStat(
            id = id,
            name = "Song $id",
            artist = "Neri Band",
            album = "Demo Tape",
            coverUrl = null,
            durationMs = 180_000,
            totalListenMs = listen,
            playCount = plays,
            lastPlayedAt = LAST_PLAYED_AT,
            firstPlayedAt = firstPlayedAt,
            mediaUri = "https://cdn.example.com/$id.flac",
            localFilePath = null,
            localFileName = null,
            customName = null,
            customArtist = null,
            customCoverUrl = null,
            identityKey = key
        )
    }

    private fun bucket(track: TrackStat) = PlaybackStatBucket(
        dayStartAt = DAY_START,
        id = track.id,
        name = track.name,
        artist = track.artist,
        album = track.album,
        coverUrl = track.coverUrl,
        durationMs = track.durationMs,
        totalListenMs = track.totalListenMs,
        playCount = track.playCount,
        lastPlayedAt = track.lastPlayedAt,
        firstPlayedAt = track.firstPlayedAt,
        mediaUri = track.mediaUri,
        localFilePath = null,
        localFileName = null,
        customName = null,
        customArtist = null,
        customCoverUrl = null,
        identityKey = track.identityKey
    )

    private companion object {
        const val DAY_MS = 86_400_000L

        // 06:00 UTC keeps both plays on one local calendar day in every time zone
        const val FIRST_PLAYED_AT = 1_759_989_600_000L
        const val LAST_PLAYED_AT = FIRST_PLAYED_AT + 60_000L
        val DAY_START = playbackStatsDayStartAt(FIRST_PLAYED_AT)
    }
}
