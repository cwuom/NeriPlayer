package moe.ouom.neriplayer.data.local.database.store.stats


import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.UUID
import java.io.IOException
import java.security.MessageDigest
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsSnapshotEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsPendingDeltaEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsEventReceiptEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.toEntity
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsPage
import moe.ouom.neriplayer.data.stats.PlaybackStatsSummary
import moe.ouom.neriplayer.data.stats.PlaybackStatsCursor
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatBucketEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatDailyCounterShardEntity
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatEntity
import moe.ouom.neriplayer.data.model.stats.PlaybackStatBucket
import moe.ouom.neriplayer.data.model.stats.PlaybackStatsSyncCounterSnapshot
import moe.ouom.neriplayer.data.model.stats.TrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard

internal data class PlaybackStatsRoomSnapshot(
    val stats: List<TrackStat>,
    val dailyStats: List<PlaybackStatBucket>,
    val counterSnapshot: PlaybackStatsSyncCounterSnapshot,
    val counterEpochStartedAt: Long,
    val clearedAt: Long
)

internal data class PlaybackStatsCaptureStamp(
    val state: PlaybackStatsRoomState,
    val journalSequence: Long,
    val databaseInstance: String
)

internal class PlaybackStatsRoomStore(
    internal val database: NeriUserDataDatabase
) {
    suspend fun readIfRoomPrimary(): PlaybackStatsRoomSnapshot? {
        return database.withTransaction {
            if (database.syncMetadataDao()
                .getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)
                ?.value != ROOM_PRIMARY_STATE
            ) {
                null
            } else {
                readSnapshot()
            }
        }
    }

    suspend fun importLegacyAndPromote(
        stats: List<TrackStat>,
        dailyStats: List<PlaybackStatBucket>,
        counterSnapshot: PlaybackStatsSyncCounterSnapshot,
        counterEpochStartedAt: Long,
        clearedAt: Long,
        now: Long = System.currentTimeMillis()
    ) {
        replaceAll(
            stats = stats,
            dailyStats = dailyStats,
            counterSnapshot = counterSnapshot,
            counterEpochStartedAt = counterEpochStartedAt,
            clearedAt = clearedAt,
            now = now
        )
    }

    suspend fun replaceAll(
        stats: List<TrackStat>,
        dailyStats: List<PlaybackStatBucket>,
        counterSnapshot: PlaybackStatsSyncCounterSnapshot,
        counterEpochStartedAt: Long,
        clearedAt: Long,
        now: Long = System.currentTimeMillis()
    ) {
        database.withTransaction {
            val dao = database.playbackStatsDao()
            dao.deleteAllDailyCounterShards()
            dao.deleteAllCounterShards()
            dao.deleteAllBuckets()
            dao.deleteAllStats()
            insertAll(
                stats = stats,
                dailyStats = dailyStats,
                counterSnapshot = counterSnapshot
            )
            markRoomPrimary(
                clearedAt = clearedAt,
                counterEpochStartedAt = counterEpochStartedAt,
                now = now
            )
        }
    }

    suspend fun writeIncremental(
        previousStats: List<TrackStat>,
        nextStats: List<TrackStat>,
        previousDailyStats: List<PlaybackStatBucket>,
        nextDailyStats: List<PlaybackStatBucket>,
        previousCounterSnapshot: PlaybackStatsSyncCounterSnapshot,
        counterSnapshot: PlaybackStatsSyncCounterSnapshot,
        counterEpochStartedAt: Long,
        clearedAt: Long,
        now: Long = System.currentTimeMillis()
    ) {
        val previousByKey = previousStats.associateBy(TrackStat::identityKey)
        val nextByKey = nextStats.associateBy(TrackStat::identityKey)
        val previousDailyByKey = previousDailyStats.groupBy(PlaybackStatBucket::identityKey)
        val nextDailyByKey = nextDailyStats.groupBy(PlaybackStatBucket::identityKey)
        val counterChangedKeys = changedCounterIdentityKeys(previousCounterSnapshot, counterSnapshot)
        val changedKeys = (previousByKey.keys + nextByKey.keys +
            previousDailyByKey.keys + nextDailyByKey.keys + counterChangedKeys)
            .filter { key ->
                previousByKey[key] != nextByKey[key] ||
                    previousDailyByKey[key] != nextDailyByKey[key] ||
                    key in counterChangedKeys
            }
            .toSet()

        database.withTransaction {
            val dao = database.playbackStatsDao()
            changedKeys.asSequence().writeBatches { chunk ->
                dao.deleteDailyCounterShards(chunk)
                dao.deleteCounterShards(chunk)
                dao.deleteBuckets(chunk)
                dao.deleteStats(chunk.filter { it !in nextByKey })
            }
            changedKeys
                .asSequence()
                .filter { it in nextByKey }
                .chunked(PLAYBACK_STATS_WRITE_BATCH_SIZE)
                .forEach { chunk ->
                    val chunkBuckets = chunk.asSequence().flatMap { nextDailyByKey[it].orEmpty().asSequence() }
                    chunk.asSequence().mapNotNull(nextByKey::get)
                        .map(TrackStat::toEntity)
                        .writeBatches(dao::upsertStats)
                    chunkBuckets.map(PlaybackStatBucket::toEntity)
                        .writeBatches(dao::upsertBuckets)
                    chunk.asSequence()
                        .flatMap { key ->
                            counterSnapshot.trackShards(key).asSequence().map { shard ->
                                shard.toTrackEntity(key)
                            }
                        }
                        .writeBatches(dao::upsertCounterShards)
                    chunkBuckets
                        .flatMap { bucket ->
                            counterSnapshot.dailyShards(
                                dayStartAt = bucket.dayStartAt,
                                identityKey = bucket.identityKey
                            ).asSequence().map { shard ->
                                shard.toDailyEntity(
                                    dayStartAt = bucket.dayStartAt,
                                    identityKey = bucket.identityKey
                                )
                            }
                        }
                        .writeBatches(dao::upsertDailyCounterShards)
                }
            markRoomPrimary(
                clearedAt = clearedAt,
                counterEpochStartedAt = counterEpochStartedAt,
                now = now
            )
        }
    }

    suspend fun commitLegacyFallback(writeSnapshot: () -> Boolean): Boolean {
        return database.withTransaction {
            if (!writeSnapshot()) return@withTransaction false
            currentCoroutineContext().ensureActive()
            markLegacyJsonPrimary()
            true
        }
    }

    suspend fun markLegacyJsonPrimary(now: Long = System.currentTimeMillis()) {
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(CUTOVER_STATE_METADATA_KEY, LEGACY_JSON_STATE, now)
        )
    }

    private suspend fun readSnapshot(): PlaybackStatsRoomSnapshot {
        val dao = database.playbackStatsDao()
        val stats = dao.getStats().map(PlaybackStatEntity::toDomain)
        val buckets = dao.getBuckets().map(PlaybackStatBucketEntity::toDomain)
        val trackShards = dao.getCounterShards()
            .groupBy(PlaybackStatCounterShardEntity::identityKey)
            .mapValues { (_, shards) ->
                shards.map(PlaybackStatCounterShardEntity::toDomain)
            }
        val dailyShards = dao.getDailyCounterShards()
            .groupBy { it.dayStartAt to it.identityKey }
            .mapKeys { (key, _) ->
                PlaybackStatsSyncCounterSnapshot.dailyCounterKey(
                    dayStartAt = key.first,
                    identityKey = key.second
                )
            }
            .mapValues { (_, shards) ->
                shards.map(PlaybackStatDailyCounterShardEntity::toDomain)
            }
        val clearedAt = database.syncMetadataDao()
            .getMigrationMetadata(CLEARED_AT_METADATA_KEY)
            ?.value
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 0L
        val counterEpochStartedAt = database.syncMetadataDao()
            .getMigrationMetadata(COUNTER_EPOCH_METADATA_KEY)
            ?.value
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: clearedAt
        return PlaybackStatsRoomSnapshot(
            stats = stats,
            dailyStats = buckets,
            counterSnapshot = PlaybackStatsSyncCounterSnapshot(
                trackShardsByIdentity = trackShards,
                dailyShardsByBucketKey = dailyShards
            ),
            counterEpochStartedAt = counterEpochStartedAt,
            clearedAt = clearedAt
        )
    }

    private suspend fun insertAll(
        stats: List<TrackStat>,
        dailyStats: List<PlaybackStatBucket>,
        counterSnapshot: PlaybackStatsSyncCounterSnapshot
    ) {
        val statKeys = stats.mapTo(mutableSetOf(), TrackStat::identityKey)
        val validBuckets = dailyStats.asSequence().filter { it.identityKey in statKeys }
        val dao = database.playbackStatsDao()
        stats.asSequence().map(TrackStat::toEntity).writeBatches(dao::upsertStats)
        validBuckets.map(PlaybackStatBucket::toEntity).writeBatches(dao::upsertBuckets)
        stats.asSequence()
            .flatMap { stat ->
                counterSnapshot.trackShards(stat.identityKey).asSequence().map { shard ->
                    shard.toTrackEntity(stat.identityKey)
                }
            }
            .writeBatches(dao::upsertCounterShards)
        validBuckets
            .flatMap { bucket ->
                counterSnapshot.dailyShards(
                    dayStartAt = bucket.dayStartAt,
                    identityKey = bucket.identityKey
                ).asSequence().map { shard ->
                    shard.toDailyEntity(
                        dayStartAt = bucket.dayStartAt,
                        identityKey = bucket.identityKey
                    )
                }
            }
            .writeBatches(dao::upsertDailyCounterShards)
    }

    private suspend fun markRoomPrimary(
        clearedAt: Long,
        counterEpochStartedAt: Long,
        now: Long
    ) {
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(CUTOVER_STATE_METADATA_KEY, ROOM_PRIMARY_STATE, now)
        )
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(IMPORT_SCHEMA_METADATA_KEY, "1", now)
        )
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(CLEARED_AT_METADATA_KEY, clearedAt.coerceAtLeast(0L).toString(), now)
        )
        database.syncMetadataDao().upsertMigrationMetadata(
            metadata(
                COUNTER_EPOCH_METADATA_KEY,
                counterEpochStartedAt.coerceAtLeast(0L).toString(),
                now
            )
        )
        advanceRevision(now)
    }

    private fun metadata(key: String, value: String, now: Long) =
        moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity(
            key = key,
            value = value,
            updatedAt = now
        )

    val revisionFlow: Flow<Long> = database.playbackStatsDao().observeRevision()
        .map { it?.toLongOrNull() ?: 0L }

    val clearedAtFlow: Flow<Long> = database.playbackStatsDao().observeClearedAt()
        .distinctUntilChanged().map { stored ->
            if (stored == null) 0L else stored.toLongOrNull()?.takeIf { it >= 0 }
                ?: throw IOException("Invalid playback statistics clear metadata")
        }

    suspend fun readPrimaryState(): PlaybackStatsRoomState? = database.withTransaction {
        when (val marker = database.syncMetadataDao().getMigrationMetadata(CUTOVER_STATE_METADATA_KEY)?.value) {
            ROOM_PRIMARY_STATE -> readState()
            null, LEGACY_JSON_STATE -> null
            else -> throw IOException("Unknown playback statistics primary marker: $marker")
        }
    }

    suspend fun readConfirmedCaptureStamp(): PlaybackStatsCaptureStamp = database.withTransaction {
        val state = readPrimaryState() ?: throw IOException("Playback capture requires the Room primary store")
        if (database.playbackStatsSnapshotDao().pendingDeltaCount() != 0L) {
            throw IOException("Playback capture contains pending journal entries")
        }
        val stored = database.syncMetadataDao().getMigrationMetadata(CAPTURE_DATABASE_INSTANCE_KEY)?.value
        val instance = stored ?: UUID.randomUUID().toString().also {
            database.syncMetadataDao().upsertMigrationMetadata(metadata(CAPTURE_DATABASE_INSTANCE_KEY, it, System.currentTimeMillis()))
        }
        if (runCatching { UUID.fromString(instance).toString() }.getOrNull() != instance) {
            throw IOException("Invalid playback capture database instance")
        }
        PlaybackStatsCaptureStamp(state, readLong(JOURNAL_SEQUENCE_METADATA_KEY), instance)
    }

    private suspend fun readState(): PlaybackStatsRoomState {
        val clearedAt = readLong(CLEARED_AT_METADATA_KEY)
        return PlaybackStatsRoomState(readLong(REVISION_METADATA_KEY), clearedAt, readLong(COUNTER_EPOCH_METADATA_KEY, clearedAt))
    }

    private suspend fun readLong(key: String, default: Long = 0): Long {
        val stored = database.syncMetadataDao().getMigrationMetadata(key)?.value ?: return default
        return stored.toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException("Invalid playback statistics metadata: $key")
    }

    private suspend fun advanceRevision(now: Long = System.currentTimeMillis()) {
        val previous = readLong(REVISION_METADATA_KEY)
        check(previous < Long.MAX_VALUE) { "Playback statistics revision exhausted" }
        database.syncMetadataDao().upsertMigrationMetadata(metadata(REVISION_METADATA_KEY, (previous + 1).toString(), now))
    }

    suspend fun beginLegacyImport(): PlaybackStatsSnapshotEntity {
        val id = UUID.randomUUID().toString()
        return createOwnedSnapshot(id) {
            val state = readState()
            PlaybackStatsSnapshotEntity(id, state.revision, 0, 0, System.currentTimeMillis(), false, snapshotOwnerProcessId)
        }
    }

    suspend fun readTrack(identityKey: String): TrackStat? = database.playbackStatsDao().getTrack(identityKey)?.toDomain()

    suspend fun checkCapturedRevision(expectedRevision: Long): Boolean = database.withTransaction {
        val state = checkNotNull(readPrimaryState()) { "Playback statistics are unavailable" }
        state.revision == expectedRevision && database.playbackStatsSnapshotDao().pendingDeltaCount() == 0L
    }

    suspend fun freezeSnapshot(): PlaybackStatsSnapshotEntity {
        val id = UUID.randomUUID().toString()
        return createOwnedSnapshot(id, copyPrimary = true) {
            val state = checkNotNull(readPrimaryState()) { "Playback statistics are not initialized" }
            check(database.playbackStatsSnapshotDao().pendingDeltaCount() == 0L) { "Playback statistics have pending deltas" }
            PlaybackStatsSnapshotEntity(id, state.revision, state.clearedAt, state.counterEpochStartedAt, System.currentTimeMillis(), true, snapshotOwnerProcessId)
        }
    }

    suspend fun beginDiffSnapshot(clearedAt: Long): PlaybackStatsSnapshotEntity {
        val id = UUID.randomUUID().toString()
        return createOwnedSnapshot(id) {
            val state = checkNotNull(readPrimaryState()) { "Playback statistics are not initialized" }
            check(database.playbackStatsSnapshotDao().pendingDeltaCount() == 0L) { "Playback statistics have pending deltas" }
            val barrier = maxOf(state.clearedAt, clearedAt)
            PlaybackStatsSnapshotEntity(id, state.revision, barrier, maxOf(state.counterEpochStartedAt, barrier),
                System.currentTimeMillis(), false, snapshotOwnerProcessId, isDiff = true)
        }
    }

    private suspend fun createOwnedSnapshot(id: String, copyPrimary: Boolean = false, create: suspend () -> PlaybackStatsSnapshotEntity): PlaybackStatsSnapshotEntity {
        try {
            return database.withTransaction {
                val snapshot = create()
                val dao = database.playbackStatsSnapshotDao()
                dao.upsertSnapshot(snapshot)
                if (copyPrimary) {
                    dao.freezeTrack(id)
                    dao.freezeBucket(id)
                    dao.freezeCounter(id)
                    dao.freezeDailyCounter(id)
                }
                snapshot
            }
        } catch (error: Exception) {
            // 事务已经提交但返回前取消时，仍知道自己创建的副本身份
            withContext(NonCancellable) {
                try { releaseSnapshot(id) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            }
            throw error
        }
    }

    suspend fun cleanupAbandonedSnapshots() {
        while (true) {
            val ids = database.playbackStatsSnapshotDao().abandonedSnapshotIds(snapshotOwnerProcessId, 32)
            if (ids.isEmpty()) return
            ids.forEach { releaseSnapshot(it) }
        }
    }

    suspend fun readFrozenTracks(id: String, after: String?, limit: Int): List<TrackStat> {
        require(limit in 1..256)
        return database.playbackStatsSnapshotDao().trackPage(id, after, limit).map { it.stat.toEntity().toDomain() }
    }

    suspend fun commitFrozenSnapshot(id: String, expectedRevision: Long): Boolean = database.withTransaction {
        val snapshot = checkNotNull(database.playbackStatsSnapshotDao().getSnapshot(id)) { "Playback snapshot no longer exists" }
        check(!snapshot.isDiff) { "A playback diff cannot replace the complete primary store" }
        check(snapshot.sealed) { "Playback snapshot is incomplete" }
        if (readState().revision != expectedRevision || database.playbackStatsSnapshotDao().pendingDeltaCount() != 0L) return@withTransaction false
        val dao = database.playbackStatsDao()
        dao.deleteAllDailyCounterShards()
        dao.deleteAllCounterShards()
        dao.deleteAllBuckets()
        dao.deleteAllStats()
        val staged = database.playbackStatsSnapshotDao()
        staged.publishTrack(id)
        staged.publishBucket(id)
        staged.publishCounter(id)
        staged.publishDailyCounter(id)
        markRoomPrimary(snapshot.clearedAt, snapshot.counterEpochStartedAt, System.currentTimeMillis())
        true
    }

    suspend fun commitDiffSnapshot(id: String, expectedRevision: Long): Boolean = database.withTransaction {
        val staged = database.playbackStatsSnapshotDao()
        val snapshot = checkNotNull(staged.getSnapshot(id)) { "Playback diff no longer exists" }
        check(snapshot.isDiff && snapshot.sealed) { "Playback diff is incomplete" }
        val current = checkNotNull(readPrimaryState()) { "Playback statistics are unavailable" }
        if (current.revision != expectedRevision || staged.pendingDeltaCount() != 0L) return@withTransaction false
        if (snapshot.clearedAt == current.clearedAt && snapshot.counterEpochStartedAt == current.counterEpochStartedAt && !staged.hasDiffRows(id)) {
            return@withTransaction true
        }
        staged.applyBucketTombstones(id)
        staged.applyTrackTombstones(id)
        staged.deleteReplacedDailyCounters(id)
        staged.deleteReplacedCounters(id)
        staged.updateDiffTracks(id)
        staged.insertDiffTracks(id)
        staged.updateDiffBuckets(id)
        staged.insertDiffBuckets(id)
        staged.publishCounter(id)
        staged.publishDailyCounter(id)
        markRoomPrimary(snapshot.clearedAt, snapshot.counterEpochStartedAt, System.currentTimeMillis())
        true
    }

    suspend fun releaseSnapshot(id: String) = database.withTransaction {
        val dao = database.playbackStatsSnapshotDao()
        dao.deleteBucketTombstones(id)
        dao.deleteTrackTombstones(id)
        dao.deleteDailyCounter(id)
        dao.deleteCounter(id)
        dao.deleteBucket(id)
        dao.deleteTrack(id)
        dao.deleteSnapshot(id)
    }

    suspend fun enqueueDelta(eventId: String, trackJson: String, listenedMs: Long, playCountIncrement: Int?, playedAt: Long,
        deviceId: String, observedClearedAt: Long? = null, observeCurrentClear: Boolean = false): Boolean = database.withTransaction {
        require(observedClearedAt == null || observedClearedAt >= 0)
        val payloadHash = MessageDigest.getInstance("SHA-256").digest((trackJson + "|" + listenedMs + "|" + playCountIncrement + "|" + playedAt + "|" + observedClearedAt).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val receipt = database.playbackStatsSnapshotDao().receipt(eventId)
        if (receipt != null) {
            if (receipt.payloadHash != payloadHash) throw IOException("Playback event identity reused with different content")
            return@withTransaction false
        }
        val state = checkNotNull(readPrimaryState()) { "Playback statistics are unavailable" }
        val sequence = readLong(JOURNAL_SEQUENCE_METADATA_KEY)
        check(sequence < Long.MAX_VALUE) { "Playback journal sequence exhausted" }
        // 新事件以已观察代次判断先后；没有代次的旧导入仍按原始时间保守处理
        val eventEpoch = observedClearedAt ?: state.clearedAt.takeIf { observeCurrentClear }
        val accepted = if (eventEpoch == null) playedAt >= state.clearedAt else eventEpoch >= state.clearedAt
        val delta = PlaybackStatsPendingDeltaEntity(eventId, sequence + 1, trackJson, listenedMs, playCountIncrement,
            playedAt, maxOf(state.clearedAt, eventEpoch ?: 0), deviceId)
        database.playbackStatsSnapshotDao().insertReceipt(PlaybackStatsEventReceiptEntity(eventId, playedAt, payloadHash))
        if (accepted) database.playbackStatsSnapshotDao().insertPendingDelta(delta)
        database.syncMetadataDao().upsertMigrationMetadata(metadata(JOURNAL_SEQUENCE_METADATA_KEY, delta.sequence.toString(), playedAt))
        pruneEventReceipts()
        true
    }

    private suspend fun pruneEventReceipts() {
        database.playbackStatsSnapshotDao().pruneCompletedReceipts(RETAINED_EVENT_RECEIPTS)
    }

    suspend fun pendingDeltas(): List<PlaybackStatsPendingDeltaEntity> = database.playbackStatsSnapshotDao().pendingDeltas(32)
    suspend fun pendingDeltaCount(): Long = database.playbackStatsSnapshotDao().pendingDeltaCount()

    suspend fun applyDelta(delta: PlaybackStatsPendingDeltaEntity, identityKey: String, update: (PlaybackStatsDeltaRows) -> PlaybackStatsDeltaRows) = database.withTransaction {
        if (database.playbackStatsSnapshotDao().pendingDelta(delta.id) == null) return@withTransaction
        val dao = database.playbackStatsDao()
        val state = readState()
        if (delta.epochStartedAt < state.clearedAt) {
            database.playbackStatsSnapshotDao().deletePendingDelta(delta.id)
            pruneEventReceipts()
            return@withTransaction
        }
        val day = moe.ouom.neriplayer.data.model.stats.playbackStatsDayStartAt(delta.playedAt)
        val current = PlaybackStatsDeltaRows(
            dao.getTrack(identityKey), dao.getBucket(day, identityKey),
            dao.getOwnedCounter(identityKey, delta.deviceId, delta.epochStartedAt),
            dao.getOwnedDailyCounter(day, identityKey, delta.deviceId, delta.epochStartedAt)
        )
        val rows = update(current)
        dao.upsertStats(listOf(checkNotNull(rows.track)))
        dao.upsertBuckets(listOf(checkNotNull(rows.bucket)))
        dao.upsertCounterShards(listOf(checkNotNull(rows.counter)))
        dao.upsertDailyCounterShards(listOf(checkNotNull(rows.dailyCounter)))
        database.playbackStatsSnapshotDao().deletePendingDelta(delta.id)
        pruneEventReceipts()
        advanceRevision(delta.playedAt)
    }

    suspend fun clear(now: Long): PlaybackStatsRoomState = database.withTransaction {
        val previous = readState()
        check(previous.clearedAt < Long.MAX_VALUE) { "Playback clear clock exhausted" }
        val barrier = maxOf(now, previous.clearedAt + 1)
        val dao = database.playbackStatsDao()
        database.playbackStatsSnapshotDao().deleteAllPendingDeltas()
        pruneEventReceipts()
        dao.deleteAllStats()
        markRoomPrimary(barrier, barrier, now)
        readState()
    }

    suspend fun removeTracks(keys: Set<String>) = database.withTransaction {
        keys.asSequence().chunked(500).forEach { database.playbackStatsDao().deleteStats(it) }
        if (keys.isNotEmpty()) advanceRevision()
    }

    suspend fun readSummary(query: PlaybackStatsQuery): PlaybackStatsSummary {
        return database.withTransaction {
            val hasBuckets = database.playbackStatsDao().hasBuckets()
            val hasStats = database.playbackStatsDao().hasStats()
            val sql = PlaybackStatsSqlQuery(query, hasBuckets, database.playbackStatsDao().hasLegacyStats())
            val totals = database.playbackStatsDao().querySummary(sql.summary())
            PlaybackStatsSummary(totals.trackCount, totals.totalPlayCount, totals.totalListenMs, sql.usesLegacyBreakdown && hasStats, hasStats)
        }
    }

    suspend fun readPage(query: PlaybackStatsQuery, after: PlaybackStatsCursor?, limit: Int, before: Boolean = false): PlaybackStatsPage {
        require(limit in 1..256)
        return database.withTransaction {
            val sql = PlaybackStatsSqlQuery(query, database.playbackStatsDao().hasBuckets())
            val rows = database.playbackStatsDao().queryStats(sql.page(after, limit + 1, before)).map(PlaybackStatEntity::toDomain)
            val page = if (before) rows.take(limit).asReversed() else rows.take(limit)
            val hasEarlier = if (before) rows.size > limit else after != null
            val hasLater = if (before) after != null else rows.size > limit
            PlaybackStatsPage(page, if (hasLater) page.lastOrNull()?.let(sql::cursor) else null, if (hasEarlier) page.firstOrNull()?.let(sql::cursor) else null)
        }
    }

    companion object {
        // 播放器按顺序重试最早未确认事件，后续事件不能越过它；持久待处理增量另外保留全部回执
        const val RETAINED_EVENT_RECEIPTS = 256
        const val REVISION_METADATA_KEY = "playback_stats_revision"
        const val JOURNAL_SEQUENCE_METADATA_KEY = "playback_stats_journal_sequence"
        const val CUTOVER_STATE_METADATA_KEY = "playback_stats_cutover_state"
        const val IMPORT_SCHEMA_METADATA_KEY = "playback_stats_import_schema"
        const val CLEARED_AT_METADATA_KEY = "playback_stats_cleared_at"
        const val COUNTER_EPOCH_METADATA_KEY = "playback_stats_counter_epoch"
        const val CAPTURE_DATABASE_INSTANCE_KEY = "playback_stats_capture_database_instance"
        const val ROOM_PRIMARY_STATE = "room_primary"
        const val LEGACY_JSON_STATE = "legacy_json"
    }
}

private const val PLAYBACK_STATS_WRITE_BATCH_SIZE = 500

private suspend fun <T> Sequence<T>.writeBatches(write: suspend (List<T>) -> Unit) {
    // 按数据库行分批，单曲的日桶或因果分片也不能一次展开到内存
    chunked(PLAYBACK_STATS_WRITE_BATCH_SIZE).forEach { batch ->
        currentCoroutineContext().ensureActive()
        write(batch)
    }
}

private fun identityKeyFromDailyCounterKey(key: String): String {
    return key.substringAfter('|', missingDelimiterValue = key)
}

private fun changedCounterIdentityKeys(
    previous: PlaybackStatsSyncCounterSnapshot,
    next: PlaybackStatsSyncCounterSnapshot
): Set<String> = linkedSetOf<String>().apply {
    (previous.trackShardsByIdentity.keys.asSequence() + next.trackShardsByIdentity.keys.asSequence()).forEach { key ->
        if (previous.trackShards(key) != next.trackShards(key)) add(key)
    }
    // 直接比较每个桶键，避免为每首曲目重新过滤全部日桶
    (previous.dailyShardsByBucketKey.keys.asSequence() + next.dailyShardsByBucketKey.keys.asSequence()).forEach { key ->
        if (previous.dailyShardsByBucketKey[key] != next.dailyShardsByBucketKey[key]) add(identityKeyFromDailyCounterKey(key))
    }
}

internal fun PlaybackStatEntity.toDomain(): TrackStat {
    return TrackStat(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        coverUrl = coverUrl,
        durationMs = durationMs,
        totalListenMs = totalListenMs,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        firstPlayedAt = firstPlayedAt,
        mediaUri = mediaUri,
        localFilePath = localFilePath,
        localFileName = localFileName,
        customName = customName,
        customArtist = customArtist,
        customCoverUrl = customCoverUrl,
        identityKey = identityKey
    )
}

internal fun PlaybackStatBucketEntity.toDomain(): PlaybackStatBucket {
    return PlaybackStatBucket(
        dayStartAt = dayStartAt,
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        coverUrl = coverUrl,
        durationMs = durationMs,
        totalListenMs = totalListenMs,
        playCount = playCount,
        lastPlayedAt = lastPlayedAt,
        firstPlayedAt = firstPlayedAt,
        mediaUri = mediaUri,
        localFilePath = localFilePath,
        localFileName = localFileName,
        customName = customName,
        customArtist = customArtist,
        customCoverUrl = customCoverUrl,
        identityKey = identityKey
    )
}

internal fun PlaybackStatCounterShardEntity.toDomain(): SyncPlaybackCounterShard {
    return SyncPlaybackCounterShard(
        deviceId = deviceId,
        epochStartedAt = epochStartedAt,
        totalListenMs = totalListenMs,
        playCount = playCount,
        firstPlayedAt = firstPlayedAt,
        lastPlayedAt = lastPlayedAt
    )
}

internal fun PlaybackStatDailyCounterShardEntity.toDomain(): SyncPlaybackCounterShard {
    return SyncPlaybackCounterShard(
        deviceId = deviceId,
        epochStartedAt = epochStartedAt,
        totalListenMs = totalListenMs,
        playCount = playCount,
        firstPlayedAt = firstPlayedAt,
        lastPlayedAt = lastPlayedAt
    )
}

internal fun SyncPlaybackCounterShard.toTrackEntity(
    identityKey: String
): PlaybackStatCounterShardEntity {
    return PlaybackStatCounterShardEntity(
        identityKey = identityKey,
        deviceId = deviceId,
        epochStartedAt = epochStartedAt,
        totalListenMs = totalListenMs,
        playCount = playCount,
        firstPlayedAt = firstPlayedAt,
        lastPlayedAt = lastPlayedAt
    )
}

internal fun SyncPlaybackCounterShard.toDailyEntity(
    dayStartAt: Long,
    identityKey: String
): PlaybackStatDailyCounterShardEntity {
    return PlaybackStatDailyCounterShardEntity(
        dayStartAt = dayStartAt,
        identityKey = identityKey,
        deviceId = deviceId,
        epochStartedAt = epochStartedAt,
        totalListenMs = totalListenMs,
        playCount = playCount,
        firstPlayedAt = firstPlayedAt,
        lastPlayedAt = lastPlayedAt
    )
}

private val snapshotOwnerProcessId = UUID.randomUUID().toString()
