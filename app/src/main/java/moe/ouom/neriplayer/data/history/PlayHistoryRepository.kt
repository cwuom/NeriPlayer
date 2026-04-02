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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.github.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class PlayedEntry(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: Long = 0L,
    val durationMs: Long,
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
    val playedAt: Long
)

class PlayHistoryRepository private constructor(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val file: File by lazy { File(app.filesDir, "play_history.json") }
    private val _history = MutableStateFlow(loadFromDisk())
    val historyFlow: StateFlow<List<PlayedEntry>> = _history
    private val storage by lazy { SecureTokenStorage(app) }
    private var lastBatchSyncTime = 0L
    private val writing = AtomicBoolean(false)

    private fun loadFromDisk(): List<PlayedEntry> {
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

    private fun persistAsync(list: List<PlayedEntry>) {
        scope.launch {
            runCatching {
                file.writeText(gson.toJson(list))
            }
        }
    }

    private fun triggerSyncIfNeeded() {
        try {
            val mode = storage.getPlayHistoryUpdateMode()
            val now = System.currentTimeMillis()
            when (mode) {
                SecureTokenStorage.PlayHistoryUpdateMode.IMMEDIATE -> triggerAutoSync()
                SecureTokenStorage.PlayHistoryUpdateMode.BATCHED -> {
                    if (now - lastBatchSyncTime >= 10 * 60 * 1000) {
                        lastBatchSyncTime = now
                        triggerAutoSync()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun triggerAutoSync() {
        try {
            storage.markSyncMutation()
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
        NPLogger.d("PlayHistoryRepo", "record() called: songId=${song.id}, name=${song.name}, writing=${writing.get()}")
        if (writing.get()) {
            NPLogger.w("PlayHistoryRepo", "record() blocked by writing lock, skipping")
            return
        }

        writing.set(true)
        try {
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

            NPLogger.d("PlayHistoryRepo", "Updated history size: ${updated.size}, latest: ${updated.firstOrNull()?.name}")
            _history.value = updated
            persistAsync(updated)

            if (!LocalSongSupport.isLocalSong(song.album, song.mediaUri, song.albumId, app)) {
                storage.removeRecentPlayDeletion(song.identityKey())
            }
            triggerSyncIfNeeded()
        } finally {
            writing.set(false)
        }
    }

    fun updateSongMetadata(originalSong: SongItem, updatedSong: SongItem) {
        NPLogger.d(
            "PlayHistoryRepo",
            "updateSongMetadata() called: songId=${originalSong.id}, writing=${writing.get()}"
        )
        if (writing.get()) {
            NPLogger.w("PlayHistoryRepo", "updateSongMetadata() blocked by writing lock, skipping")
            return
        }

        writing.set(true)
        try {
            val current = _history.value
            val existingIndex = current.indexOfFirst { it.identityKey() == originalSong.identityKey() }
            if (existingIndex == -1) {
                return
            }

            val updatedEntry = current[existingIndex].mergeSongMetadata(updatedSong)
            val updated = current.toMutableList().apply {
                this[existingIndex] = updatedEntry
            }
                .sortedByDescending { it.playedAt }
                .distinctBy { it.identityKey() }
                .take(1000)

            _history.value = updated
            persistAsync(updated)
            triggerSyncIfNeeded()
        } finally {
            writing.set(false)
        }
    }

    fun clear() {
        val current = _history.value
        if (current.isEmpty()) {
            return
        }

        val deletedAt = System.currentTimeMillis()
        val deviceId = storage.getOrCreateDeviceId()
        val deletions = current
            .filterNot { LocalSongSupport.isLocalSong(it.album, it.mediaUri, it.albumId, app) }
            .map { it.toRecentPlayDeletion(deletedAt, deviceId) }
        if (deletions.isNotEmpty()) {
            storage.addRecentPlayDeletions(deletions)
        }

        _history.value = emptyList()
        persistAsync(emptyList())
        triggerSyncIfNeeded()
    }

    fun removeSongs(songs: List<SongItem>) {
        if (songs.isEmpty()) {
            return
        }

        val current = _history.value
        val removalKeys = songs.map { it.identityKey() }.toSet()
        val removedEntries = current.filter { it.identityKey() in removalKeys }
        if (removedEntries.isEmpty()) {
            return
        }

        val deletedAt = System.currentTimeMillis()
        val deviceId = storage.getOrCreateDeviceId()
        val deletions = removedEntries
            .filterNot { LocalSongSupport.isLocalSong(it.album, it.mediaUri, it.albumId, app) }
            .map { it.toRecentPlayDeletion(deletedAt, deviceId) }
        if (deletions.isNotEmpty()) {
            storage.addRecentPlayDeletions(deletions)
        }

        val updated = current.filterNot { it.identityKey() in removalKeys }
        _history.value = updated
        persistAsync(updated)
        triggerSyncIfNeeded()
    }

    fun updateHistory(entries: List<PlayedEntry>) {
        NPLogger.d("PlayHistoryRepo", "updateHistory() called: entries=${entries.size}, writing=${writing.get()}")
        if (writing.get()) {
            NPLogger.w("PlayHistoryRepo", "updateHistory() blocked by writing lock, skipping")
            return
        }

        writing.set(true)
        try {
            val clipped = entries
                .sortedByDescending { it.playedAt }
                .distinctBy { it.identityKey() }
                .take(1000)
            NPLogger.d("PlayHistoryRepo", "updateHistory() setting history to ${clipped.size} entries, latest: ${clipped.firstOrNull()?.name}")
            _history.value = clipped
            persistAsync(clipped)
        } finally {
            writing.set(false)
        }
    }

    private fun PlayedEntry.identityKey(): SongIdentity {
        return SongIdentity(id, album, localFilePath ?: mediaUri)
    }

    private fun SongItem.identityKey(): SongIdentity {
        return SongIdentity(id, album, localFilePath ?: mediaUri)
    }

    private fun SongItem.toPlayedEntry(now: Long): PlayedEntry {
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
            playedAt = now
        )
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
                INSTANCE ?: PlayHistoryRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
