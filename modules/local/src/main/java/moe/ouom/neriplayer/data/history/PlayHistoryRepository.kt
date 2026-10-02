package moe.ouom.neriplayer.data.history

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
 * File: moe.ouom.neriplayer.data.history/PlayHistoryRepository
 * Updated: 2026/3/23
 */

import moe.ouom.neriplayer.data.sync.mapping.toSongItem
import moe.ouom.neriplayer.data.sync.mapping.fromSongItemOrNull
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.PlayHistoryRoomImportStatus
import moe.ouom.neriplayer.data.local.database.store.PlayHistoryRoomStore
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.sync.store.preferences.PlayHistoryUpdateMode
import moe.ouom.neriplayer.data.sync.store.preferences.SyncPreferences
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.local.database.maintenance.LegacyJsonCleanupRequests
import moe.ouom.neriplayer.common.io.writeTextAtomically
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.common.logging.NPLogger
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

internal fun SongItem.toPlayedEntry(now: Long): PlayedEntry {
    return PlayedEntry(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        coverUrl = coverUrl,
        mediaUri = mediaUri,
        matchedLyric = matchedLyric,
        matchedTranslatedLyric = matchedTranslatedLyric,
        matchedRomanizedLyric = matchedRomanizedLyric,
        originalRomanizedLyric = originalRomanizedLyric,
        matchedLyricSource = matchedLyricSource,
        matchedSongId = matchedSongId,
        userLyricOffsetMs = userLyricOffsetMs,
        lyricSyncRevision = lyricSyncRevision,
        lyricSyncEdited = lyricSyncEdited,
        customCoverUrl = customCoverUrl,
        customName = customName,
        customArtist = customArtist,
        originalName = originalName,
        originalArtist = originalArtist,
        originalCoverUrl = originalCoverUrl,
        originalLyric = originalLyric,
        originalTranslatedLyric = originalTranslatedLyric,
        localFileName = localFileName,
        localFilePath = localFilePath,
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        sourceStableKey = sourceStableKey,
        playedAt = now
    )
}

fun PlayedEntry.toSongItem(): SongItem {
    return SongItem(
        id = id,
        name = name,
        artist = artist,
        albumId = albumId,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
        mediaUri = localFilePath ?: mediaUri,
        matchedLyric = matchedLyric,
        matchedTranslatedLyric = matchedTranslatedLyric,
        matchedRomanizedLyric = matchedRomanizedLyric,
        originalRomanizedLyric = originalRomanizedLyric,
        matchedLyricSource = matchedLyricSource,
        matchedSongId = matchedSongId,
        userLyricOffsetMs = userLyricOffsetMs,
        lyricSyncRevision = lyricSyncRevision,
        lyricSyncEdited = lyricSyncEdited,
        customCoverUrl = customCoverUrl,
        customName = customName,
        customArtist = customArtist,
        originalName = originalName,
        originalArtist = originalArtist,
        originalCoverUrl = originalCoverUrl,
        originalLyric = originalLyric,
        originalTranslatedLyric = originalTranslatedLyric,
        localFileName = localFileName,
        localFilePath = localFilePath,
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        sourceStableKey = sourceStableKey
    )
}

internal enum class PlayHistorySyncUrgency {
    SETTLED,
    IMMEDIATE
}

internal fun playHistoryAutoSyncDelayMillis(urgency: PlayHistorySyncUrgency): Long {
    return when (urgency) {
        PlayHistorySyncUrgency.SETTLED -> 15_000L
        PlayHistorySyncUrgency.IMMEDIATE -> 0L
    }
}

