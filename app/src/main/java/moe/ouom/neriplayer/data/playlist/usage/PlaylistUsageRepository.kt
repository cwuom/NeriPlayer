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


import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.PlaylistUsageRoomStore
import moe.ouom.neriplayer.data.local.playlist.model.buildLocalArtistSummaries
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.FavoritesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatMapper
import moe.ouom.neriplayer.data.sync.github.SyncPlaylistUsageStatsMergePolicy
import moe.ouom.neriplayer.data.sync.model.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.sync.model.SyncPlaylistUsageStat
import moe.ouom.neriplayer.data.sync.model.sanitizeCoverUrlForSync
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.core.startup.LegacyJsonCleanupScheduler
import moe.ouom.neriplayer.util.io.writeTextAtomically
import moe.ouom.neriplayer.util.platform.LanguageManager
import java.io.File
import java.util.UUID

data class UsageEntry(
    val id: Long,
    val name: String,
    val picUrl: String?,
    val trackCount: Int,
    val source: String, // "netease" | "neteaseAlbum" | "bili" | "local" | "localArtist" | "youtubeMusic"
    val lastOpened: Long,
    val openCount: Int,
    val firstOpened: Long = lastOpened,
    val counterBaseOpenCount: Long = 0L,
    val counterShards: List<SyncPlaybackCounterShard> = emptyList(),
    val fid: Long? = null,
    val mid: Long? = null,
    val browseId: String? = null,
    val playlistId: String? = null,
    val subtype: String? = null,
    val subtitle: String? = null,
)

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

internal fun normalizeUsageEntries(list: List<UsageEntry>): List<UsageEntry> {
    return list
        .filterNotNull()
        .map { entry ->
            entry.copy(counterShards = entry.counterShards.orEmpty().filterNotNull())
        }
        .filter(UsageEntry::hasPlayableTracks)
        .groupBy(UsageEntry::usageKey)
        .map { (_, duplicates) -> mergeDuplicateUsageEntries(duplicates) }
        .sortedWith(usageEntryComparator)
}

