package moe.ouom.neriplayer.data.stats

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.local.database.store.stats.toDomain
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PlaybackStatsLegacyRecoveryTest {
    @Test
    fun corruptLegacyFilesRemainUnpromotedAndMayBeRetriedAfterRepair() = runTest {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val valid = mapOf("playback_stats.json" to Gson().toJson(listOf(track())), "playback_stats_daily.json" to "[]", "playback_stats_counters.json" to "{}", "playback_stats_meta.json" to "{}")
        for ((name, repaired) in valid) {
            val directory = File(base.cacheDir, "stats-legacy-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val database = Room.inMemoryDatabaseBuilder(base, NeriUserDataDatabase::class.java).build()
            try {
                val context = isolatedContext(base, directory)
                val file = File(directory, name).apply { writeText("{broken") }
                val store = PlaybackStatsRoomStore(database)
                val repository = PlaybackStatsRepository(context, store, backgroundScope) { "device" }
                assertFalse(name, repository.awaitInitialized())
                assertEquals(null, store.readPrimaryState())
                assertEquals("{broken", file.readText())
                file.writeText(repaired)
                assertTrue(name, repository.awaitInitialized())
                assertTrue(store.readPrimaryState() != null)
                assertEquals(repaired, file.readText())
            } finally { database.close(); directory.deleteRecursively() }
        }
    }

    @Test
    fun committedMetadataSnapshotOverridesMixedInterruptedLegacyProjections() = runTest {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "stats-legacy-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val database = Room.inMemoryDatabaseBuilder(base, NeriUserDataDatabase::class.java).build()
        try {
            val expected = track().copy(totalListenMs = 45_000, customName = "kept title", localFilePath = "/local/audio.flac")
            File(directory, "playback_stats_meta.json").writeText(Gson().toJson(mapOf("clearedAt" to 50, "snapshot" to mapOf(
                "stats" to listOf(expected), "dailyStats" to emptyList<Any>(), "counterSnapshot" to mapOf("trackShardsByIdentity" to emptyMap<String, Any>(), "dailyShardsByBucketKey" to emptyMap<String, Any>()),
                "counterEpochStartedAt" to 50, "clearedAt" to 50))))
            for (name in listOf("playback_stats.json", "playback_stats_daily.json", "playback_stats_counters.json")) File(directory, name).writeText("{broken")
            val store = PlaybackStatsRoomStore(database)
            val repository = PlaybackStatsRepository(isolatedContext(base, directory), store, backgroundScope) { "device" }
            assertTrue(repository.awaitInitialized())
            assertEquals(expected, store.readTrack(expected.identityKey))
            assertEquals(50L, repository.statsClearedAtFlow.value)
        } finally { database.close(); directory.deleteRecursively() }
    }

    @Test
    fun finalLegacySyntaxFailureDoesNotPublishAlreadyParsedPages() = runTest {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "stats-legacy-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val database = Room.inMemoryDatabaseBuilder(base, NeriUserDataDatabase::class.java).build()
        try {
            val rows = (0..599).map { track().copy(id = it.toLong(), identityKey = "track|$it") }
            val complete = Gson().toJson(rows)
            val file = File(directory, "playback_stats.json").apply { writeText(complete.dropLast(1)) }
            val store = PlaybackStatsRoomStore(database)
            val repository = PlaybackStatsRepository(isolatedContext(base, directory), store, backgroundScope) { "device" }
            assertFalse(repository.awaitInitialized())
            assertEquals(null, store.readPrimaryState())
            assertEquals(0, database.playbackStatsDao().getStats().size)
            file.writeText(complete)
            assertTrue(repository.awaitInitialized())
            assertEquals(600, database.playbackStatsDao().getStats().size)
        } finally { database.close(); directory.deleteRecursively() }
    }

    @Test
    fun failedMainPromotionKeepsAllLegacyFilesAndRollsBackEveryParsedPage() = runTest {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "stats-legacy-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val database = Room.inMemoryDatabaseBuilder(base, NeriUserDataDatabase::class.java).build()
        try {
            val rows = (0..599).map { track().copy(id = it.toLong(), identityKey = "track|$it") }
            val complete = Gson().toJson(rows)
            val file = File(directory, "playback_stats.json").apply { writeText(complete) }
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_late_legacy BEFORE INSERT ON playback_stat WHEN NEW.id = 599 BEGIN SELECT RAISE(ABORT, 'injected late promotion failure'); END")
            val store = PlaybackStatsRoomStore(database)
            val repository = PlaybackStatsRepository(isolatedContext(base, directory), store, backgroundScope) { "device" }
            assertFalse(repository.awaitInitialized())
            assertEquals(null, store.readPrimaryState())
            assertEquals(0, database.playbackStatsDao().getStats().size)
            assertEquals(complete, file.readText())
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_late_legacy")
            assertTrue(repository.awaitInitialized())
            assertEquals(600, database.playbackStatsDao().getStats().size)
        } finally { database.close(); directory.deleteRecursively() }
    }

    @Test
    fun duplicateLegacyCounterShardsWithinOnePageKeepMaximaAndSurviveRoomReopen() = runTest {
        verifyDuplicateCountersAcrossPromotion(crossPage = false)
    }

    @Test
    fun duplicateCommittedCounterShardsAcrossPagesKeepMaximaAndSurviveRoomReopen() = runTest {
        verifyDuplicateCountersAcrossPromotion(crossPage = true)
    }

    @Test
    fun legacyProjectionWithTrimmedDailyCountersPromotesWithoutChangingSourceFiles() = runTest {
        verifyLegacyCounterParents(committed = false, keepBuckets = false)
    }

    @Test
    fun committedSnapshotWithTrimmedDailyCountersPromotesWithoutChangingSourceFiles() = runTest {
        verifyLegacyCounterParents(committed = true, keepBuckets = false)
    }

    @Test
    fun counterFirstCommittedSnapshotKeepsLateParentsAndCountersAcrossPages() = runTest {
        verifyLegacyCounterParents(committed = true, keepBuckets = true, rowCount = 600)
    }

    private suspend fun verifyLegacyCounterParents(committed: Boolean, keepBuckets: Boolean, rowCount: Int = 1) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "stats-legacy-parents-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val database = Room.inMemoryDatabaseBuilder(base, NeriUserDataDatabase::class.java).build()
        try {
            val rows = (0 until rowCount).map { index ->
                track().copy(id = index.toLong(), identityKey = "track|${index.toString().padStart(4, '0')}",
                    localFilePath = "/audio-$index.flac", customName = "kept title $index")
            }
            val buckets = if (keepBuckets) rows.map { row ->
                PlaybackStatBucket(0, row.id, row.name, row.artist, row.album, row.albumId, row.coverUrl,
                    row.durationMs, row.totalListenMs, row.playCount, row.lastPlayedAt, row.firstPlayedAt,
                    row.mediaUri, row.localFilePath, row.localFileName, row.customName, row.customArtist,
                    row.customCoverUrl, row.identityKey)
            } else emptyList()
            val shard = SyncPlaybackCounterShard("device", 0, 30_000, 1, 100, 200)
            val trackCounters = linkedMapOf<String, List<SyncPlaybackCounterShard>>()
            val dailyCounters = linkedMapOf<String, List<SyncPlaybackCounterShard>>()
            rows.forEachIndexed { index, row ->
                trackCounters[row.identityKey] = listOf(shard)
                if (keepBuckets) trackCounters["removed|$index"] = listOf(shard)
                dailyCounters["0|${row.identityKey}"] = listOf(shard)
                dailyCounters["-86400000|${row.identityKey}"] = listOf(shard)
                dailyCounters["0|removed|$index"] = listOf(shard)
            }
            val counters = linkedMapOf<String, Any>(
                "dailyShardsByBucketKey" to dailyCounters,
                "trackShardsByIdentity" to trackCounters,
                "epochStartedAt" to 0
            )
            val gson = Gson()
            val sources = if (committed) {
                mapOf("playback_stats_meta.json" to gson.toJson(linkedMapOf(
                    "snapshot" to linkedMapOf(
                        "counterSnapshot" to counters,
                        "counterEpochStartedAt" to 0,
                        "dailyStats" to buckets,
                        "clearedAt" to 0,
                        "stats" to rows
                    ),
                    "clearedAt" to 0
                )))
            } else {
                mapOf("playback_stats.json" to gson.toJson(rows),
                    "playback_stats_daily.json" to gson.toJson(buckets),
                    "playback_stats_counters.json" to gson.toJson(counters))
            }
            sources.forEach { (name, content) -> File(directory, name).writeText(content) }
            val store = PlaybackStatsRoomStore(database)
            PlaybackStatsLegacyImporter(isolatedContext(base, directory), gson, store).migrate()
            assertTrue(store.readPrimaryState() != null)
            val dao = database.playbackStatsDao()
            assertEquals(rows.sortedBy { it.identityKey }, dao.getStats().map { it.toDomain() }.sortedBy { it.identityKey })
            assertEquals(buckets, dao.getBuckets().map { it.toDomain() })
            assertEquals(rowCount, dao.getCounterShards().size)
            val expectedDailyCounterCount = if (keepBuckets) rowCount else 0
            assertEquals(expectedDailyCounterCount, dao.getDailyCounterShards().size)
            for (row in rows) {
                assertEquals(shard, dao.getOwnedCounter(row.identityKey, "device", 0)?.toDomain())
                if (keepBuckets) assertEquals(shard, dao.getOwnedDailyCounter(0, row.identityKey, "device", 0)?.toDomain())
            }
            sources.forEach { (name, content) -> assertEquals(content, File(directory, name).readText()) }
        } finally { database.close(); directory.deleteRecursively() }
    }

    @Test
    fun streamedCounterWritesUseBoundedPageTransactionsInsteadOfOneTransactionPerShard() = runTest {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "stats-legacy-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val sequence = AtomicInteger()
        val trackWrites = ConcurrentHashMap<Int, AtomicInteger>()
        val dailyWrites = ConcurrentHashMap<Int, AtomicInteger>()
        val trackLookup = AtomicReference<Pair<String, List<Any?>>>()
        val dailyLookup = AtomicReference<Pair<String, List<Any?>>>()
        val database = Room.inMemoryDatabaseBuilder(base, NeriUserDataDatabase::class.java)
            .setQueryCallback(Dispatchers.Unconfined, RoomDatabase.QueryCallback { sql, args ->
                if (sql.startsWith("BEGIN")) sequence.incrementAndGet()
                if (sql.startsWith("WITH requested")) {
                    when {
                        sql.contains("CROSS JOIN playback_stat_snapshot_counter ") -> trackLookup.compareAndSet(null, sql to args.toList())
                        sql.contains("CROSS JOIN playback_stat_snapshot_daily_counter ") -> dailyLookup.compareAndSet(null, sql to args.toList())
                    }
                }
                val writes = when {
                    sql.startsWith("INSERT INTO `playback_stat_snapshot_counter`") -> trackWrites
                    sql.startsWith("INSERT INTO `playback_stat_snapshot_daily_counter`") -> dailyWrites
                    else -> null
                }
                writes?.computeIfAbsent(sequence.get()) { AtomicInteger() }?.incrementAndGet()
            }).build()
        try {
            val row = track()
            val shards = (0..599).map { SyncPlaybackCounterShard("actor-$it", 0, 0, 0, 100, 200) }
            writeLegacyInputs(directory, row, shards, committed = false)
            val store = PlaybackStatsRoomStore(database)
            PlaybackStatsLegacyImporter(isolatedContext(base, directory), Gson(), store).migrate()
            assertEquals(600, trackWrites.values.sumOf { it.get() })
            assertEquals(600, dailyWrites.values.sumOf { it.get() })
            assertEquals(listOf(88, 256, 256), trackWrites.values.map { it.get() }.sorted())
            assertEquals(listOf(88, 256, 256), dailyWrites.values.map { it.get() }.sorted())
            assertEquals(600, database.playbackStatsDao().getTrackCounters(row.identityKey).size)
            assertEquals(600, database.playbackStatsDao().getBucketCounters(0, row.identityKey).size)
            assertCounterLookupUsesFullPrimaryKey(database, checkNotNull(trackLookup.get()), 769,
                listOf("snapshot_id", "identity_key", "device_id", "epoch_started_at"))
            assertCounterLookupUsesFullPrimaryKey(database, checkNotNull(dailyLookup.get()), 513,
                listOf("snapshot_id", "day_start_at", "identity_key", "device_id", "epoch_started_at"))
        } finally { database.close(); directory.deleteRecursively() }
    }

    private fun assertCounterLookupUsesFullPrimaryKey(database: NeriUserDataDatabase,
        captured: Pair<String, List<Any?>>, argumentCount: Int, columns: List<String>) {
        assertEquals(argumentCount, captured.second.size)
        val sql = database.openHelper.readableDatabase
        val plan = sql.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN ${captured.first}", captured.second.toTypedArray())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }
        }
        val version = sql.query("SELECT sqlite_version()").use { cursor -> cursor.moveToFirst(); cursor.getString(0) }
        Log.d("PlaybackCounterLookup", "SQLite $version: ${plan.joinToString("; ")}")
        val primaryLookup = plan.singleOrNull { it.contains("SEARCH s ") }
        assertTrue(plan.toString(), primaryLookup != null)
        for (column in columns) assertTrue(plan.toString(), primaryLookup?.contains("$column=?") == true)
        assertFalse(plan.toString(), plan.any { it.contains("SCAN s") })
    }

    private suspend fun verifyDuplicateCountersAcrossPromotion(crossPage: Boolean) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "stats-legacy-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val name = "stats-legacy-repeat-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(base, NeriUserDataDatabase::class.java, name).build()
        var database = open()
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val larger = SyncPlaybackCounterShard("device", 0, 1_000, 5, 100, 300)
            val smaller = SyncPlaybackCounterShard("device", 0, 100, 1,
                if (crossPage) 50 else 150, if (crossPage) 400 else 200)
            val between = if (crossPage) (0..254).map { SyncPlaybackCounterShard("other-$it", 0, 0, 0, 100, 200) } else emptyList()
            val expectedShard = larger.copy(firstPlayedAt = if (crossPage) 50 else 100, lastPlayedAt = if (crossPage) 400 else 300)
            val expectedCounters = (listOf(expectedShard) + between).sortedBy { it.deviceId }
            val row = track().copy(playCount = 10, firstPlayedAt = 50, lastPlayedAt = 400,
                localFilePath = "/audio.flac", customName = "kept title")
            writeLegacyInputs(directory, row, listOf(larger) + between + smaller, committed = crossPage)
            val context = isolatedContext(base, directory)
            var store = PlaybackStatsRoomStore(database)
            var repository = PlaybackStatsRepository(context, store, scope) { "device" }
            assertTrue(repository.awaitInitialized())
            assertTrue(store.readPrimaryState() != null)
            assertEquals(expectedShard, database.playbackStatsDao().getOwnedCounter(row.identityKey, "device", 0)?.toDomain())
            assertEquals(expectedShard, database.playbackStatsDao().getOwnedDailyCounter(0, row.identityKey, "device", 0)?.toDomain())
            assertEquals(expectedCounters, database.playbackStatsDao().getTrackCounters(row.identityKey).map { it.toDomain() })
            assertEquals(expectedCounters, database.playbackStatsDao().getBucketCounters(0, row.identityKey).map { it.toDomain() })
            assertEquals(row, store.readTrack(row.identityKey))
            val revision = checkNotNull(store.readPrimaryState()).revision
            scope.cancel()
            database.close()
            File(directory, if (crossPage) "playback_stats_meta.json" else "playback_stats_counters.json").writeText("{broken-after-promotion")
            database = open()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            store = PlaybackStatsRoomStore(database)
            repository = PlaybackStatsRepository(context, store, scope) { "device" }
            assertTrue(repository.awaitInitialized())
            assertEquals(revision, store.readPrimaryState()?.revision)
            assertEquals(row, store.readTrack(row.identityKey))
            assertEquals(expectedShard, database.playbackStatsDao().getOwnedCounter(row.identityKey, "device", 0)?.toDomain())
            assertEquals(expectedShard, database.playbackStatsDao().getOwnedDailyCounter(0, row.identityKey, "device", 0)?.toDomain())
            assertEquals(expectedCounters, database.playbackStatsDao().getTrackCounters(row.identityKey).map { it.toDomain() })
            assertEquals(expectedCounters, database.playbackStatsDao().getBucketCounters(0, row.identityKey).map { it.toDomain() })
        } finally {
            scope.cancel()
            database.close()
            base.deleteDatabase(name)
            directory.deleteRecursively()
        }
    }

    private fun writeLegacyInputs(directory: File, row: TrackStat, shards: List<SyncPlaybackCounterShard>, committed: Boolean) {
        val bucket = PlaybackStatBucket(0, row.id, row.name, row.artist, row.album, row.albumId, row.coverUrl,
            row.durationMs, row.totalListenMs, row.playCount, row.lastPlayedAt, row.firstPlayedAt,
            row.mediaUri, row.localFilePath, row.localFileName, row.customName, row.customArtist, row.customCoverUrl, row.identityKey)
        val counters = mapOf("trackShardsByIdentity" to mapOf(row.identityKey to shards),
            "dailyShardsByBucketKey" to mapOf("0|${row.identityKey}" to shards))
        if (committed) {
            File(directory, "playback_stats_meta.json").writeText(Gson().toJson(mapOf("clearedAt" to 0,
                "snapshot" to mapOf("stats" to listOf(row), "dailyStats" to listOf(bucket),
                    "counterSnapshot" to counters, "counterEpochStartedAt" to 0, "clearedAt" to 0))))
        } else {
            File(directory, "playback_stats.json").writeText(Gson().toJson(listOf(row)))
            File(directory, "playback_stats_daily.json").writeText(Gson().toJson(listOf(bucket)))
            File(directory, "playback_stats_counters.json").writeText(Gson().toJson(counters))
        }
    }

    private fun isolatedContext(base: Context, directory: File) = object : ContextWrapper(base) {
        override fun getFilesDir(): File = directory
        override fun getApplicationContext(): Context = this
    }
    private fun track() = TrackStat(7, "song", "artist", "netease", 0, null, 180_000, 30_000, 1, 200, 100, null, null, null, null, null, null, "track|7")
}