class PlayHistoryRepository private constructor(
    private val app: Context,
    private val roomStore: PlayHistoryRoomStore? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val file: File by lazy { File(app.filesDir, "play_history.json") }
    @Volatile
    private var roomStorageEnabled = roomStore != null
    @Volatile
    private var initialized = false
    private var initialLoadFailure: Exception? = null
    private val _history = MutableStateFlow(loadInitialHistory())
    @Volatile
    private var persistedHistory = _history.value
    @Volatile
    private var baselineTrusted = initialized
    private var pendingUiChanges = false
    val historyFlow: StateFlow<List<PlayedEntry>> = _history
    private val storage by lazy { SecureTokenStorage(app) }
    private val syncPreferences by lazy { SyncPreferences(app) }
    private var lastBatchSyncTime = 0L
    private val historyMutex = Mutex()
    private var pendingSettledSyncJob: Job? = null

    private fun loadInitialHistory(): List<PlayedEntry> {
        return runBlocking(Dispatchers.IO) {
            tryLoadHistory()?.also { initialized = true }.orEmpty()
        }
    }

    private suspend fun loadTrustedHistory(): List<PlayedEntry> {
        if (roomStorageEnabled && roomStore != null) {
            val activeRoomStore = roomStore
            // 读取失败不能证明 Room 尚未接管，旧 JSON 可能已经过时或被清理
            val roomEntries = activeRoomStore.readIfRoomPrimary()
            if (roomEntries != null) {
                LegacyJsonCleanupRequests.schedule(app, "play-history-room-load")
                return roomEntries
            }
        }

        val legacyEntries = loadLegacyFromDisk()
        if (roomStorageEnabled && roomStore != null) {
            val activeRoomStore = roomStore
            val imported = runCatching {
                activeRoomStore.importLegacyAndPromote(legacyEntries)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                roomStorageEnabled = false
                NPLogger.e(
                    "PlayHistoryRepo",
                    "Failed to promote legacy history to Room",
                    error
                )
            }.getOrNull()
            if (imported?.status == PlayHistoryRoomImportStatus.SKIPPED_NOT_EQUIVALENT) {
                roomStorageEnabled = false
                NPLogger.w(
                    "PlayHistoryRepo",
                    "Room history mapper is not equivalent; keep legacy JSON"
                )
            } else if (imported?.status == PlayHistoryRoomImportStatus.IMPORTED) {
                LegacyJsonCleanupRequests.schedule(app, "play-history-import")
            }
        }
        return legacyEntries
    }

    private fun loadLegacyFromDisk(): List<PlayedEntry> {
        if (!file.exists()) return emptyList()
        val type = object : TypeToken<List<PlayedEntry>>() {}.type
        return (gson.fromJson<List<PlayedEntry>>(file.readText(), type)
            ?: throw IOException("Play history JSON has no valid list"))
            .sortedByDescending { it.playedAt }
            .distinctBy { it.identityKey() }
    }

    private suspend fun tryLoadHistory(): List<PlayedEntry>? = try {
        loadTrustedHistory().also { initialLoadFailure = null }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        initialLoadFailure = error
        NPLogger.e("PlayHistoryRepo", "History unavailable; preserving storage for retry", error)
        null
    }

    private suspend fun ensureInitializedLocked(): Boolean {
        if (initialized) return recoverBaselineLocked()
        val loaded = tryLoadHistory() ?: return false
        _history.value = loaded
        persistedHistory = loaded
        baselineTrusted = true
        pendingUiChanges = false
        initialized = true
        return true
    }

    private suspend fun recoverBaselineLocked(): Boolean = try {
        if (!baselineTrusted) {
            // 事务可能已经提交后才通知取消，必须重新读取实际主存再计算增量
            val roomHistory = roomStore?.readIfRoomPrimary()
            val actual = roomHistory ?: loadLegacyFromDisk()
            currentCoroutineContext().ensureActive()
            persistedHistory = actual
            roomStorageEnabled = roomHistory != null
            baselineTrusted = true
        }
        if (!pendingUiChanges) _history.value = persistedHistory
        true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        initialLoadFailure = error
        NPLogger.e("PlayHistoryRepo", "History authority unavailable; refusing an uncertain baseline", error)
        false
    }

    private fun publishUiHistory(history: List<PlayedEntry>) {
        pendingUiChanges = true
        _history.value = history
    }

    private fun confirmPersistedHistory(history: List<PlayedEntry>) {
        persistedHistory = history
        baselineTrusted = true
        if (_history.value == history) pendingUiChanges = false
    }

    suspend fun awaitInitialized(): Boolean = withContext(Dispatchers.IO) {
        historyMutex.withLock {
            ensureInitializedLocked() && flushPendingWritesLocked()
        }
    }

    fun syncSnapshot(): List<PlayedEntry> {
        if (!initialized || !baselineTrusted) throw IOException("Play history has no trusted snapshot", initialLoadFailure)
        val current = _history.value
        if (current != persistedHistory || !baselineTrusted) throw IOException("Play history still has uncommitted changes")
        return current
    }

    private suspend fun flushPendingWritesLocked(): Boolean = try {
        persistSnapshotChecked(_history.value)
        true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        NPLogger.e("PlayHistoryRepo", "History changes remain pending until persistence recovers", error)
        false
    }

    private suspend fun persistSnapshot(next: List<PlayedEntry>) {
        try {
            persistSnapshotChecked(next)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            NPLogger.e("PlayHistoryRepo", "Failed to persist play history", error)
        }
    }

    private suspend fun persistSnapshotChecked(next: List<PlayedEntry>) {
        currentCoroutineContext().ensureActive()
        if (!initialized || !baselineTrusted) throw IOException("Cannot write unknown play history", initialLoadFailure)
        if (next == persistedHistory) {
            confirmPersistedHistory(next)
            return
        }
        baselineTrusted = false
        if (roomStorageEnabled && roomStore != null) {
            val activeRoomStore = roomStore
            try {
                activeRoomStore.writeIncremental(previous = persistedHistory, next = next)
                confirmPersistedHistory(next)
                return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                roomStorageEnabled = false
                NPLogger.e(
                    "PlayHistoryRepo",
                    "Failed to write Room history; falling back to legacy JSON",
                    error
                )
            }
        }

        currentCoroutineContext().ensureActive()
        file.writeTextAtomically(gson.toJson(next))
        currentCoroutineContext().ensureActive()
        roomStore?.markLegacyJsonPrimary()
        confirmPersistedHistory(next)
    }

    private fun markSyncMutation() {
        runCatching {
            storage.markSyncMutation()
        }.onFailure { error ->
            NPLogger.e("PlayHistoryRepo", "Failed to mark sync mutation", error)
        }
    }

    private fun triggerSyncIfNeeded(
        urgency: PlayHistorySyncUrgency = PlayHistorySyncUrgency.IMMEDIATE,
        markMutation: Boolean = true
    ) {
        if (markMutation) {
            markSyncMutation()
        }
        try {
            val mode = syncPreferences.getUpdateMode(storage.getLegacyPlayHistoryUpdateModeName())
            val now = System.currentTimeMillis()
            when (mode) {
                PlayHistoryUpdateMode.IMMEDIATE -> triggerAutoSync(urgency)
                else -> {
                    if (now - lastBatchSyncTime >= mode.intervalMillis) {
                        lastBatchSyncTime = now
                        triggerAutoSync(urgency)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun triggerAutoSync(urgency: PlayHistorySyncUrgency) {
        val delayMs = playHistoryAutoSyncDelayMillis(urgency)
        if (delayMs > 0L) {
            pendingSettledSyncJob?.cancel()
            pendingSettledSyncJob = scope.launch {
                delay(delayMs.milliseconds)
                triggerAutoSyncNow()
            }
            return
        }
        pendingSettledSyncJob?.cancel()
        pendingSettledSyncJob = null
        triggerAutoSyncNow()
    }

    private fun triggerAutoSyncNow() {
        try {
            if (!storage.isAutoSyncEnabled()) {
                NPLogger.d("PlayHistoryRepo", "Auto sync is disabled, skipping sync")
            }
            GitHubSyncWorker.scheduleDelayedSync(app, triggerByUserAction = false)
            WebDavSyncWorker.scheduleDelayedSync(app, triggerByUserAction = false)
            NPLogger.d("PlayHistoryRepo", "Sync scheduled after play history change")
        } catch (e: Exception) {
            NPLogger.e("PlayHistoryRepo", "Failed to trigger sync", e)
        }
    }

    fun record(song: SongItem, now: Long = System.currentTimeMillis()) {
        scope.launch {
            historyMutex.withLock {
                if (!ensureInitializedLocked()) return@withLock
                NPLogger.d("PlayHistoryRepo", "record() called: songId=${song.id}, name=${song.name}")
                val current = _history.value
                NPLogger.d("PlayHistoryRepo", "Current history size: ${current.size}")

                val songIdentityKey = song.identityKey()
                val existingIndex = current.indexOfFirst { it.identityKey() == songIdentityKey }
                val latestEntry = if (existingIndex >= 0) {
                    NPLogger.d("PlayHistoryRepo", "Updating existing entry at index $existingIndex")
                    current[existingIndex].mergeSongMetadata(song, playedAt = now)
                } else {
                    NPLogger.d("PlayHistoryRepo", "Creating new entry")
                    song.toPlayedEntry(now)
                }

                val updated = buildList {
                    add(latestEntry)
                    current.forEachIndexed { index, entry ->
                        if (index != existingIndex) {
                            add(entry)
                        }
                    }
                }
                    .sortedByDescending { it.playedAt }
                    .distinctBy { it.identityKey() }

                markSyncMutation()
                NPLogger.d("PlayHistoryRepo", "Updated history size: ${updated.size}, latest: ${updated.firstOrNull()?.name}")
                publishUiHistory(updated)
                persistSnapshot(updated)

                if (!LocalSongSupport.isLocalSong(song.album, song.mediaUri, song.albumId, app)) {
                    storage.removeRecentPlayDeletion(song.identityKey())
                }
                triggerSyncIfNeeded(
                    urgency = PlayHistorySyncUrgency.SETTLED,
                    markMutation = false
                )
            }
        }
    }

    fun rememberedPlaybackPosition(song: SongItem): Long {
        val songIdentityKey = song.identityKey()
        return _history.value
            .firstOrNull { it.identityKey() == songIdentityKey }
            ?.resumePositionMs
            ?.coerceAtLeast(0L)
            ?: 0L
    }

    fun updateRememberedPlaybackPosition(
        song: SongItem,
        positionMs: Long,
        now: Long = System.currentTimeMillis()
    ) {
        val normalizedPositionMs = positionMs.coerceAtLeast(0L)
        scope.launch {
            historyMutex.withLock {
                if (!ensureInitializedLocked()) return@withLock
                val current = _history.value
                val songIdentityKey = song.identityKey()
                val existingIndex = current.indexOfFirst { it.identityKey() == songIdentityKey }
                val existingEntry = current.getOrNull(existingIndex)
                if (existingEntry != null && existingEntry.playedAt > now) {
                    return@withLock
                }
                if (existingEntry == null && normalizedPositionMs == 0L) {
                    return@withLock
                }
                if (existingEntry?.resumePositionMs == normalizedPositionMs) {
                    return@withLock
                }

                val latestEntry = (existingEntry?.mergeSongMetadata(song, playedAt = now)
                    ?: song.toPlayedEntry(now)).copy(
                    resumePositionMs = normalizedPositionMs,
                    playedAt = now
                )
                val updated = buildList {
                    add(latestEntry)
                    current.forEachIndexed { index, entry ->
                        if (index != existingIndex) {
                            add(entry)
                        }
                    }
                }
                    .sortedByDescending { it.playedAt }
                    .distinctBy { it.identityKey() }

                markSyncMutation()
                publishUiHistory(updated)
                persistSnapshot(updated)
                if (!LocalSongSupport.isLocalSong(song.album, song.mediaUri, song.albumId, app)) {
                    storage.removeRecentPlayDeletion(song.identityKey())
                }
                triggerSyncIfNeeded(
                    urgency = PlayHistorySyncUrgency.SETTLED,
                    markMutation = false
                )
            }
        }
    }

    fun updateSongMetadata(
        originalSong: SongItem,
        updatedSong: SongItem,
        triggerSync: Boolean = true
    ) {
        scope.launch {
            historyMutex.withLock {
                if (!ensureInitializedLocked()) return@withLock
                NPLogger.d(
                    "PlayHistoryRepo",
                    "updateSongMetadata() called: songId=${originalSong.id}"
                )
                if (updatedSong.lyricSyncEdited != null && updatedSong.lyricSyncRevision > 0L) {
                    SyncSong.fromSongItemOrNull(updatedSong, app)
                        ?.let(storage::recordLyricOverride)
                }
                val current = _history.value
                val existingIndex = current.indexOfFirst { it.identityKey() == originalSong.identityKey() }
                if (existingIndex == -1) {
                    return@withLock
                }

                val updatedEntry = current[existingIndex].mergeSongMetadata(updatedSong)
                if (updatedEntry == current[existingIndex]) {
                    return@withLock
                }
                val updated = current.toMutableList().apply {
                    this[existingIndex] = updatedEntry
                }
                    .sortedByDescending { it.playedAt }
                    .distinctBy { it.identityKey() }

                publishUiHistory(updated)
                persistSnapshot(updated)
                if (triggerSync) {
                    triggerSyncIfNeeded(PlayHistorySyncUrgency.SETTLED)
                }
            }
        }
    }

    fun clear() {
        scope.launch {
            historyMutex.withLock {
                if (!ensureInitializedLocked()) return@withLock
                val current = _history.value
                if (current.isEmpty()) {
                    return@withLock
                }

                val deletedAt = System.currentTimeMillis()
                val deviceId = storage.getOrCreateDeviceId()
                val deletions = current
                    .filterNot { LocalSongSupport.isLocalSong(it.album, it.mediaUri, it.albumId, app) }
                    .map { it.toRecentPlayDeletion(deletedAt, deviceId) }
                if (deletions.isNotEmpty()) {
                    storage.addRecentPlayDeletions(deletions)
                } else {
                    markSyncMutation()
                }

                publishUiHistory(emptyList())
                persistSnapshot(emptyList())
                triggerSyncIfNeeded(markMutation = false)
            }
        }
    }

    fun removeSongs(songs: List<SongItem>) {
        if (songs.isEmpty()) {
            return
        }

        scope.launch {
            historyMutex.withLock {
                if (!ensureInitializedLocked()) return@withLock
                val current = _history.value
                val removalKeys = songs.map { it.identityKey() }.toSet()
                val removedEntries = current.filter { it.identityKey() in removalKeys }
                if (removedEntries.isEmpty()) {
                    return@withLock
                }

                val deletedAt = System.currentTimeMillis()
                val deviceId = storage.getOrCreateDeviceId()
                val deletions = removedEntries
                    .filterNot { LocalSongSupport.isLocalSong(it.album, it.mediaUri, it.albumId, app) }
                    .map { it.toRecentPlayDeletion(deletedAt, deviceId) }
                if (deletions.isNotEmpty()) {
                    storage.addRecentPlayDeletions(deletions)
                } else {
                    markSyncMutation()
                }

                val updated = current.filterNot { it.identityKey() in removalKeys }
                publishUiHistory(updated)
                persistSnapshot(updated)
                triggerSyncIfNeeded(markMutation = false)
            }
        }
    }

    suspend fun updateHistory(entries: List<PlayedEntry>) {
        historyMutex.withLock {
            if (!ensureInitializedLocked()) throw IOException("Cannot replace unknown play history", initialLoadFailure)
            NPLogger.d("PlayHistoryRepo", "updateHistory() called: entries=${entries.size}")
            val normalized = entries
                .sortedByDescending { it.playedAt }
                .distinctBy { it.identityKey() }
            NPLogger.d("PlayHistoryRepo", "updateHistory() setting history to ${normalized.size} entries, latest: ${normalized.firstOrNull()?.name}")
            publishUiHistory(normalized)
            persistSnapshot(normalized)
        }
    }

    suspend fun updateHistoryIfUnchanged(
        entries: List<PlayedEntry>,
        expectedMutationVersion: Long
    ): Boolean {
        return historyMutex.withLock {
            if (!ensureInitializedLocked()) return@withLock false
            if (storage.getSyncMutationVersion() != expectedMutationVersion) {
                return@withLock false
            }
            val normalized = entries
                .sortedByDescending { it.playedAt }
                .distinctBy { it.identityKey() }
            persistSnapshotChecked(normalized)
            currentCoroutineContext().ensureActive()
            _history.value = normalized
            pendingUiChanges = false
            true
        }
    }

    private fun PlayedEntry.identityKey(): SongIdentity {
        return SongIdentity(id, album, localFilePath ?: mediaUri)
    }

    private fun SongItem.identityKey(): SongIdentity {
        return SongIdentity(id, album, localFilePath ?: mediaUri)
    }

    private fun PlayedEntry.mergeSongMetadata(song: SongItem, playedAt: Long = this.playedAt): PlayedEntry {
        return copy(
            name = song.name,
            artist = song.artist,
            album = song.album,
            albumId = song.albumId,
            durationMs = song.durationMs,
            coverUrl = song.coverUrl,
            mediaUri = song.mediaUri,
            matchedLyric = song.matchedLyric,
            matchedTranslatedLyric = song.matchedTranslatedLyric,
            matchedRomanizedLyric = song.matchedRomanizedLyric,
            originalRomanizedLyric = song.originalRomanizedLyric,
            matchedLyricSource = song.matchedLyricSource,
            matchedSongId = song.matchedSongId,
            userLyricOffsetMs = song.userLyricOffsetMs,
            lyricSyncRevision = song.lyricSyncRevision,
            lyricSyncEdited = song.lyricSyncEdited,
            customCoverUrl = song.customCoverUrl,
            customName = song.customName,
            customArtist = song.customArtist,
            originalName = song.originalName,
            originalArtist = song.originalArtist,
            originalCoverUrl = song.originalCoverUrl,
            originalLyric = song.originalLyric,
            originalTranslatedLyric = song.originalTranslatedLyric,
            localFileName = song.localFileName,
            localFilePath = song.localFilePath,
            channelId = song.channelId,
            audioId = song.audioId,
            subAudioId = song.subAudioId,
            sourceStableKey = song.sourceStableKey,
            playedAt = playedAt
        )
    }

    private fun PlayedEntry.toRecentPlayDeletion(
        deletedAt: Long,
        deviceId: String
    ): SyncRecentPlayDeletion {
        return SyncRecentPlayDeletion(
            songId = id,
            album = album,
            mediaUri = LocalSongSupport.sanitizeMediaUriForSync(localFilePath ?: mediaUri),
            deletedAt = deletedAt,
            deviceId = deviceId
        )
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: PlayHistoryRepository? = null

        fun getInstance(context: Context): PlayHistoryRepository {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                INSTANCE ?: PlayHistoryRepository(
                    app = appContext,
                    roomStore = PlayHistoryRoomStore(
                        database = NeriUserDataDatabase.getInstance(appContext)
                    )
                ).also { INSTANCE = it }
            }
        }
    }
}