private fun mergeDuplicateUsageEntries(entries: List<UsageEntry>): UsageEntry {
    val latest = entries.sortedWith(usageEntryComparator).first()
    val allLegacyCounters = entries.all { entry ->
        entry.counterBaseOpenCount <= 0L && entry.counterShards.orEmpty().isEmpty()
    }
    if (!allLegacyCounters) {
        val merged = SyncPlaylistUsageStatsMergePolicy.mergePlaylistUsageStats(
            local = entries.map(UsageEntry::toSyncPlaylistUsageStat),
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
    val mergedOpenCount = entries.sumOf(UsageEntry::openCount)
        .coerceAtLeast(latest.openCount)
    return latest.copy(
        openCount = mergedOpenCount,
        firstOpened = entries.fold(0L) { earliest, entry ->
            minPositiveTimestamp(earliest, entry.firstOpened)
        }
    )
}

class PlaylistUsageRepository internal constructor(
    private val app: Context,
    private val roomStore: PlaylistUsageRoomStore? = null
) {
    companion object {
        const val SOURCE_LOCAL = "local"
        const val SOURCE_LOCAL_ARTIST = "localArtist"

        @Volatile
        private var instance: PlaylistUsageRepository? = null

        fun getInstance(context: Context): PlaylistUsageRepository {
            return instance ?: synchronized(this) {
                val appContext = context.applicationContext
                instance ?: PlaylistUsageRepository(
                    app = appContext,
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
    private val file: File by lazy { File(app.filesDir, "playlist_usage.json") }
    private val syncStorage by lazy { SecureTokenStorage(app) }
    private val fallbackCounterDeviceId = "playlist-usage-${UUID.randomUUID()}"
    private val mutationLock = Any()
    private val persistenceMutex = Mutex()
    private var persistenceGeneration = 0L
    private val manuallyRemovedUsageKeys = mutableMapOf<String, Long>()
    private var manuallyRemovedUsageKeysLoaded = false
    @Volatile
    private var roomStorageEnabled = roomStore != null
    private val initialEntries = load()
    private val _flow = MutableStateFlow(initialEntries)
    private var persistedEntries = initialEntries
    val frequentPlaylistsFlow: StateFlow<List<UsageEntry>> = _flow

    private fun load(): List<UsageEntry> {
        if (roomStorageEnabled && roomStore != null) {
            val activeRoomStore = roomStore
            val roomEntries = runCatching {
                runBlocking { activeRoomStore.readIfRoomPrimary() }
            }.onFailure { error ->
                roomStorageEnabled = false
                NPLogger.e(
                    "PlaylistUsageRepo",
                    "Failed to read Room playlist usage; falling back to JSON",
                    error
                )
            }.getOrNull()
            if (roomEntries != null) {
                LegacyJsonCleanupScheduler.schedule(app, "playlist-usage-room-load")
                return normalizeUsageEntries(roomEntries)
            }
        }

        val list: List<UsageEntry> = try {
            if (!file.exists()) {
                emptyList()
            } else {
                gson.fromJson<List<UsageEntry>>(
                    file.readText(),
                    object : TypeToken<List<UsageEntry>>() {}.type
                ) ?: emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }

        val normalized = normalizeUsageEntries(list)
        if (roomStorageEnabled && roomStore != null) {
            val activeRoomStore = roomStore
            runCatching {
                runBlocking { activeRoomStore.importLegacyAndPromote(normalized) }
            }.onFailure { error ->
                roomStorageEnabled = false
                NPLogger.e(
                    "PlaylistUsageRepo",
                    "Failed to promote playlist usage JSON to Room",
                    error
                )
            }
            LegacyJsonCleanupScheduler.schedule(app, "playlist-usage-import")
        }
        return normalized
    }

    private fun saveAsync(list: List<UsageEntry>) {
        val generation = synchronized(mutationLock) {
            persistenceGeneration += 1L
            persistenceGeneration
        }
        scope.launch {
            persistenceMutex.withLock {
                val isLatest = synchronized(mutationLock) {
                    generation == persistenceGeneration
                }
                if (!isLatest) return@withLock

                if (roomStorageEnabled && roomStore != null) {
                    val activeRoomStore = roomStore
                    val roomWriteSucceeded = runCatching {
                        activeRoomStore.writeIncremental(
                            previous = persistedEntries,
                            next = list
                        )
                    }.onFailure { error ->
                        roomStorageEnabled = false
                        NPLogger.e(
                            "PlaylistUsageRepo",
                            "Failed to write Room playlist usage; falling back to JSON",
                            error
                        )
                    }.isSuccess
                    if (roomWriteSucceeded) {
                        persistedEntries = list
                        return@withLock
                    }
                }

                runCatching { file.writeTextAtomically(gson.toJson(list)) }
                    .onFailure { error ->
                        NPLogger.e("PlaylistUsageRepo", "Failed to persist playlist usage", error)
                    }
                roomStore?.let { fallbackStore ->
                    runCatching { fallbackStore.markLegacyJsonPrimary() }
                        .onFailure { error ->
                            NPLogger.e(
                                "PlaylistUsageRepo",
                                "Failed to mark playlist usage JSON fallback state",
                                error
                            )
                        }
                }
                persistedEntries = list
            }
        }
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
        now: Long = System.currentTimeMillis()
    ) {
        if (trackCount <= 0) {
            removeEntryIfPresent(id, source, subtype)
            return
        }

        val deviceId = syncCounterDeviceId()
        val out = synchronized(mutationLock) {
            val data = _flow.value.toMutableList()
            val targetKey = playlistUsageKey(source, id, subtype)
            clearManualRemovalLocked(targetKey)
            val idx = data.indexOfFirst { it.usageKey() == targetKey }
            val updated = if (idx >= 0) {
                data[idx].copy(
                    name = name,
                    picUrl = picUrl,
                    trackCount = trackCount,
                    fid = fid,
                    mid = mid,
                    browseId = browseId,
                    playlistId = playlistId,
                    subtype = subtype,
                    subtitle = subtitle ?: data[idx].subtitle
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
                    subtitle = subtitle
                )
            }
            val counted = updated.recordOpen(deviceId = deviceId, openedAt = now)
            if (idx >= 0) {
                data[idx] = counted
            } else {
                data.add(counted)
            }
            normalizeUsageEntries(data).also { _flow.value = it }
        }
        saveAsync(out)
        triggerSync()
    }

    fun syncStats(): List<SyncPlaylistUsageStat> {
        return synchronized(mutationLock) {
            _flow.value.map(UsageEntry::toSyncPlaylistUsageStat)
        }
    }

    fun applyMergedStats(stats: List<SyncPlaylistUsageStat>) {
        val out = synchronized(mutationLock) {
            val manualRemovals = manualRemovalTimestampsLocked()
            val localStats = _flow.value
                .filter { entry ->
                    !isManuallyRemoved(entry.usageKey(), entry.lastOpened, manualRemovals)
                }
                .map(UsageEntry::toSyncPlaylistUsageStat)
            val remoteStats = stats.filter { stat ->
                !isManuallyRemoved(stat.playlistKey.trim(), stat.lastOpenedAt, manualRemovals)
            }
            val merged = SyncPlaylistUsageStatsMergePolicy.mergePlaylistUsageStats(
                local = localStats,
                remote = remoteStats
            )
            normalizeUsageEntries(merged.map(SyncPlaylistUsageStat::toUsageEntry))
                .also { _flow.value = it }
        }
        saveAsync(out)
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

        val deviceId = syncCounterDeviceId()
        val out = synchronized(mutationLock) {
            val data = _flow.value.toMutableList()
            val targetKey = playlistUsageKey(source, id, subtype)
            clearManualRemovalLocked(targetKey)
            val idx = data.indexOfFirst { it.usageKey() == targetKey }
            if (idx >= 0) {
                val old = data[idx]
                data[idx] = old.copy(
                    name = name,
                    picUrl = picUrl,
                    trackCount = trackCount,
                    fid = fid,
                    mid = mid,
                    browseId = browseId ?: old.browseId,
                    playlistId = playlistId ?: old.playlistId,
                    subtype = subtype ?: old.subtype,
                    subtitle = subtitle ?: old.subtitle
                )
            } else {
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
                    subtitle = subtitle
                ).recordOpen(deviceId = deviceId, openedAt = now)
            }

            normalizeUsageEntries(data).also { _flow.value = it }
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
        localFilesCoverCandidates: List<SongItem> = emptyList()
    ) {
        val current = _flow.value
        if (current.none { it.source == SOURCE_LOCAL }) return

        val localizedContext = LanguageManager.applyLanguage(app)
        val localPlaylistLookup = buildLocalPlaylistUsageLookup(playlists, localizedContext)
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
            val refreshedPicUrl = playlist.displayCoverUrl(
                context = localizedContext,
                resolveLocalMetadataFallback = true,
                additionalCoverCandidates = if (LocalFilesPlaylist.isSystemPlaylist(
                        playlist,
                        localizedContext
                    )
                ) {
                    localFilesCoverCandidates
                } else {
                    emptyList()
                }
            )
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

        if (!changed) return

        val out = normalizeUsageEntries(updated)
        _flow.value = out
        saveAsync(out)
    }

    /** 同步本地歌手虚拟歌单卡片信息 */
    fun syncLocalArtistEntries(playlists: List<LocalPlaylist>) {
        val current = _flow.value
        if (current.none { it.source == SOURCE_LOCAL_ARTIST }) return

        val localizedContext = LanguageManager.applyLanguage(app)
        val artistsById = buildLocalArtistSummaries(playlists, localizedContext)
            .associateBy { artist -> artist.id }
        var changed = false
        val updated = current.mapNotNull { entry ->
            if (entry.source != SOURCE_LOCAL_ARTIST) return@mapNotNull entry

            val artist = artistsById[entry.id] ?: run {
                changed = true
                return@mapNotNull null
            }

            val refreshedPicUrl = artist.displayCoverUrl(
                context = localizedContext,
                resolveLocalMetadataFallback = true
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

        if (!changed) return

        val out = normalizeUsageEntries(updated)
        _flow.value = out
        saveAsync(out)
    }

    /** 从继续播放列表中移除指定项 */
    fun removeEntry(id: Long, source: String, subtype: String? = null) {
        val targetKey = playlistUsageKey(source, id, subtype)
        val out = synchronized(mutationLock) {
            rememberManualRemovalLocked(targetKey)
            val data = _flow.value.toMutableList()
            val removed = data.removeAll { it.usageKey() == targetKey }
            if (!removed) {
                return@synchronized null
            }
            normalizeUsageEntries(data).also { _flow.value = it }
        }
        out?.let(::saveAsync)
        triggerSync()
    }

    private fun removeEntryIfPresent(id: Long, source: String, subtype: String? = null) {
        val out = synchronized(mutationLock) {
            val data = _flow.value.toMutableList()
            val targetKey = playlistUsageKey(source, id, subtype)
            val removed = data.removeAll { it.usageKey() == targetKey }
            if (!removed) return
            normalizeUsageEntries(data).also { _flow.value = it }
        }
        saveAsync(out)
    }

    private fun syncCounterDeviceId(): String {
        return runCatching { syncStorage.getOrCreateDeviceId() }
            .getOrDefault(fallbackCounterDeviceId)
    }

    private fun manualRemovalTimestampsLocked(): MutableMap<String, Long> {
        if (!manuallyRemovedUsageKeysLoaded) {
            val persisted = runCatching { syncStorage.getPlaylistUsageDeletions() }
                .getOrDefault(emptyMap())
            manuallyRemovedUsageKeys.putAll(persisted)
            manuallyRemovedUsageKeysLoaded = true
        }
        return manuallyRemovedUsageKeys
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
        removals[playlistKey] = normalizedTimestamp
        runCatching {
            syncStorage.addPlaylistUsageDeletion(playlistKey, normalizedTimestamp)
        }.onFailure { error ->
            NPLogger.w(
                "PlaylistUsageRepo",
                "Failed to persist manually removed playlist usage",
                error
            )
        }
    }

    private fun clearManualRemovalLocked(playlistKey: String) {
        val removals = manualRemovalTimestampsLocked()
        if (removals.remove(playlistKey) == null) {
            return
        }
        runCatching {
            syncStorage.removePlaylistUsageDeletion(playlistKey)
        }.onFailure { error ->
            NPLogger.w(
                "PlaylistUsageRepo",
                "Failed to clear manually removed playlist usage",
                error
            )
        }
    }

    private fun isManuallyRemoved(
        playlistKey: String,
        openedAt: Long,
        removals: Map<String, Long>
    ): Boolean {
        val removedAt = removals[playlistKey] ?: return false
        return openedAt <= removedAt
    }

    private fun triggerSync() {
        runCatching {
            GitHubSyncWorker.scheduleDelayedSync(
                app,
                triggerByUserAction = false,
                markMutation = true
            )
            WebDavSyncWorker.scheduleDelayedSync(
                app,
                triggerByUserAction = false,
                markMutation = true
            )
        }
    }
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
        subtitle = subtitle
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
        subtitle = subtitle
    )
}

private fun UsageEntry.recordOpen(deviceId: String, openedAt: Long): UsageEntry {
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
        lastOpened = maxOf(lastOpened, openedAt),
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

internal fun buildLocalPlaylistUsageLookup(
    playlists: List<LocalPlaylist>,
    context: Context
): Map<Long, LocalPlaylist> {
    val lookup = playlists.associateBy(LocalPlaylist::id).toMutableMap()
    val systemGroups = playlists.groupBy { playlist ->
        SystemLocalPlaylists.resolve(playlist.id, playlist.name, context)?.id
    }

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
