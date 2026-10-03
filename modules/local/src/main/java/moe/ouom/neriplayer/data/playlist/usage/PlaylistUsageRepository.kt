package moe.ouom.neriplayer.data.playlist.usage

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.playlist.usage/PlaylistUsageRepository
 * Updated: 2026/3/23
 */

import moe.ouom.neriplayer.data.model.stats.UsageEntry
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.PlaylistUsageRoomStore
import moe.ouom.neriplayer.data.local.playlist.artist.buildLocalArtistSummaries
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.displayCoverUrl
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.merge.stats.SyncPlaylistUsageStatsMergePolicy
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageDeletion
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageDeletionPolicy
import moe.ouom.neriplayer.data.model.sync.normalizedSyncCausalTokens
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.sync.mapping.sanitizeCoverUrlForSync
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.data.local.database.maintenance.LegacyJsonCleanupRequests
import moe.ouom.neriplayer.common.io.writeTextAtomically
import moe.ouom.neriplayer.common.locale.LanguageManager
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.CoroutineContext

internal fun playlistUsageKey(source: String, id: Long, subtype: String?): String = buildString {
    append(source)
    append(':')
    append(id)
    subtype?.trim()?.takeIf { it.isNotEmpty() }?.let {
        append(':')
        append(it)
    }
}

internal fun UsageEntry.usageKey(): String = playlistUsageKey(source, id, subtype)

internal fun UsageEntry.hasPlayableTracks(): Boolean = trackCount > 0

private val usageEntryComparator = Comparator<UsageEntry> { left, right ->
    when {
        left.lastOpened != right.lastOpened -> right.lastOpened.compareTo(left.lastOpened)
        left.openCount != right.openCount -> right.openCount.compareTo(left.openCount)
        else -> left.id.compareTo(right.id)
    }
}

private const val LOCAL_USAGE_COVER_CANDIDATE_LIMIT = 24

internal fun normalizeUsageEntries(list: List<UsageEntry>): List<UsageEntry> {
    return list
        .filterNotNull()
        .map { entry ->
            entry.copy(
                counterShards = entry.counterShards.orEmpty().filterNotNull(),
                observedDeletionTokens = entry.observedDeletionTokens.normalizedSyncCausalTokens()
            )
        }
        .filter(UsageEntry::hasPlayableTracks)
        .groupBy(UsageEntry::usageKey)
        .mapNotNull { (_, duplicates) -> mergeDuplicateUsageEntries(duplicates) }
        .sortedWith(usageEntryComparator)
}

private fun mergeDuplicateUsageEntries(entries: List<UsageEntry>): UsageEntry? {
    val knownTokens = entries.flatMap { it.observedDeletionTokens }.normalizedSyncCausalTokens()
    val eligible = entries.filter { SyncPlaylistUsageDeletionPolicy.observes(it.observedDeletionTokens, knownTokens) }
    val latest = eligible.sortedWith(usageEntryComparator).firstOrNull() ?: return null
    val allLegacyCounters = eligible.all { entry ->
        entry.counterBaseOpenCount <= 0L && entry.counterShards.orEmpty().isEmpty()
    }
    if (!allLegacyCounters) {
        val merged = SyncPlaylistUsageStatsMergePolicy.mergePlaylistUsageStats(
            local = eligible.map(UsageEntry::toSyncPlaylistUsageStat),
            remote = emptyList()
        ).single().toUsageEntry()
        return merged.copy(
            name = latest.name,
            picUrl = latest.picUrl,
            trackCount = latest.trackCount,
            fid = latest.fid,
            mid = latest.mid,
            browseId = latest.browseId,
            playlistId = latest.playlistId,
            subtype = latest.subtype,
            subtitle = latest.subtitle
        )
    }
    val mergedOpenCount = eligible.sumOf(UsageEntry::openCount)
        .coerceAtLeast(latest.openCount)
    return latest.copy(
        openCount = mergedOpenCount,
        observedDeletionTokens = knownTokens,
        firstOpened = eligible.fold(0L) { earliest, entry ->
            minPositiveTimestamp(earliest, entry.firstOpened)
        }
    )
}

