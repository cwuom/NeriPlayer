package moe.ouom.neriplayer.data.local.database.store.stats

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatProjection
import moe.ouom.neriplayer.data.sync.runtime.dataset.SYNC_PLAYBACK_PAGE_RECORDS

internal class PlaybackStatsWalReadCapture private constructor(private val reader: SQLiteDatabase,
    private val dispatcher: ExecutorCoroutineDispatcher, override val state: PlaybackStatsRoomState) : PlaybackStatsExportCapture {
    private val released = AtomicBoolean()

    override suspend fun export(context: Context, tracks: suspend (List<SyncTrackStat>) -> Unit,
        buckets: suspend (List<SyncPlaybackStatBucket>) -> Unit, projection: SyncPlaybackStatProjection?) = withContext(dispatcher) {
        check(!released.get()) { "Playback read capture is closed" }
        val boundProjection = projection ?: SyncPlaybackStatMapper.bind(context)
        var identity: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = trackPage(identity)
            if (page.isEmpty()) break
            val counters = trackCounters(page.map { it.identityKey })
            tracks(page.mapNotNull { stat ->
                if (boundProjection.shouldSync(stat)) SyncPlaybackStatMapper.fromTrackStat(stat, counters[stat.identityKey].orEmpty()) else null
            })
            identity = page.last().identityKey
        }
        var day: Long? = null
        identity = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = bucketPage(day, identity)
            if (page.isEmpty()) break
            val counters = dailyCounters(page)
            buckets(page.mapNotNull { bucket ->
                if (boundProjection.shouldSync(bucket)) SyncPlaybackStatMapper.fromPlaybackStatBucket(bucket,
                    counters[bucket.dayStartAt to bucket.identityKey].orEmpty()) else null
            })
            day = page.last().dayStartAt
            identity = page.last().identityKey
        }
        currentCoroutineContext().ensureActive()
        if (readState(reader) != state) throw IOException("Playback read capture changed while exporting")
    }

    override suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            withContext(NonCancellable + dispatcher) {
                try { reader.endTransaction() } finally { reader.close() }
            }
        } finally { dispatcher.close() }
    }

    private fun trackPage(after: String?): List<TrackStat> {
        val boundary = if (after == null) "" else " WHERE identity_key > ?"
        return reader.rawQuery("SELECT $TRACK_COLUMNS FROM playback_stat$boundary ORDER BY identity_key LIMIT $SYNC_PLAYBACK_PAGE_RECORDS",
            after?.let { arrayOf(it) }).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.track()) } }
    }

    private fun bucketPage(day: Long?, identity: String?): List<PlaybackStatBucket> {
        val boundary = if (day == null) "" else " WHERE (day_start_at, identity_key) > (?, ?)"
        return reader.rawQuery("SELECT day_start_at, $TRACK_COLUMNS FROM playback_stat_bucket$boundary " +
            "ORDER BY day_start_at, identity_key LIMIT $SYNC_PLAYBACK_PAGE_RECORDS",
            day?.let { arrayOf(it.toString(), checkNotNull(identity)) }).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.bucket()) }
        }
    }

    private fun trackCounters(keys: List<String>): Map<String, List<SyncPlaybackCounterShard>> {
        val rows = linkedMapOf<String, MutableList<SyncPlaybackCounterShard>>()
        reader.rawQuery("SELECT identity_key, $COUNTER_COLUMNS FROM playback_stat_counter_shard " +
            "WHERE identity_key IN (${keys.joinToString(",") { "?" }}) ORDER BY identity_key, device_id, epoch_started_at",
            keys.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) rows.getOrPut(cursor.getString(0)) { mutableListOf() }.add(cursor.counter(1))
        }
        return rows
    }

    private fun dailyCounters(page: List<PlaybackStatBucket>): Map<Pair<Long, String>, List<SyncPlaybackCounterShard>> {
        val rows = linkedMapOf<Pair<Long, String>, MutableList<SyncPlaybackCounterShard>>()
        for ((day, buckets) in page.groupBy { it.dayStartAt }) {
            reader.rawQuery("SELECT identity_key, $COUNTER_COLUMNS FROM playback_stat_daily_counter_shard " +
                "WHERE day_start_at = ? AND identity_key IN (${buckets.joinToString(",") { "?" }}) " +
                "ORDER BY identity_key, device_id, epoch_started_at", (listOf(day.toString()) + buckets.map { it.identityKey }).toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) rows.getOrPut(day to cursor.getString(0)) { mutableListOf() }.add(cursor.counter(1))
            }
        }
        return rows
    }

    companion object {
        suspend fun openIfSupported(store: PlaybackStatsRoomStore): PlaybackStatsWalReadCapture? {
            if (Build.VERSION.SDK_INT < 35) return null
            val primary = store.database.openHelper.readableDatabase
            val file = File(primary.path ?: return null)
            if (!file.isFile) return null
            val wal = primary.query("PRAGMA journal_mode").use { it.moveToFirst() && it.getString(0).equals("wal", ignoreCase = true) }
            if (!wal) return null
            return openWalFile(file)
        }

        @RequiresApi(35)
        private suspend fun openWalFile(file: File): PlaybackStatsWalReadCapture? {
            val dispatcher = Executors.newSingleThreadExecutor { action ->
                Thread(action, "PlaybackStatsReadCapture").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            var reader: SQLiteDatabase? = null
            var began = false
            var captured: PlaybackStatsWalReadCapture? = null
            var handedOff = false
            var failure: Throwable? = null
            try {
                withContext(dispatcher) {
                    val params = SQLiteDatabase.OpenParams.Builder().setOpenFlags(SQLiteDatabase.OPEN_READONLY)
                        .setErrorHandler { throw SQLiteDatabaseCorruptException("Playback read capture must preserve the primary database") }.build()
                    val database = SQLiteDatabase.openDatabase(file, params)
                    reader = database
                    check(database.isReadOnly) { "Playback capture requires a read-only handle" }
                    val wal = database.rawQuery("PRAGMA journal_mode", null).use { it.moveToFirst() && it.getString(0).equals("wal", ignoreCase = true) }
                    if (wal) {
                        database.beginTransactionReadOnly()
                        began = true
                        captured = PlaybackStatsWalReadCapture(database, dispatcher, readState(database))
                    }
                }
                if (captured != null) handedOff = true
                return captured
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                if (!handedOff) {
                    try { closeFailedOpen(reader, began, dispatcher) }
                    catch (cleanup: Throwable) {
                        val original = failure
                        if (original == null) throw cleanup else original.addSuppressed(cleanup)
                    }
                }
            }
        }

        private suspend fun closeFailedOpen(reader: SQLiteDatabase?, began: Boolean, dispatcher: ExecutorCoroutineDispatcher) {
            try {
                withContext(NonCancellable + dispatcher) {
                    try { if (began) reader?.endTransaction() } finally { reader?.close() }
                }
            } finally { dispatcher.close() }
        }

        private fun readState(database: SQLiteDatabase): PlaybackStatsRoomState {
            val keys = listOf(PlaybackStatsRoomStore.CUTOVER_STATE_METADATA_KEY, PlaybackStatsRoomStore.REVISION_METADATA_KEY,
                PlaybackStatsRoomStore.CLEARED_AT_METADATA_KEY, PlaybackStatsRoomStore.COUNTER_EPOCH_METADATA_KEY)
            val values = database.rawQuery("SELECT key, value FROM migration_metadata WHERE key IN (?, ?, ?, ?)", keys.toTypedArray()).use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
            }
            if (values[PlaybackStatsRoomStore.CUTOVER_STATE_METADATA_KEY] != PlaybackStatsRoomStore.ROOM_PRIMARY_STATE) {
                throw IOException("Playback read capture requires a trusted primary store")
            }
            fun number(key: String, fallback: Long = 0): Long {
                val value = values[key] ?: return fallback
                return value.toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException("Invalid playback read capture metadata: $key")
            }
            val pending = database.rawQuery("SELECT EXISTS(SELECT 1 FROM playback_stats_pending_delta)", null).use {
                if (!it.moveToFirst()) throw IOException("Missing playback read capture journal state")
                it.getInt(0) != 0
            }
            if (pending) throw IOException("Playback read capture contains pending journal entries")
            val clearedAt = number(PlaybackStatsRoomStore.CLEARED_AT_METADATA_KEY)
            return PlaybackStatsRoomState(number(PlaybackStatsRoomStore.REVISION_METADATA_KEY), clearedAt,
                number(PlaybackStatsRoomStore.COUNTER_EPOCH_METADATA_KEY, clearedAt))
        }

        private const val TRACK_COLUMNS = "identity_key, id, name, artist, album, album_id, cover_url, duration_ms, total_listen_ms, play_count, last_played_at, first_played_at, media_uri, local_file_path, local_file_name, custom_name, custom_artist, custom_cover_url"
        private const val COUNTER_COLUMNS = "device_id, epoch_started_at, total_listen_ms, play_count, first_played_at, last_played_at"

        private fun Cursor.track() = TrackStat(getLong(1), getString(2), getString(3), getString(4), getLong(5), nullableString(6),
            getLong(7), getLong(8), getInt(9), getLong(10), getLong(11), nullableString(12), nullableString(13),
            nullableString(14), nullableString(15), nullableString(16), nullableString(17), getString(0))

        private fun Cursor.bucket() = PlaybackStatBucket(getLong(0), getLong(2), getString(3), getString(4), getString(5), getLong(6),
            nullableString(7), getLong(8), getLong(9), getInt(10), getLong(11), getLong(12), nullableString(13), nullableString(14),
            nullableString(15), nullableString(16), nullableString(17), nullableString(18), getString(1))

        private fun Cursor.counter(offset: Int) = SyncPlaybackCounterShard(getString(offset), getLong(offset + 1), getLong(offset + 2),
            getInt(offset + 3), getLong(offset + 4), getLong(offset + 5))

        private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)
    }
}
