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

import android.annotation.SuppressLint
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.PlayHistoryRoomImportStatus
import moe.ouom.neriplayer.data.local.database.store.PlayHistoryRoomStore
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.sync.PlayHistoryUpdateMode
import moe.ouom.neriplayer.data.sync.SyncPreferences
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.core.startup.LegacyJsonCleanupScheduler
import moe.ouom.neriplayer.data.sync.model.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.File

data class PlayedEntry(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: Long = 0L,
    val durationMs: Long,
    val resumePositionMs: Long = 0L,
    val coverUrl: String?,
    val mediaUri: String? = null,
    val matchedLyric: String? = null,
    val matchedTranslatedLyric: String? = null,
    val customCoverUrl: String? = null,
    val customName: String? = null,
    val customArtist: String? = null,
    val originalName: String? = null,
    val originalArtist: String? = null,
    val originalCoverUrl: String? = null,
    val originalLyric: String? = null,
    val originalTranslatedLyric: String? = null,
    val localFileName: String? = null,
    val localFilePath: String? = null,
    val channelId: String? = null,
    val audioId: String? = null,
    val subAudioId: String? = null,
    val sourceStableKey: String? = null,
    val playedAt: Long
)

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

internal fun PlayedEntry.toSongItem(): SongItem {
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
    private val initialHistory = loadInitialHistory()
    private val _history = MutableStateFlow(initialHistory)
    private var persistedHistory = initialHistory
    val historyFlow: StateFlow<List<PlayedEntry>> = _history
    private val storage by lazy { SecureTokenStorage(app) }
    private val syncPreferences by lazy { SyncPreferences(app) }
    private var lastBatchSyncTime = 0L
    private val historyMutex = Mutex()
    private var pendingSettledSyncJob: Job? = null
    private var retryJob: Job? = null
    private var pendingSyncUrgency: PlayHistorySyncUrgency? = null
    private val pendingRecentPlayDeletionRemovals = mutableSetOf<SongIdentity>()

    private fun loadInitialHistory(): List<PlayedEntry> {
        if (roomStorageEnabled && roomStore != null) {
            val activeRoomStore = roomStore
            val roomEntries = runCatching {
                runBlocking {
                    activeRoomStore.readIfRoomPrimary()
                }
            }.onFailure { error ->
                roomStorageEnabled = false
                NPLogger.e(
                    "PlayHistoryRepo",
                    "Failed to read Room history; falling back to legacy JSON",
                    error
                )
            }.getOrNull()
            if (roomEntries != null) {
                LegacyJsonCleanupScheduler.schedule(app, "play-history-room-load")
                return roomEntries
            }
        }

        val legacyEntries = loadLegacyFromDisk()
        if (roomStorageEnabled && roomStore != null) {
            val activeRoomStore = roomStore
            val imported = runCatching {
                runBlocking {
                    activeRoomStore.importLegacyAndPromote(legacyEntries)
                }
            }.onFailure { error ->
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
                LegacyJsonCleanupScheduler.schedule(app, "play-history-import")
            }
        }
        return legacyEntries
    }

    private fun loadLegacyFromDisk(): List<PlayedEntry> {
        return try {
            if (!file.exists()) return emptyList()
            val raw = file.readText()
            val type = object : TypeToken<List<PlayedEntry>>() {}.type
            gson.fromJson<List<PlayedEntry>>(raw, type).orEmpty()
                .sortedByDescending { it.playedAt }
                .distinctBy { it.identityKey() }
                .take(1000)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private suspend fun persistSnapshot(
        next: List<PlayedEntry>
    ) {
        val activeRoomStore = roomStore
        if (!roomStorageEnabled || activeRoomStore == null) {
            NPLogger.w(
                "PlayHistoryRepo",
                "Play history update remains in memory until Room is available."
            )
            return
        }
        val roomWriteSucceeded = runCatching {
            activeRoomStore.writeIncremental(previous = persistedHistory, next = next)
        }.onFailure { error ->
            NPLogger.e(
                "PlayHistoryRepo",
                "Failed to write Room history; keeping JSON migration data read-only",
                error
            )
        }.isSuccess
        if (roomWriteSucceeded) {
            persistedHistory = next
            flushPostPersistenceActions(next)
        } else {
            schedulePersistenceRetry()
        }
    }

    private fun schedulePersistenceRetry() {
        if (!roomStorageEnabled || retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(ROOM_RETRY_DELAY_MS)
            historyMutex.withLock {
                retryJob = null
                persistSnapshot(_history.value)
            }
        }
    }

    private fun requestSyncAfterPersistence(urgency: PlayHistorySyncUrgency) {
        pendingSyncUrgency = when {
            pendingSyncUrgency == PlayHistorySyncUrgency.IMMEDIATE ||
                urgency == PlayHistorySyncUrgency.IMMEDIATE -> PlayHistorySyncUrgency.IMMEDIATE
            else -> PlayHistorySyncUrgency.SETTLED
        }
    }

    private fun requestRecentPlayDeletionRemoval(song: SongItem) {
        if (!LocalSongSupport.isLocalSong(song.album, song.mediaUri, song.albumId, app)) {
            pendingRecentPlayDeletionRemovals += song.identityKey()
        }
    }

    private fun flushPostPersistenceActions(persisted: List<PlayedEntry>) {
        val persistedKeys = persisted.mapTo(mutableSetOf()) { entry -> entry.identityKey() }
        pendingRecentPlayDeletionRemovals
            .filter { identityKey -> identityKey in persistedKeys }
            .also { clearedKeys -> pendingRecentPlayDeletionRemovals.removeAll(clearedKeys) }
            .forEach { identityKey ->
                runCatching {
                    storage.removeRecentPlayDeletion(identityKey)
                }.onFailure { error ->
                    NPLogger.e(
                        "PlayHistoryRepo",
                        "Failed to clear superseded recent play deletion",
                        error
                    )
                }
            }
        pendingSyncUrgency?.let { urgency ->
            pendingSyncUrgency = null
            triggerSyncIfNeeded(urgency = urgency, markMutation = false)
        }
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
                delay(delayMs)
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
                return
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
                    .take(1000)

                markSyncMutation()
                requestSyncAfterPersistence(PlayHistorySyncUrgency.SETTLED)
                requestRecentPlayDeletionRemoval(song)
                NPLogger.d("PlayHistoryRepo", "Updated history size: ${updated.size}, latest: ${updated.firstOrNull()?.name}")
                _history.value = updated
                persistSnapshot(next = updated)
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
                    .take(1000)

                markSyncMutation()
                requestSyncAfterPersistence(PlayHistorySyncUrgency.SETTLED)
                requestRecentPlayDeletionRemoval(song)
                _history.value = updated
                persistSnapshot(next = updated)
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
                NPLogger.d(
                    "PlayHistoryRepo",
                    "updateSongMetadata() called: songId=${originalSong.id}"
                )
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
                    .take(1000)

                if (triggerSync) {
                    markSyncMutation()
                    requestSyncAfterPersistence(PlayHistorySyncUrgency.SETTLED)
                }
                _history.value = updated
                persistSnapshot(next = updated)
            }
        }
    }

    fun clear() {
        scope.launch {
            historyMutex.withLock {
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

                requestSyncAfterPersistence(PlayHistorySyncUrgency.IMMEDIATE)
                _history.value = emptyList()
                persistSnapshot(next = emptyList())
            }
        }
    }

    fun removeSongs(songs: List<SongItem>) {
        if (songs.isEmpty()) {
            return
        }

        scope.launch {
            historyMutex.withLock {
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
                requestSyncAfterPersistence(PlayHistorySyncUrgency.IMMEDIATE)
                _history.value = updated
                persistSnapshot(next = updated)
            }
        }
    }

    suspend fun updateHistory(entries: List<PlayedEntry>) {
        historyMutex.withLock {
            NPLogger.d("PlayHistoryRepo", "updateHistory() called: entries=${entries.size}")
            val clipped = entries
                .sortedByDescending { it.playedAt }
                .distinctBy { it.identityKey() }
                .take(1000)
            NPLogger.d("PlayHistoryRepo", "updateHistory() setting history to ${clipped.size} entries, latest: ${clipped.firstOrNull()?.name}")
            _history.value = clipped
            persistSnapshot(next = clipped)
        }
    }

    suspend fun updateHistoryIfUnchanged(
        entries: List<PlayedEntry>,
        expectedMutationVersion: Long
    ): Boolean {
        return historyMutex.withLock {
            if (storage.getSyncMutationVersion() != expectedMutationVersion) {
                return@withLock false
            }
            val clipped = entries
                .sortedByDescending { it.playedAt }
                .distinctBy { it.identityKey() }
                .take(1000)
            _history.value = clipped
            persistSnapshot(next = clipped)
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
        private const val ROOM_RETRY_DELAY_MS = 15_000L

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