class PlaylistUsageRepository internal constructor(
    context: Context,
    private val roomStore: PlaylistUsageRoomStore? = null
) {
    private val appContext = context.applicationContext

    companion object {
        const val SOURCE_LOCAL = "local"
        const val SOURCE_LOCAL_ARTIST = "localArtist"

        @Volatile
        private var instance: PlaylistUsageRepository? = null

        fun getInstance(context: Context): PlaylistUsageRepository {
            return instance ?: synchronized(this) {
                val appContext = context.applicationContext
                instance ?: PlaylistUsageRepository(
                    context = appContext,
                    roomStore = PlaylistUsageRoomStore(
                        database = NeriUserDataDatabase.getInstance(appContext)
                    )
                ).also {
                    instance = it
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val file: File by lazy { File(appContext.filesDir, "playlist_usage.json") }
    private val syncStorage by lazy { SecureTokenStorage(appContext) }
    private val fallbackCounterDeviceId = "playlist-usage-${UUID.randomUUID()}"
    private val mutationLock = Any()
    private val persistenceMutex = Mutex()
    private var persistenceGeneration = 0L
    private val manuallyRemovedUsageKeys = mutableMapOf<String, Long>()
    private var manuallyRemovedUsageKeysLoaded = false
    @Volatile
    private var roomStorageEnabled = roomStore != null
    @Volatile
    private var initialized = false
    private var initialLoadFailure: Exception? = null
    private val initialEntries = load()
    private val _flow = MutableStateFlow(initialEntries)
    @Volatile
    private var persistedEntries = initialEntries
    @Volatile
    private var baselineTrusted = initialized
    private var pendingUiChanges = false
    private var pendingWrite: UsageWriteSnapshot? = null
    @Volatile
    private var persistenceInProgress = false
    @Volatile
    private var writingTrustedSnapshot = false
    val frequentPlaylistsFlow: StateFlow<List<UsageEntry>> = _flow

    private fun load(): List<UsageEntry> {
        return runBlocking(Dispatchers.IO) {
            tryLoadEntries()?.also { initialized = true }.orEmpty()
        }
    }

    private suspend fun loadTrustedEntries(): List<UsageEntry> {
        if (roomStorageEnabled && roomStore != null) {
            val activeRoomStore = roomStore
            // Room 主存不可读时不能使用旧 JSON 覆盖尚未恢复的数据
            val roomEntries = activeRoomStore.readIfRoomPrimary()
            if (roomEntries != null) {
                LegacyJsonCleanupRequests.schedule(appContext, "playlist-usage-room-load")
                return normalizeUsageEntries(roomEntries)
            }
        }

        val normalized = loadLegacyEntries()
        if (roomStorageEnabled && roomStore != null) {
            val activeRoomStore = roomStore
            runCatching {
                activeRoomStore.importLegacyAndPromote(normalized)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                roomStorageEnabled = false
                NPLogger.e(
                    "PlaylistUsageRepo",
                    "Failed to promote playlist usage JSON to Room",
                    error
                )
            }
            LegacyJsonCleanupRequests.schedule(appContext, "playlist-usage-import")
        }
        return normalized
    }

    private fun loadLegacyEntries(): List<UsageEntry> {
        if (!file.exists()) return emptyList()
        val entries = gson.fromJson<List<UsageEntry>>(
            file.readText(), object : TypeToken<List<UsageEntry>>() {}.type
        ) ?: throw IOException("Playlist usage JSON has no valid list")
        return normalizeUsageEntries(entries)
    }

    private suspend fun tryLoadEntries(): List<UsageEntry>? = try {
        loadTrustedEntries().also { initialLoadFailure = null }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        initialLoadFailure = error
        NPLogger.e("PlaylistUsageRepo", "Usage unavailable; preserving storage for retry", error)
        null
    }

    private fun ensureInitializedLocked(
        context: CoroutineContext = Dispatchers.IO,
        forPersistence: Boolean = false
    ): Boolean {
        // 活动保存期间允许 UI 更新，保存结束后未知基线必须先经串行恢复
        if (initialized) return baselineTrusted || writingTrustedSnapshot || forPersistence
        val loaded = runBlocking(context + Dispatchers.IO) { tryLoadEntries() } ?: return false
        _flow.value = loaded
        persistedEntries = loaded
        baselineTrusted = true
        pendingUiChanges = false
        initialized = true
        return true
    }

    private suspend fun recoverBaselineLocked(): Boolean = try {
        if (!baselineTrusted) {
            // 保存锁内重读实际主存，已提交后通知取消的事务不能继续沿用旧基线
            val roomEntries = roomStore?.readIfRoomPrimary()
            val actual = roomEntries?.let(::normalizeUsageEntries) ?: loadLegacyEntries()
            currentCoroutineContext().ensureActive()
            synchronized(mutationLock) {
                val write = pendingWrite
                if (write != null) {
                    // 取消前可能已提交，只把尚未进入实际主存的 UI 变化重放一次
                    val previous = if (actual == write.next) write.previousVisible else write.previousPersisted
                    _flow.value = rebaseUiEntriesLocked(previous, actual)
                    pendingUiChanges = _flow.value != actual
                }
                persistedEntries = actual
                roomStorageEnabled = roomEntries != null
                baselineTrusted = true
                pendingWrite = null
            }
        }
        synchronized(mutationLock) {
            if (!pendingUiChanges) _flow.value = persistedEntries
        }
        true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        initialLoadFailure = error
        NPLogger.e("PlaylistUsageRepo", "Usage authority unavailable; refusing an uncertain baseline", error)
        false
    }

    private fun publishUiEntries(entries: List<UsageEntry>) {
        pendingUiChanges = true
        _flow.value = entries
    }

    private fun confirmPersistedEntries(entries: List<UsageEntry>) {
        synchronized(mutationLock) {
            val write = pendingWrite
            if (write != null && (persistenceGeneration != write.generation || _flow.value != write.previousVisible)) {
                _flow.value = rebaseUiEntriesLocked(write.previousVisible, entries)
            }
            persistedEntries = entries
            baselineTrusted = true
            pendingWrite = null
            if (_flow.value == entries) pendingUiChanges = false
        }
    }

    suspend fun awaitInitialized(): Boolean = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val generation = synchronized(mutationLock) {
            if (!ensureInitializedLocked(context, forPersistence = true)) return@withContext false
            // 占用新的保存代次，等待中的旧 UI 保存不能在本轮确认之后迟到覆盖
            persistenceGeneration += 1L
            persistenceGeneration
        }
        flushPendingWrites(generation)
    }

    private suspend fun flushPendingWrites(generation: Long): Boolean = try {
        withPersistenceLock {
            if (!isLatestGeneration(generation) || !recoverBaselineLocked()) return@withPersistenceLock false
            val snapshot = synchronized(mutationLock) {
                if (generation != persistenceGeneration) return@withPersistenceLock false
                val visible = visibleEntriesLocked(_flow.value)
                if (visible != _flow.value) publishUiEntries(visible)
                visible
            }
            persistEntriesChecked(snapshot, generation)
            synchronized(mutationLock) {
                generation == persistenceGeneration && baselineTrusted && _flow.value == persistedEntries
            }
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        NPLogger.e("PlaylistUsageRepo", "Usage changes remain pending until persistence recovers", error)
        false
    }

    private fun saveAsync(list: List<UsageEntry>) {
        val generation = synchronized(mutationLock) {
            if (!initialized) return
            // 较早操作迟到的保存不能覆盖已经发布的新快照
            if (list != _flow.value) return
            persistenceGeneration += 1L
            persistenceGeneration
        }
        scope.launch {
            try {
                persistSnapshotChecked(generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                NPLogger.e("PlaylistUsageRepo", "Failed to persist playlist usage", error)
            }
        }
    }

    private suspend fun persistSnapshotChecked(generation: Long): Boolean {
        return withPersistenceLock {
            if (!isLatestGeneration(generation)) return@withPersistenceLock false
            if (!recoverBaselineLocked()) throw IOException("Cannot recover playlist usage authority", initialLoadFailure)
            if (!isLatestGeneration(generation)) return@withPersistenceLock false
            val visible = synchronized(mutationLock) {
                // 前一笔提交可能已重基 UI，排队时捕获的列表不能覆盖这份新基线
                val current = _flow.value
                val filtered = visibleEntriesLocked(current)
                if (generation == persistenceGeneration && filtered != current) publishUiEntries(filtered)
                filtered
            }
            persistEntriesChecked(visible, generation)
            true
        }
    }

    private fun isLatestGeneration(generation: Long): Boolean = synchronized(mutationLock) {
        generation == persistenceGeneration
    }

    private suspend fun <T> withPersistenceLock(action: suspend () -> T): T = persistenceMutex.withLock {
        persistenceInProgress = true
        try {
            action()
        } finally {
            persistenceInProgress = false
        }
    }

    private suspend fun persistEntriesChecked(list: List<UsageEntry>, generation: Long, previousVisible: List<UsageEntry> = list) {
        currentCoroutineContext().ensureActive()
        if (!initialized || !baselineTrusted) throw IOException("Cannot persist unknown playlist usage", initialLoadFailure)
        if (list == persistedEntries) {
            confirmPersistedEntries(list)
            return
        }
        writingTrustedSnapshot = true
        try {
            synchronized(mutationLock) {
                pendingWrite = UsageWriteSnapshot(persistedEntries, previousVisible, list, generation)
                baselineTrusted = false
            }
            persistEntriesToStorage(list)
        } finally {
            writingTrustedSnapshot = false
        }
    }

    private suspend fun persistEntriesToStorage(list: List<UsageEntry>) {
        if (roomStorageEnabled && roomStore != null) {
            try {
                roomStore.writeIncremental(previous = persistedEntries, next = list)
                confirmPersistedEntries(list)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                roomStorageEnabled = false
                NPLogger.e("PlaylistUsageRepo", "Failed to write Room playlist usage; falling back to JSON", error)
            }
        }
        currentCoroutineContext().ensureActive()
        file.writeTextAtomically(gson.toJson(list))
        currentCoroutineContext().ensureActive()
        roomStore?.markLegacyJsonPrimary()
        confirmPersistedEntries(list)
    }

    fun recordOpen(
        id: Long,
        name: String,
        picUrl: String?,
        trackCount: Int,
        fid: Long = 0,
        mid: Long = 0,
        source: String,
        browseId: String? = null,
        playlistId: String? = null,
        subtype: String? = null,
        subtitle: String? = null,
        now: Long = System.currentTimeMillis(),
        updateLastOpened: Boolean = true
    ) {
        if (trackCount <= 0) {
            removeEntryIfPresent(id, source, subtype)
            return
        }

        val out = synchronized(mutationLock) {
            if (!ensureInitializedLocked()) return
            val deviceId = syncCounterDeviceId()
            val data = _flow.value.toMutableList()
            val targetKey = playlistUsageKey(source, id, subtype)
            var observed = emptyList<SyncCausalToken>()
            if (!tryUiTombstoneOperation {
                    observed = deletionBarriersLocked().firstOrNull { it.playlistKey == targetKey }?.deletionTokens.orEmpty()
                    clearManualRemovalLocked(targetKey)
                }) return
            val idx = data.indexOfFirst { it.usageKey() == targetKey }
            val previous = data.getOrNull(idx)?.takeIf { SyncPlaylistUsageDeletionPolicy.observes(it.observedDeletionTokens, observed) }
            val updated = if (previous != null) {
                previous.copy(
                    name = name,
                    picUrl = picUrl?.takeIf { it.isNotBlank() } ?: data[idx].picUrl,
                    trackCount = trackCount,
                    fid = fid,
                    mid = mid,
                    browseId = browseId,
                    playlistId = playlistId,
                    subtype = subtype,
                    subtitle = subtitle ?: previous.subtitle,
                    observedDeletionTokens = (previous.observedDeletionTokens + observed).normalizedSyncCausalTokens()
                )
            } else {
                UsageEntry(
                    id = id,
                    name = name,
                    picUrl = picUrl,
                    trackCount = trackCount,
                    source = source,
                    lastOpened = now,
                    openCount = 0,
                    firstOpened = now,
                    fid = fid,
                    mid = mid,
                    browseId = browseId,
                    playlistId = playlistId,
                    subtype = subtype,
                    subtitle = subtitle,
                    observedDeletionTokens = observed
                )
            }
            val counted = updated.recordOpen(
                deviceId = deviceId,
                openedAt = now,
                updateLastOpened = updateLastOpened
            )
            if (idx >= 0) {
                data[idx] = counted
            } else {
                data.add(counted)
            }
            normalizeUsageEntries(data).also(::publishUiEntries)
        }
        saveAsync(out)
        triggerSync()
    }

    fun syncStats(): List<SyncPlaylistUsageStat> {
        return syncStatsAndDeletions().first
    }

    fun syncStatsAndDeletions(): Pair<List<SyncPlaylistUsageStat>, List<SyncPlaylistUsageDeletion>> {
        return synchronized(mutationLock) {
            if (!initialized || !baselineTrusted) throw IOException("Playlist usage has no trusted snapshot", initialLoadFailure)
            if (persistenceInProgress || _flow.value != persistedEntries) {
                throw IOException("Playlist usage still has uncommitted changes")
            }
            val barriers = deletionBarriersLocked()
            SyncPlaylistUsageStatsMergePolicy.mergePlaylistUsageStats(
                _flow.value.map(UsageEntry::toSyncPlaylistUsageStat), emptyList(), barriers
            ) to barriers
        }
    }

    fun applyMergedStats(stats: List<SyncPlaylistUsageStat>) {
        val out = synchronized(mutationLock) {
            if (!ensureInitializedLocked()) return
            var barriers = emptyList<SyncPlaylistUsageDeletion>()
            if (!tryUiTombstoneOperation { barriers = deletionBarriersLocked() }) return
            mergeStatsLocked(stats, barriers).also(::publishUiEntries)
        }
        saveAsync(out)
    }

    suspend fun applyMergedStatsAndPersist(
        stats: List<SyncPlaylistUsageStat>,
        deletions: List<SyncPlaylistUsageDeletion> = emptyList()
    ) {
        val context = currentCoroutineContext()
        val generation = synchronized(mutationLock) {
            if (!ensureInitializedLocked(context, forPersistence = true)) throw IOException("Cannot apply unknown playlist usage", initialLoadFailure)
            persistenceGeneration += 1L
            persistenceGeneration
        }
        withPersistenceLock {
            if (!isLatestGeneration(generation)) throw IOException("Playlist usage changed before sync persistence")
            if (!recoverBaselineLocked()) throw IOException("Cannot recover playlist usage authority", initialLoadFailure)
            val (previous, next) = synchronized(mutationLock) {
                if (generation != persistenceGeneration) throw IOException("Playlist usage changed before sync persistence")
                if (deletions.isNotEmpty()) syncStorage.mergePlaylistUsageDeletionBarriers(deletions)
                manualRemovalTimestampsLocked(forceReload = true)
                val previous = _flow.value
                val next = mergeStatsLocked(stats)
                previous to next
            }
            persistEntriesChecked(next, generation, previous)
            currentCoroutineContext().ensureActive()
            synchronized(mutationLock) {
                if (generation != persistenceGeneration || _flow.value != previous) {
                    throw IOException("Playlist usage changed during sync persistence")
                }
                _flow.value = next
                pendingUiChanges = false
            }
        }
    }

    private fun mergeStatsLocked(
        stats: List<SyncPlaylistUsageStat>,
        barriers: List<SyncPlaylistUsageDeletion> = deletionBarriersLocked()
    ): List<UsageEntry> {
        val current = _flow.value
        val merged = SyncPlaylistUsageStatsMergePolicy.mergePlaylistUsageStats(
            current.map(UsageEntry::toSyncPlaylistUsageStat), stats, barriers
        )
        val previousByKey = current.associateBy(UsageEntry::usageKey)
        val mergedEntries = merged.map(SyncPlaylistUsageStat::toUsageEntry).map { entry ->
            val previousCover = previousByKey[entry.usageKey()]?.picUrl?.takeIf { it.isNotBlank() }
            val stableCover = entry.picUrl?.takeIf { it.isNotBlank() } ?: previousCover
            if (stableCover == entry.picUrl) entry else entry.copy(picUrl = stableCover)
        }
        return normalizeUsageEntries(mergedEntries)
    }

    private fun rebaseUiEntriesLocked(previous: List<UsageEntry>, committed: List<UsageEntry>): List<UsageEntry> {
        val previousByKey = previous.associateBy(UsageEntry::usageKey)
        val currentByKey = _flow.value.associateBy(UsageEntry::usageKey)
        val committedByKey = committed.associateBy(UsageEntry::usageKey)
        val removedKeys = previousByKey.keys - currentByKey.keys
        val merged = SyncPlaylistUsageStatsMergePolicy.mergePlaylistUsageStats(
            committed.map(UsageEntry::toSyncPlaylistUsageStat), _flow.value.map(UsageEntry::toSyncPlaylistUsageStat), deletionBarriersLocked()
        )
        return normalizeUsageEntries(merged.filterNot { it.playlistKey in removedKeys }.map { stat ->
            val restored = stat.toUsageEntry()
            val confirmed = committedByKey[restored.usageKey()]
            val current = currentByKey[restored.usageKey()]
            val metadata = confirmed ?: current ?: restored
            val stable = metadata.copy(openCount = restored.openCount, lastOpened = restored.lastOpened,
                firstOpened = restored.firstOpened, counterBaseOpenCount = restored.counterBaseOpenCount,
                counterShards = restored.counterShards, observedDeletionTokens = restored.observedDeletionTokens)
            if (current == null) stable else {
                val before = previousByKey[current.usageKey()]
                current.rebaseMetadata(before, rebaseOpenDelta(before, current, confirmed, stable))
            }
        })
    }

    private fun rebaseOpenDelta(previous: UsageEntry?, current: UsageEntry, committed: UsageEntry?, merged: UsageEntry): UsageEntry {
        if (current.counterShards.orEmpty().isEmpty()) return merged
        val deviceId = syncCounterDeviceId()
        val currentCount = current.ownedOpenCount(deviceId)
        val delta = (currentCount - previous.ownedOpenCount(deviceId)).coerceAtLeast(0)
        if (delta == 0) return merged
        // 合并可能带回更大的本机分片，写入期间新发生的打开仍须在它之后累加
        val count = maxOf(currentCount, committed.ownedOpenCount(deviceId).toLong().saturatingAdd(delta.toLong()).toBoundedInt())
        return merged.copy(counterShards = merged.counterShards.orEmpty().map { shard ->
            if (shard.deviceId == deviceId && shard.epochStartedAt == 0L) shard.copy(playCount = count) else shard
        })
    }

    /** 刷新歌单信息；详情加载出有效曲目时可补齐打开记录 */
    fun updateInfo(
        id: Long,
        name: String,
        picUrl: String?,
        trackCount: Int,
        fid: Long = 0,
        mid: Long = 0,
        source: String,
        browseId: String? = null,
        playlistId: String? = null,
        subtype: String? = null,
        subtitle: String? = null,
        now: Long = System.currentTimeMillis()
    ) {
        if (trackCount <= 0) {
            removeEntryIfPresent(id, source, subtype)
            return
        }

        val out = synchronized(mutationLock) {
            if (!ensureInitializedLocked()) return
            val deviceId = syncCounterDeviceId()
            val data = _flow.value.toMutableList()
            val targetKey = playlistUsageKey(source, id, subtype)
            var observed = emptyList<SyncCausalToken>()
            if (!tryUiTombstoneOperation {
                    observed = deletionBarriersLocked().firstOrNull { it.playlistKey == targetKey }?.deletionTokens.orEmpty()
                    clearManualRemovalLocked(targetKey)
                }) return
            val idx = data.indexOfFirst { it.usageKey() == targetKey }
            if (idx >= 0 && SyncPlaylistUsageDeletionPolicy.observes(data[idx].observedDeletionTokens, observed)) {
                val old = data[idx]
                data[idx] = old.copy(
                    name = name,
                    picUrl = picUrl?.takeIf { it.isNotBlank() } ?: old.picUrl,
                    trackCount = trackCount,
                    fid = fid,
                    mid = mid,
                    browseId = browseId ?: old.browseId,
                    playlistId = playlistId ?: old.playlistId,
                    subtype = subtype ?: old.subtype,
                    subtitle = subtitle ?: old.subtitle
                )
            } else {
                if (idx >= 0) data.removeAt(idx)
                data += UsageEntry(
                    id = id,
                    name = name,
                    picUrl = picUrl,
                    trackCount = trackCount,
                    source = source,
                    lastOpened = now,
                    openCount = 0,
                    firstOpened = now,
                    fid = fid,
                    mid = mid,
                    browseId = browseId,
                    playlistId = playlistId,
                    subtype = subtype,
                    subtitle = subtitle,
                    observedDeletionTokens = observed
                ).recordOpen(deviceId = deviceId, openedAt = now)
            }

            normalizeUsageEntries(data).also(::publishUiEntries)
        }
        saveAsync(out)
        triggerSync()
    }

    /**
     * 同步本地歌单卡片信息
     * 已删除的歌单会被移除，名称/封面/歌曲数变化会刷新展示
     */
    fun syncLocalEntries(
        playlists: List<LocalPlaylist>,
        localFilesCoverCandidates: List<SongItem> = emptyList(),
        resolveLocalMetadataFallback: Boolean = true
    ) {
        val out = synchronized(mutationLock) {
            if (!ensureInitializedLocked()) return
            val current = _flow.value
            val localEntryIds = current.asSequence()
                .filter { entry -> entry.source == SOURCE_LOCAL }
                .mapTo(LinkedHashSet()) { entry -> entry.id }
            if (localEntryIds.isEmpty()) {
                return@synchronized null
            }

            val localizedContext = LanguageManager.applyLanguage(appContext)
            val localPlaylistLookup = buildLocalPlaylistUsageLookup(
                playlists = playlists,
                context = localizedContext,
                requestedPlaylistIds = localEntryIds
            )
            var changed = false
            val updated = current.mapNotNull { entry ->
                if (entry.source != SOURCE_LOCAL) return@mapNotNull entry

                val playlist = localPlaylistLookup[entry.id] ?: run {
                    changed = true
                    return@mapNotNull null
                }

                val refreshedName = SystemLocalPlaylists.resolve(
                    playlistId = playlist.id,
                    playlistName = playlist.name,
                    context = localizedContext
                )?.currentName ?: playlist.name
                val isLocalFilesPlaylist = LocalFilesPlaylist.isSystemPlaylist(
                    playlist,
                    localizedContext
                )
                val coverCandidateLimit = if (resolveLocalMetadataFallback) {
                    Int.MAX_VALUE
                } else {
                    LOCAL_USAGE_COVER_CANDIDATE_LIMIT
                }
                val coverCandidates = if (isLocalFilesPlaylist) {
                    if (coverCandidateLimit == Int.MAX_VALUE) {
                        localFilesCoverCandidates
                    } else {
                        localFilesCoverCandidates.take(coverCandidateLimit)
                    }
                } else {
                    emptyList()
                }
                val coverPlaylist = playlist.withCoverCandidateLimit(coverCandidateLimit)
                val refreshedPicUrl = if (isLocalFilesPlaylist && playlist.songs.isEmpty()) {
                    null
                } else {
                    val immediateCover = coverPlaylist.displayCoverUrl(coverCandidates)
                        ?.takeIf { it.isNotBlank() }
                    val resolvedFallback = if (resolveLocalMetadataFallback) {
                            coverPlaylist.displayCoverUrl(
                                context = localizedContext,
                                resolveLocalMetadataFallback = true,
                                additionalCoverCandidates = coverCandidates
                            )?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                    resolveRefreshedLocalUsageCover(
                        immediateCover = immediateCover,
                        resolvedFallback = resolvedFallback,
                        cachedCover = entry.picUrl,
                        resolveLocalMetadataFallback = resolveLocalMetadataFallback
                    )
                }
                val refreshedTrackCount = playlist.songs.size
                if (
                    entry.name == refreshedName &&
                    entry.picUrl == refreshedPicUrl &&
                    entry.trackCount == refreshedTrackCount
                ) {
                    entry
                } else {
                    changed = true
                    entry.copy(
                        name = refreshedName,
                        picUrl = refreshedPicUrl,
                        trackCount = refreshedTrackCount
                    )
                }
            }

            if (!changed) return@synchronized null

            normalizeUsageEntries(updated).also(::publishUiEntries)
        }
        out?.let(::saveAsync)
    }

    /** 同步本地歌手虚拟歌单卡片信息 */
    fun syncLocalArtistEntries(
        playlists: List<LocalPlaylist>,
        resolveLocalMetadataFallback: Boolean = true
    ) {
        val out = synchronized(mutationLock) {
            if (!ensureInitializedLocked()) return
            val current = _flow.value
            if (current.none { it.source == SOURCE_LOCAL_ARTIST }) {
                return@synchronized null
            }

            val localizedContext = LanguageManager.applyLanguage(appContext)
            val artistsById = buildLocalArtistSummaries(playlists, localizedContext)
                .associateBy { artist -> artist.id }
            var changed = false
            val updated = current.mapNotNull { entry ->
                if (entry.source != SOURCE_LOCAL_ARTIST) return@mapNotNull entry

                val artist = artistsById[entry.id] ?: run {
                    changed = true
                    return@mapNotNull null
                }

                val coverArtist = if (resolveLocalMetadataFallback) {
                    artist
                } else {
                    artist.copy(
                        songs = artist.songs.take(LOCAL_USAGE_COVER_CANDIDATE_LIMIT)
                    )
                }
                val immediateCover = coverArtist.displayCoverUrl()?.takeIf { it.isNotBlank() }
                val resolvedFallback = if (resolveLocalMetadataFallback && immediateCover == null) {
                    coverArtist.displayCoverUrl(
                        context = localizedContext,
                        resolveLocalMetadataFallback = true
                    )?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                val refreshedPicUrl = resolveRefreshedLocalUsageCover(
                    immediateCover = immediateCover,
                    resolvedFallback = resolvedFallback,
                    cachedCover = entry.picUrl,
                    resolveLocalMetadataFallback = resolveLocalMetadataFallback
                )
                val refreshedTrackCount = artist.songs.size
                if (
                    entry.name == artist.name &&
                    entry.picUrl == refreshedPicUrl &&
                    entry.trackCount == refreshedTrackCount
                ) {
                    entry
                } else {
                    changed = true
                    entry.copy(
                        name = artist.name,
                        picUrl = refreshedPicUrl,
                        trackCount = refreshedTrackCount
                    )
                }
            }

            if (!changed) return@synchronized null

            normalizeUsageEntries(updated).also(::publishUiEntries)
        }
        out?.let(::saveAsync)
    }

    /** 从继续播放列表中移除指定项 */
    fun removeEntry(id: Long, source: String, subtype: String? = null) {
        val targetKey = playlistUsageKey(source, id, subtype)
        val out = synchronized(mutationLock) {
            if (!ensureInitializedLocked()) return
            if (!tryUiTombstoneOperation { rememberManualRemovalLocked(targetKey) }) return
            val data = _flow.value.toMutableList()
            val removed = data.removeAll { it.usageKey() == targetKey }
            if (!removed) {
                return@synchronized null
            }
            normalizeUsageEntries(data).also(::publishUiEntries)
        }
        out?.let(::saveAsync)
        triggerSync()
    }

    private fun removeEntryIfPresent(id: Long, source: String, subtype: String? = null) {
        val out = synchronized(mutationLock) {
            if (!ensureInitializedLocked()) return
            val data = _flow.value.toMutableList()
            val targetKey = playlistUsageKey(source, id, subtype)
            val removed = data.removeAll { it.usageKey() == targetKey }
            if (!removed) return
            normalizeUsageEntries(data).also(::publishUiEntries)
        }
        saveAsync(out)
    }

    private fun syncCounterDeviceId(): String {
        return runCatching { syncStorage.getOrCreateDeviceId() }
            .getOrDefault(fallbackCounterDeviceId)
    }

    private fun manualRemovalTimestampsLocked(forceReload: Boolean = false): MutableMap<String, Long> {
        if (forceReload || !manuallyRemovedUsageKeysLoaded) {
            val persisted = try {
                syncStorage.getPlaylistUsageDeletionsConfirmed()
            } catch (error: Exception) {
                manuallyRemovedUsageKeysLoaded = false
                throw error
            }
            manuallyRemovedUsageKeys.clear()
            manuallyRemovedUsageKeys.putAll(persisted)
            manuallyRemovedUsageKeysLoaded = true
        }
        return manuallyRemovedUsageKeys
    }

    private fun deletionBarriersLocked(): List<SyncPlaylistUsageDeletion> {
        val legacy = manualRemovalTimestampsLocked(forceReload = true)
        return SyncPlaylistUsageDeletionPolicy.merge(
            syncStorage.getPlaylistUsageDeletionBarriersConfirmed().orEmpty() + SyncPlaylistUsageDeletionPolicy.fromLegacy(legacy)
        )
    }

    private fun visibleEntriesLocked(entries: List<UsageEntry>): List<UsageEntry> {
        val barriers = deletionBarriersLocked().associateBy { it.playlistKey }
        return entries.filter { entry ->
            SyncPlaylistUsageDeletionPolicy.observes(entry.observedDeletionTokens, barriers[entry.usageKey()]?.deletionTokens.orEmpty())
        }
    }

    private fun rememberManualRemovalLocked(
        playlistKey: String,
        deletedAt: Long = System.currentTimeMillis()
    ) {
        val removals = manualRemovalTimestampsLocked()
        val normalizedTimestamp = deletedAt.coerceAtLeast(1L)
        if (normalizedTimestamp <= (removals[playlistKey] ?: 0L)) {
            return
        }
        try {
            syncStorage.addPlaylistUsageDeletion(playlistKey, normalizedTimestamp)
        } catch (error: Exception) {
            manuallyRemovedUsageKeysLoaded = false
            throw error
        }
        removals[playlistKey] = normalizedTimestamp
    }

    private fun clearManualRemovalLocked(playlistKey: String) {
        val removals = manualRemovalTimestampsLocked()
        if (playlistKey !in removals) {
            return
        }
        try {
            syncStorage.removePlaylistUsageDeletion(playlistKey)
        } catch (error: Exception) {
            manuallyRemovedUsageKeysLoaded = false
            throw error
        }
        removals.remove(playlistKey)
    }

    private fun tryUiTombstoneOperation(operation: () -> Unit): Boolean {
        return try {
            operation()
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            NPLogger.w(
                "PlaylistUsageRepo",
                "Playlist usage mutation refused until deletion state recovers",
                error
            )
            false
        }
    }

    private fun triggerSync() {
        runCatching {
            GitHubSyncWorker.scheduleDelayedSync(
                appContext,
                triggerByUserAction = false,
                markMutation = true
            )
            WebDavSyncWorker.scheduleDelayedSync(
                appContext,
                triggerByUserAction = false,
                markMutation = true
            )
        }
    }
}

private data class UsageWriteSnapshot(
    val previousPersisted: List<UsageEntry>,
    val previousVisible: List<UsageEntry>,
    val next: List<UsageEntry>,
    val generation: Long
)

private fun UsageEntry.rebaseMetadata(previous: UsageEntry?, committed: UsageEntry): UsageEntry {
    val base = previous ?: committed
    return committed.copy(
        name = localChange(base.name, name, committed.name),
        picUrl = localChange(base.picUrl, picUrl, committed.picUrl),
        trackCount = localChange(base.trackCount, trackCount, committed.trackCount),
        fid = localChange(base.fid, fid, committed.fid),
        mid = localChange(base.mid, mid, committed.mid),
        browseId = localChange(base.browseId, browseId, committed.browseId),
        playlistId = localChange(base.playlistId, playlistId, committed.playlistId),
        subtype = localChange(base.subtype, subtype, committed.subtype),
        subtitle = localChange(base.subtitle, subtitle, committed.subtitle)
    )
}

private fun <T> localChange(previous: T?, current: T, committed: T): T =
    if (current != previous) current else committed

private fun UsageEntry?.ownedOpenCount(deviceId: String): Int = this?.counterShards.orEmpty()
    .firstOrNull { it.deviceId == deviceId && it.epochStartedAt == 0L }?.playCount?.coerceAtLeast(0) ?: 0

internal fun resolveRefreshedLocalUsageCover(
    immediateCover: String?,
    resolvedFallback: String?,
    cachedCover: String?,
    resolveLocalMetadataFallback: Boolean
): String? {
    return immediateCover?.takeIf(String::isNotBlank)
        ?: resolvedFallback
            ?.takeIf { resolveLocalMetadataFallback && it.isNotBlank() }
        ?: cachedCover?.takeIf(String::isNotBlank)
}

private fun UsageEntry.toSyncPlaylistUsageStat(): SyncPlaylistUsageStat {
    val normalizedShards = SyncPlaybackStatMapper.normalizeCounterShards(counterShards)
    val shardCount = normalizedShards.fold(0L) { total, shard ->
        total.saturatingAdd(shard.playCount.toLong().coerceAtLeast(0L))
    }
    val baseOpenCount = if (normalizedShards.isEmpty()) {
        0L
    } else {
        maxOf(
            counterBaseOpenCount.coerceAtLeast(0L),
            openCount.toLong().minus(shardCount).coerceAtLeast(0L)
        )
    }
    return SyncPlaylistUsageStat(
        playlistKey = usageKey(),
        source = source,
        id = id,
        subtype = subtype,
        name = name,
        coverUrl = sanitizeCoverUrlForSync(picUrl),
        trackCount = trackCount,
        lastOpenedAt = lastOpened.coerceAtLeast(0L),
        firstOpenedAt = firstOpened.coerceAtLeast(0L),
        openCount = maxOf(openCount.toLong(), baseOpenCount.saturatingAdd(shardCount))
            .toBoundedInt(),
        counterBaseOpenCount = baseOpenCount,
        counterShards = normalizedShards,
        fid = fid ?: 0L,
        mid = mid ?: 0L,
        browseId = browseId,
        playlistId = playlistId,
        subtitle = subtitle,
        observedDeletionTokens = observedDeletionTokens.normalizedSyncCausalTokens()
    )
}

private fun SyncPlaylistUsageStat.toUsageEntry(): UsageEntry {
    return UsageEntry(
        id = id,
        name = name,
        picUrl = sanitizeCoverUrlForSync(coverUrl),
        trackCount = trackCount,
        source = source,
        lastOpened = lastOpenedAt,
        openCount = openCount,
        firstOpened = firstOpenedAt,
        counterBaseOpenCount = counterBaseOpenCount,
        counterShards = counterShards,
        fid = fid.takeIf { it != 0L },
        mid = mid.takeIf { it != 0L },
        browseId = browseId,
        playlistId = playlistId,
        subtype = subtype,
        subtitle = subtitle,
        observedDeletionTokens = observedDeletionTokens.normalizedSyncCausalTokens()
    )
}

private fun UsageEntry.recordOpen(
    deviceId: String,
    openedAt: Long,
    updateLastOpened: Boolean = true
): UsageEntry {
    val normalizedShards = SyncPlaybackStatMapper.normalizeCounterShards(counterShards)
    val previousShardCount = normalizedShards.fold(0L) { total, shard ->
        total.saturatingAdd(shard.playCount.toLong().coerceAtLeast(0L))
    }
    val baseOpenCount = if (normalizedShards.isEmpty()) {
        openCount.toLong().coerceAtLeast(0L)
    } else {
        maxOf(
            counterBaseOpenCount.coerceAtLeast(0L),
            openCount.toLong().minus(previousShardCount).coerceAtLeast(0L)
        )
    }
    val index = normalizedShards.indexOfFirst { shard ->
        shard.deviceId == deviceId && shard.epochStartedAt == 0L
    }
    val currentShard = normalizedShards.getOrNull(index)
    val nextShard = if (currentShard == null) {
        SyncPlaybackCounterShard(
            deviceId = deviceId,
            epochStartedAt = 0L,
            playCount = 1,
            firstPlayedAt = openedAt,
            lastPlayedAt = openedAt
        )
    } else {
        currentShard.copy(
            playCount = currentShard.playCount.saturatingIncrement(),
            firstPlayedAt = minPositiveTimestamp(currentShard.firstPlayedAt, openedAt),
            lastPlayedAt = maxOf(currentShard.lastPlayedAt, openedAt)
        )
    }
    val nextShards = normalizedShards.toMutableList().apply {
        if (index >= 0) {
            this[index] = nextShard
        } else {
            add(nextShard)
        }
    }.let(SyncPlaybackStatMapper::normalizeCounterShards)
    val nextShardCount = nextShards.fold(0L) { total, shard ->
        total.saturatingAdd(shard.playCount.toLong().coerceAtLeast(0L))
    }
    return copy(
        firstOpened = minPositiveTimestamp(firstOpened, openedAt),
        lastOpened = if (updateLastOpened) maxOf(lastOpened, openedAt) else lastOpened,
        openCount = maxOf(
            openCount.toLong().coerceAtLeast(0L),
            baseOpenCount.saturatingAdd(nextShardCount)
        ).toBoundedInt(),
        counterBaseOpenCount = baseOpenCount,
        counterShards = nextShards
    )
}

private fun Long.saturatingAdd(other: Long): Long {
    return if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
}

private fun Int.saturatingIncrement(): Int {
    return if (this == Int.MAX_VALUE) Int.MAX_VALUE else this + 1
}

private fun Long.toBoundedInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

private fun minPositiveTimestamp(left: Long, right: Long): Long {
    return when {
        left <= 0L -> right.coerceAtLeast(0L)
        right <= 0L -> left.coerceAtLeast(0L)
        else -> minOf(left, right)
    }
}

private fun LocalPlaylist.withCoverCandidateLimit(limit: Int): LocalPlaylist {
    val boundedLimit = limit.coerceAtLeast(0)
    if (songs.size <= boundedLimit) return this
    return copy(songs = songs.take(boundedLimit).toMutableList())
}

fun buildLocalPlaylistUsageLookup(
    playlists: List<LocalPlaylist>,
    context: Context,
    requestedPlaylistIds: Set<Long>? = null,
    maxSongsPerPlaylist: Int = Int.MAX_VALUE
): Map<Long, LocalPlaylist> {
    val requestedIds = requestedPlaylistIds?.takeIf { it.isNotEmpty() }
    fun boundedPlaylist(playlist: LocalPlaylist): LocalPlaylist {
        return playlist.withCoverCandidateLimit(maxSongsPerPlaylist)
    }

    val lookup = playlists.asSequence()
        .filter { playlist -> requestedIds == null || playlist.id in requestedIds }
        .associate { playlist -> playlist.id to boundedPlaylist(playlist) }
        .toMutableMap()
    val systemGroups = playlists.asSequence()
        .mapNotNull { playlist ->
            SystemLocalPlaylists.resolve(playlist.id, playlist.name, context)
                ?.id
                ?.let { systemId -> systemId to boundedPlaylist(playlist) }
        }
        .filter { (systemId, _) -> requestedIds == null || systemId in requestedIds }
        .groupBy(
            keySelector = { (systemId, _) -> systemId },
            valueTransform = { (_, playlist) -> playlist }
        )

    systemGroups[FavoritesPlaylist.SYSTEM_ID]
        ?.takeIf { it.isNotEmpty() }
        ?.let { favorites ->
            lookup[FavoritesPlaylist.SYSTEM_ID] = FavoritesPlaylist.merge(favorites, context)
        }
    systemGroups[LocalFilesPlaylist.SYSTEM_ID]
        ?.takeIf { it.isNotEmpty() }
        ?.let { localFiles ->
            lookup[LocalFilesPlaylist.SYSTEM_ID] = LocalFilesPlaylist.merge(localFiles, context)
        }

    return lookup
}
