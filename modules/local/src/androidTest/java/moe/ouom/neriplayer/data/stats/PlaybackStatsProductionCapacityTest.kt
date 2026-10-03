package moe.ouom.neriplayer.data.stats

import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsPeriod
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncSystemPlaylist
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRepository
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.merge.host.SyncMergeHost
import moe.ouom.neriplayer.data.sync.runtime.SyncBackend
import moe.ouom.neriplayer.data.sync.runtime.SyncLocalDataStore
import moe.ouom.neriplayer.data.sync.runtime.SyncSession
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDatasetRemoteSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStatsProductionCapacityTest {
    @Test
    fun roomSessionArchiveRoundTripAndOneChangeUseBoundedPages() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("runIndustrialSyncScale") == "true")
        val count = args.getString("industrialSyncTracks")?.toInt() ?: 1_000_000
        require(count in 1..10_000_000)
        val buckets = args.getString("industrialSyncBuckets", "true") == "true"
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "sync-production-scale-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, name).apply { check(mkdir()) }
        val database = Room.databaseBuilder(context, NeriUserDataDatabase::class.java, name).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var ownedRepository: PlaybackStatsRepository? = null
        try {
            val room = PlaybackStatsRoomStore(database)
            room.importLegacyAndPromote(emptyList(), emptyList(), PlaybackStatsSyncCounterSnapshot(), 0, 0)
            measure("seed", count) { seed(database, count, buckets) }
            val repository = PlaybackStatsRepository(context, room, scope) { "scale-device" }.also { ownedRepository = it }
            assertTrue(repository.awaitInitialized())
            for (period in if (buckets) listOf(PlaybackStatsPeriod.ALL, PlaybackStatsPeriod.MONTH, PlaybackStatsPeriod.YEAR) else listOf(PlaybackStatsPeriod.ALL)) {
                measure("summary_$period", count) {
                    val summary = repository.readSummary(PlaybackStatsQuery(period = period))
                    assertEquals(count.toLong(), summary.trackCount)
                    assertEquals(count.toLong(), summary.totalPlayCount)
                    assertEquals(count.toLong() * 30_000, summary.totalListenMs)
                }
                for (sort in PlaybackStatsSort.entries) {
                    measure("page_${period}_$sort", count) {
                        val query = PlaybackStatsQuery(period = period, sort = sort)
                        val first = repository.readPage(query, pageSize = 100)
                        assertEquals(minOf(count, 100), first.tracks.size)
                        if (count > 100) {
                            val next = repository.readPage(query, first.nextCursor, 100)
                            assertEquals(minOf(count - 100, 100), next.tracks.size)
                            assertTrue(first.tracks.none { previous -> next.tracks.any { it.identityKey == previous.identityKey } })
                        }
                    }
                }
            }
            val store = FileSyncPlaybackDatasetStore(File(directory, "datasets"))
            val local = RoomLocal(repository, store)
            val backend = DiskBackend(context, File(directory, "remote"))
            val session = SyncSession(local, SyncDataMerger(Messages), store, "unchanged", "initial",
                { IllegalStateException("busy") }, deferredMessage = "pending")
            val revision = room.readPrimaryState()!!.revision
            measure("initial_session", count) { session.execute { backend }.getOrThrow() }
            assertEquals(1, backend.uploads)
            assertEquals(revision, room.readPrimaryState()!!.revision)
            assertTrue(backend.maxObjectBytes <= 2 * 1024 * 1024)
            assertEquals(count.toLong(), rowCount(database, "playback_stat"))
            assertEquals(if (buckets) count.toLong() else 0L, rowCount(database, "playback_stat_bucket"))

            measure("unchanged_session", count) { session.execute { backend }.getOrThrow() }
            assertEquals(1, backend.uploads)
            assertEquals(revision, room.readPrimaryState()!!.revision)
            assertEquals(0, local.changedApplies)

            measure("remote_one_record", count) { backend.changeFirstTrack() }
            val before = backend.wireBytes
            val downloadedBefore = backend.downloadWireBytes
            measure("one_record_session", count) { session.execute { backend }.getOrThrow() }
            assertEquals(30_007L, repository.getStatForTrack("scale|00000000")!!.totalListenMs)
            assertEquals(2, repository.getStatForTrack("scale|00000000")!!.playCount)
            assertEquals("local title", repository.getStatForTrack("scale|00000000")!!.customName)
            assertEquals(count.toLong(), rowCount(database, "playback_stat"))
            assertEquals(1, local.changedApplies)
            assertEquals(revision + 1, room.readPrimaryState()!!.revision)
            val changedBytes = backend.wireBytes - before + backend.downloadWireBytes - downloadedBefore
            assertTrue(changedBytes < 3 * 1024 * 1024)
            measure("changed_then_unchanged", count) { session.execute { backend }.getOrThrow() }
            assertEquals(revision + 1, room.readPrimaryState()!!.revision)
            report(JSONObject().put("tracks", count).put("buckets", buckets)
                .put("wireBytes", backend.wireBytes).put("downloadWireBytes", backend.downloadWireBytes)
                .put("oneRecordTransferBytes", changedBytes).put("maxObjectBytes", backend.maxObjectBytes)
                .put("changedApplies", local.changedApplies).put("maxHeapBytes", Runtime.getRuntime().maxMemory()))
        } finally {
            try { ownedRepository?.releaseSyncCaptureCache() }
            finally {
                scope.cancel()
                database.close()
                check(context.deleteDatabase(name))
                check(directory.deleteRecursively())
            }
        }
    }

    private suspend fun seed(database: NeriUserDataDatabase, count: Int, buckets: Boolean) = database.withTransaction {
        val sql = database.openHelper.writableDatabase
        val day = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        for (offset in 0 until count step 1_000_000) {
            sql.execSQL("WITH RECURSIVE d(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM d WHERE n<999), " +
                "r(n) AS (SELECT $offset+a.n+1000*b.n FROM d a CROSS JOIN d b WHERE $offset+a.n+1000*b.n<$count) " +
                "INSERT INTO playback_stat SELECT printf('scale|%08d',n), n, 'song '||n, 'artist', 'netease', 0, NULL, " +
                "180000, 30000, 1, ${day + 200}, ${day + 100}, NULL, NULL, NULL, CASE WHEN n=0 THEN 'local title' ELSE NULL END, NULL, NULL FROM r")
        }
        sql.execSQL("INSERT INTO playback_stat_counter_shard SELECT identity_key,'scale-device',0,total_listen_ms,play_count,first_played_at,last_played_at FROM playback_stat")
        if (buckets) {
            sql.execSQL("INSERT INTO playback_stat_bucket SELECT $day,identity_key,id,name,artist,album,album_id,cover_url,duration_ms,total_listen_ms,play_count,last_played_at,first_played_at,media_uri,local_file_path,local_file_name,custom_name,custom_artist,custom_cover_url FROM playback_stat")
            sql.execSQL("INSERT INTO playback_stat_daily_counter_shard SELECT $day,identity_key,'scale-device',0,total_listen_ms,play_count,first_played_at,last_played_at FROM playback_stat")
        }
    }

    private fun rowCount(database: NeriUserDataDatabase, table: String): Long =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { check(it.moveToFirst()); it.getLong(0) }

    private suspend fun measure(phase: String, count: Int, block: suspend () -> Unit) {
        val started = SystemClock.elapsedRealtime()
        report(JSONObject().put("phaseStarted", phase).put("tracks", count))
        block()
        val runtime = Runtime.getRuntime()
        report(JSONObject().put("phase", phase).put("tracks", count).put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("heapUsedBytes", runtime.totalMemory() - runtime.freeMemory()))
    }

    private fun report(value: JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\nSYNC_CAPACITY $value\n") })
    }

    private class RoomLocal(private val repository: PlaybackStatsRepository, private val store: FileSyncPlaybackDatasetStore) : SyncLocalDataStore {
        var changedApplies = 0
        override suspend fun awaitInitialized(): Boolean {
            if (!repository.awaitInitialized()) return false
            repository.flushPendingWrites()
            return true
        }
        override fun mutationVersion() = 0L
        override suspend fun snapshot(): SyncDataset {
            val captured = repository.borrowSyncCapture(store)
            return SyncDataset(SyncData(deviceId = "scale-device", playbackStatsClearedAt = captured.state.clearedAt),
                captured.playback, captured.state.revision)
        }
        override suspend fun apply(dataset: SyncDataset, remoteChanged: Boolean, expectedMutationVersion: Long): Boolean {
            val revision = checkNotNull(dataset.capturedPlaybackRevision)
            if (dataset.playbackMatchesCaptured) return repository.checkCapturedRevision(revision)
            val result = repository.applySyncSnapshot(dataset.playback, dataset.data.playbackStatsClearedAt, revision)
            if (result) changedApplies++
            return result
        }
    }

    private class DiskBackend(private val context: Context, private val directory: File) : SyncBackend<Int> {
        private val projection = SyncPlaybackStatMapper.bind(context)
        private val writer = SyncArchiveRepository(File(directory, "writer"))
        private val reader = SyncArchiveRepository(File(directory, "reader"))
        private val store = FileSyncPlaybackDatasetStore(File(directory, "datasets"))
        private val remote = File(directory, "objects").apply { check(mkdirs()) }
        private val known = mutableSetOf<String>()
        private var manifest: ByteArray? = null
        private var version = 0
        private var acknowledgedVersion = 0
        private var time = 0L
        var uploads = 0
        var wireBytes = 0L
        var downloadWireBytes = 0L
        var maxObjectBytes = 0
        override val isFirstSync get() = time == 0L
        override val lastSyncTime get() = time
        override val mutationConflictMessage = "mutation"
        override suspend fun fetch(): Result<SyncDatasetRemoteSnapshot<Int>> {
            val content = manifest ?: return Result.success(SyncDatasetRemoteSnapshot(null, version))
            downloadWireBytes += content.size
            return reader.readDataset(content, store,
                projection::sanitize, projection::sanitize,
                { path -> runCatching { File(remote, path).readBytes().also { downloadWireBytes += it.size } } })
                .map { SyncDatasetRemoteSnapshot(it, version) }
        }
        override suspend fun refetch(version: Int) = fetch()
        override suspend fun upload(data: SyncDataset, version: Int): Result<Int> {
            check(version == this.version)
            writer.prepareCancellable(data).use { prepared ->
                for (item in prepared.objects(known)) {
                    val file = File(remote, item.path)
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    file.writeBytes(item.content)
                    maxObjectBytes = maxOf(maxObjectBytes, item.content.size)
                    wireBytes += item.content.size
                }
                known.addAll(prepared.paths)
                manifest = prepared.content
                wireBytes += prepared.content.size
            }
            uploads++
            return Result.success(++this.version)
        }
        suspend fun changeFirstTrack() {
            fetch().getOrThrow().dataset!!.use { original ->
                store.newSink().use { sink ->
                    original.playback.openTracks().use { cursor ->
                        while (true) {
                            val page = cursor.nextPage()
                            if (page.isEmpty()) break
                            require(page.size <= SYNC_PLAYBACK_PAGE_RECORDS)
                            sink.appendTracks(page.map { track ->
                                if (track.identityKey != "scale|00000000") track else track.copy(
                                    totalListenMs = 30_007, playCount = 2, lastPlayedAt = track.lastPlayedAt + 1,
                                    counterShards = track.counterShards + SyncPlaybackCounterShard("remote", 0, 7, 1, track.lastPlayedAt + 1, track.lastPlayedAt + 1)
                                )
                            })
                        }
                    }
                    original.playback.openBuckets().use { cursor ->
                        while (true) { val page = cursor.nextPage(); if (page.isEmpty()) break; sink.appendBuckets(page) }
                    }
                    SyncDataset(original.data, sink.seal()).use { upload(it, version).getOrThrow() }
                }
            }
        }
        override fun remoteChanged(version: Int) = version != acknowledgedVersion
        override fun isConflict(error: Throwable?) = false
        override fun saveRemoteVersion(version: Int) { acknowledgedVersion = version }
        override fun saveSyncTime(timestamp: Long) { time = timestamp }
        override fun saveCompletedSyncTime(timestamp: Long) = true
        override fun scheduleFollowUp() = error("Unexpected stale local snapshot")
        override fun onFailure(error: Throwable) = Unit
    }

    private object Messages : SyncMergeHost {
        override val favoritesPlaylistId = -1001L
        override val mergeSuccessMessage = "merged"
        override val initialUploadMessage = "initial"
        override fun systemPlaylist(id: Long, name: String): SyncSystemPlaylist? = null
        override fun localRenameMessage(name: String) = name
        override fun remoteRenameMessage(name: String) = name
    }
}
