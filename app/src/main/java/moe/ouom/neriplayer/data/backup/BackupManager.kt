package moe.ouom.neriplayer.data.backup

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
 * File: moe.ouom.neriplayer.data.backup/BackupManager
 * Created: 2025/8/11
 */

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.config.LimitedTextReader
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.model.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.sync.SyncCoordinator
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.github.SyncPlaylist
import moe.ouom.neriplayer.data.sync.github.SyncPlaybackStatBucket
import moe.ouom.neriplayer.data.sync.github.SyncRecentPlay
import moe.ouom.neriplayer.data.sync.github.SyncTrackStat
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.util.NPLogger
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份管理器
 * 负责歌单的导入导出功能
 */
class BackupManager(private val context: Context) {
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    
    companion object {
        private const val TAG = "BackupManager"
        private const val BACKUP_FILE_PREFIX = "neriplayer_backup"
        private const val BACKUP_FILE_EXTENSION = ".json"
        private const val MAX_BACKUP_HISTORY_COUNT = 1000
        private const val MAX_BACKUP_IMPORT_BYTES = 10L * 1024L * 1024L
    }

    private data class PlaylistLookup(
        val descriptors: MutableList<SystemLocalPlaylists.Descriptor?> = mutableListOf(),
        val bySystemId: MutableMap<Long, Int> = mutableMapOf(),
        val byId: MutableMap<Long, MutableList<Int>> = mutableMapOf(),
        val byName: MutableMap<String, MutableList<Int>> = mutableMapOf(),
        val usedIds: MutableSet<Long> = mutableSetOf()
    )
    
    /**
     * 备份数据结构
     */
    data class BackupData(
        val version: String = "2.0",
        val timestamp: Long = System.currentTimeMillis(),
        val playlists: List<SyncPlaylist>? = emptyList(),
        val recentPlays: List<SyncRecentPlay>? = emptyList(),
        val playbackStats: List<SyncTrackStat>? = emptyList(),
        val playbackStatBuckets: List<SyncPlaybackStatBucket>? = emptyList(),
        val playbackStatsClearedAt: Long = 0L,
        val exportDate: String? = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
    )
    
    /**
     * 导出歌单到指定URI
     */
    suspend fun exportPlaylists(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val playlistRepo = LocalPlaylistRepository.getInstance(context)
            val historyRepo = PlayHistoryRepository.getInstance(context)
            val playbackStatsRepo = PlaybackStatsRepository.getInstance(context)
            val playlists = playlistRepo.playlists.value

            // 使用SyncPlaylist转换，确保使用网络地址
            val syncPlaylists = playlists.map { playlist ->
                SyncPlaylist.fromLocalPlaylist(playlist, System.currentTimeMillis(), context)
            }
            val recentPlays = historyRepo.historyFlow.value
                .filter { BackupMetadataMapper.shouldExportHistory(it, context) }
                .take(MAX_BACKUP_HISTORY_COUNT)
                .map(BackupMetadataMapper::toSyncRecentPlay)
            val playbackStats = playbackStatsRepo.statsFlow.value
                .filter { BackupMetadataMapper.shouldExportTrackStat(it, context) }
                .map(BackupMetadataMapper::toSyncTrackStat)
            val playbackStatBuckets = playbackStatsRepo.dailyStatsFlow.value
                .filter { BackupMetadataMapper.shouldExportPlaybackStatBucket(it, context) }
                .map(BackupMetadataMapper::toSyncPlaybackStatBucket)

            val backupData = BackupData(
                version = "2.2",
                playlists = syncPlaylists,
                recentPlays = recentPlays,
                playbackStats = playbackStats,
                playbackStatBuckets = playbackStatBuckets,
                playbackStatsClearedAt = playbackStatsRepo.statsClearedAtFlow.value,
                exportDate = dateFormat.format(Date())
            )

            val json = gson.toJson(backupData)

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(json.toByteArray(Charsets.UTF_8))
            } ?: throw IOException(context.getString(R.string.error_cannot_open_output))

            val fileName = "${BACKUP_FILE_PREFIX}_${dateFormat.format(Date())}$BACKUP_FILE_EXTENSION"
            NPLogger.d(TAG, context.getString(R.string.backup_export_success_file, fileName))
            Result.success(fileName)

        } catch (e: Exception) {
            NPLogger.e(TAG, context.getString(R.string.backup_export_failed), e)
            Result.failure(e)
        }
    }
    
    /**
     * 从指定URI导入歌单
     */
    suspend fun importPlaylists(uri: Uri): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val json = LimitedTextReader.readUtf8(context, uri, MAX_BACKUP_IMPORT_BYTES)
            val backupData = gson.fromJson<BackupData>(json, object : TypeToken<BackupData>() {}.type)
            val backupPlaylists = backupData.playlists.orEmpty()

            if (backupPlaylists.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("No playlist data in backup file"))  // Localized
            }

            SyncCoordinator.withExclusive {
                val playlistRepo = LocalPlaylistRepository.getInstance(context)
                val historyRepo = PlayHistoryRepository.getInstance(context)
                val playbackStatsRepo = PlaybackStatsRepository.getInstance(context)
                val currentPlaylists = playlistRepo.playlists.value.toMutableList()
                val playlistLookup = buildPlaylistLookup(currentPlaylists)

                var importedCount = 0
                var skippedCount = 0
                var mergedCount = 0

                for (syncPlaylist in backupPlaylists) {
                    val importedSystemDescriptor = SystemLocalPlaylists.resolve(
                        syncPlaylist.id,
                        syncPlaylist.name,
                        context
                    )
                    val importedPlaylistName = importedSystemDescriptor?.currentName ?: syncPlaylist.name
                    val importedPlaylistForMatch = LocalPlaylist(
                        id = importedSystemDescriptor?.id ?: syncPlaylist.id,
                        name = importedPlaylistName
                    )

                    val existingIndex = findMatchingPlaylistIndex(
                        playlists = currentPlaylists,
                        lookup = playlistLookup,
                        importedPlaylist = importedPlaylistForMatch,
                        importedSystemDescriptor = importedSystemDescriptor
                    )

                    if (existingIndex != -1) {
                        // 如果存在同名歌单，进行智能合并
                        val existingPlaylist = currentPlaylists[existingIndex]
                        val mergeResult = mergePlaylists(existingPlaylist, syncPlaylist)

                        if (mergeResult.hasChanges) {
                            currentPlaylists[existingIndex] = mergeResult.mergedPlaylist
                            mergedCount++
                            NPLogger.d(
                                TAG,
                                context.resources.getQuantityString(
                                    R.plurals.backup_playlist_merged,
                                    mergeResult.addedSongs,
                                    importedPlaylistName,
                                    mergeResult.addedSongs
                                )
                            )
                        } else {
                            skippedCount++
                            NPLogger.d(TAG, context.getString(R.string.backup_playlist_no_update, importedPlaylistName))
                        }
                    } else {
                        // 创建新的歌单
                        val newPlaylistId = nextImportedPlaylistId(playlistLookup, importedCount)
                        val newPlaylist = LocalPlaylist(
                            id = newPlaylistId,
                            name = importedPlaylistName,
                            songs = syncPlaylist.songs.map { it.toSongItem() }.toMutableList(),
                            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                        )

                        currentPlaylists.add(newPlaylist)
                        registerPlaylist(playlistLookup, newPlaylist, currentPlaylists.lastIndex)
                        importedCount++
                        NPLogger.d(
                            TAG,
                            context.resources.getQuantityString(
                                R.plurals.backup_playlist_created,
                                newPlaylist.songs.size,
                                importedPlaylistName,
                                newPlaylist.songs.size
                            )
                        )
                    }
                }

                // 更新仓库
                playlistRepo.updatePlaylists(currentPlaylists)
                importRecentPlays(historyRepo, backupData.recentPlays.orEmpty())
                importPlaybackStats(
                    playbackStatsRepo = playbackStatsRepo,
                    playbackStats = backupData.playbackStats.orEmpty(),
                    playbackStatBuckets = backupData.playbackStatBuckets.orEmpty(),
                    playbackStatsClearedAt = backupData.playbackStatsClearedAt
                )
                SecureTokenStorage(context).markSyncMutation()

                val result = ImportResult(
                    importedCount = importedCount,
                    skippedCount = skippedCount,
                    mergedCount = mergedCount,
                    totalCount = backupPlaylists.size,
                    backupDate = backupData.exportDate ?: dateFormat.format(Date(backupData.timestamp))
                )

                NPLogger.d(TAG, context.getString(R.string.backup_import_success_detail, result))
                Result.success(result)
            }

        } catch (e: Exception) {
            NPLogger.e(TAG, context.getString(R.string.backup_import_failed), e)
            Result.failure(e)
        }
    }

    /**
     * 智能合并歌单，只添加缺失的歌曲
     */
    private fun mergePlaylists(existing: LocalPlaylist, imported: SyncPlaylist): MergeResult {
        val existingSongIds = existing.songs.mapTo(HashSet(existing.songs.size)) { it.identity() }
        val newSongs = imported.songs
            .filter { it.identity() !in existingSongIds }
            .map { it.toSongItem() }
        
        if (newSongs.isEmpty()) {
            return MergeResult(
                mergedPlaylist = existing,
                hasChanges = false,
                addedSongs = 0
            )
        }
        
        val mergedSongs = (newSongs + existing.songs).toMutableList()
        val mergedPlaylist = existing.copy(
            songs = mergedSongs,
            songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
        )
        
        return MergeResult(
            mergedPlaylist = mergedPlaylist,
            hasChanges = true,
            addedSongs = newSongs.size
        )
    }

    private fun buildPlaylistLookup(playlists: List<LocalPlaylist>): PlaylistLookup {
        val lookup = PlaylistLookup()
        playlists.forEachIndexed { index, playlist ->
            registerPlaylist(lookup, playlist, index)
        }
        return lookup
    }

    private fun registerPlaylist(
        lookup: PlaylistLookup,
        playlist: LocalPlaylist,
        index: Int
    ) {
        val descriptor = SystemLocalPlaylists.resolve(playlist.id, playlist.name, context)
        while (lookup.descriptors.size <= index) {
            lookup.descriptors.add(null)
        }
        lookup.descriptors[index] = descriptor
        descriptor?.let { lookup.bySystemId.putIfAbsent(it.id, index) }
        lookup.byId.getOrPut(playlist.id) { mutableListOf() }.add(index)
        lookup.byName.getOrPut(playlist.name) { mutableListOf() }.add(index)
        lookup.usedIds.add(playlist.id)
    }

    private fun findMatchingPlaylistIndex(
        playlists: List<LocalPlaylist>,
        lookup: PlaylistLookup,
        importedPlaylist: LocalPlaylist,
        importedSystemDescriptor: SystemLocalPlaylists.Descriptor?
    ): Int {
        val candidateIndexes = linkedSetOf<Int>()
        importedSystemDescriptor?.let { descriptor ->
            lookup.bySystemId[descriptor.id]?.let(candidateIndexes::add)
        }
        lookup.byId[importedPlaylist.id]?.let(candidateIndexes::addAll)
        lookup.byName[importedPlaylist.name]?.let(candidateIndexes::addAll)

        return candidateIndexes
            .asSequence()
            .sorted()
            .firstOrNull { index ->
                val existing = playlists.getOrNull(index)
                if (existing == null) {
                    false
                } else {
                    val existingDescriptor = lookup.descriptors.getOrNull(index)
                    when {
                        existingDescriptor != null && importedSystemDescriptor != null ->
                            existingDescriptor.id == importedSystemDescriptor.id

                        else ->
                            existing.id == importedPlaylist.id || existing.name == importedPlaylist.name
                    }
                }
            }
            ?: -1
    }

    private fun nextImportedPlaylistId(lookup: PlaylistLookup, importedCount: Int): Long {
        var candidate = System.currentTimeMillis() + importedCount
        while (!lookup.usedIds.add(candidate)) {
            candidate++
        }
        return candidate
    }

    private suspend fun importRecentPlays(
        historyRepo: PlayHistoryRepository,
        recentPlays: List<SyncRecentPlay>
    ) {
        if (recentPlays.isEmpty()) return

        val imported = recentPlays.mapNotNull { BackupMetadataMapper.toPlayedEntry(it, context) }
        if (imported.isEmpty()) return

        val merged = (imported + historyRepo.historyFlow.value)
            .sortedByDescending { it.playedAt }
            .distinctBy { "${it.id}|${it.album}|${it.localFilePath ?: it.mediaUri.orEmpty()}" }
            .take(MAX_BACKUP_HISTORY_COUNT)
        historyRepo.updateHistory(merged)
    }

    private suspend fun importPlaybackStats(
        playbackStatsRepo: PlaybackStatsRepository,
        playbackStats: List<SyncTrackStat>,
        playbackStatBuckets: List<SyncPlaybackStatBucket>,
        playbackStatsClearedAt: Long
    ) {
        val sanitizedStats = playbackStats.mapNotNull {
            BackupMetadataMapper.sanitizeTrackStat(it, context)
        }
        val sanitizedBuckets = playbackStatBuckets.mapNotNull {
            BackupMetadataMapper.sanitizePlaybackStatBucket(it, context)
        }
        if (sanitizedStats.isNotEmpty() || sanitizedBuckets.isNotEmpty()) {
            playbackStatsRepo.applyMergedStats(
                syncStats = sanitizedStats,
                playbackStatsClearedAt = playbackStatsClearedAt.coerceAtLeast(0L),
                respectLocalClear = false,
                syncDailyStats = sanitizedBuckets
            )
        }
    }
    
    /**
     * 分析备份文件与当前歌单的差异
     */
    suspend fun analyzeDifferences(uri: Uri): Result<DifferenceAnalysis> = withContext(Dispatchers.IO) {
        try {
            val json = LimitedTextReader.readUtf8(context, uri, MAX_BACKUP_IMPORT_BYTES)
            val backupData = gson.fromJson<BackupData>(json, object : TypeToken<BackupData>() {}.type)
            val backupPlaylists = backupData.playlists.orEmpty()

            val playlistRepo = LocalPlaylistRepository.getInstance(context)
            val currentPlaylists = playlistRepo.playlists.value
            val playlistLookup = buildPlaylistLookup(currentPlaylists)

            val differences = mutableListOf<PlaylistDifference>()

            for (syncPlaylist in backupPlaylists) {
                val syncSystemDescriptor = SystemLocalPlaylists.resolve(
                    syncPlaylist.id,
                    syncPlaylist.name,
                    context
                )
                val currentPlaylistIndex = findMatchingPlaylistIndex(
                    playlists = currentPlaylists,
                    lookup = playlistLookup,
                    importedPlaylist = LocalPlaylist(
                        id = syncSystemDescriptor?.id ?: syncPlaylist.id,
                        name = syncSystemDescriptor?.currentName ?: syncPlaylist.name
                    ),
                    importedSystemDescriptor = syncSystemDescriptor
                )
                val currentPlaylist = currentPlaylists.getOrNull(currentPlaylistIndex)

                if (currentPlaylist == null) {
                    // 新歌单
                    differences.add(PlaylistDifference(
                        playlistName = syncPlaylist.name,
                        type = DifferenceType.NEW_PLAYLIST,
                        missingSongs = syncPlaylist.songs.size,
                        existingSongs = 0,
                        totalSongs = syncPlaylist.songs.size
                    ))
                } else {
                    // 现有歌单，分析差异
                    val currentSongIds = currentPlaylist.songs.mapTo(
                        HashSet(currentPlaylist.songs.size)
                    ) { it.identity() }
                    val missingSongs = syncPlaylist.songs.count { it.identity() !in currentSongIds }

                    if (missingSongs > 0) {
                        differences.add(PlaylistDifference(
                            playlistName = syncPlaylist.name,
                            type = DifferenceType.MISSING_SONGS,
                            missingSongs = missingSongs,
                            existingSongs = currentPlaylist.songs.size,
                            totalSongs = syncPlaylist.songs.size
                        ))
                    }
                }
            }

            val analysis = DifferenceAnalysis(
                backupDate = backupData.exportDate ?: dateFormat.format(Date(backupData.timestamp)),
                differences = differences,
                totalMissingSongs = differences.sumOf { it.missingSongs }
            )

            Result.success(analysis)
            
        } catch (e: Exception) {
            NPLogger.e(TAG, context.getString(R.string.sync_diff_failed), e)
            Result.failure(e)
        }
    }
    
    /**
     * 生成默认备份文件名
     */
    fun generateBackupFileName(): String {
        return "${BACKUP_FILE_PREFIX}_${dateFormat.format(Date())}$BACKUP_FILE_EXTENSION"
    }
    
    /**
     * 导入结果
     */
    data class ImportResult(
        val importedCount: Int,
        val skippedCount: Int,
        val mergedCount: Int,
        val totalCount: Int,
        val backupDate: String
    ) {
        val hasSkipped: Boolean get() = skippedCount > 0
        val hasMerged: Boolean get() = mergedCount > 0
    }
    
    /**
     * 歌单合并结果
     */
    data class MergeResult(
        val mergedPlaylist: LocalPlaylist,
        val hasChanges: Boolean,
        val addedSongs: Int
    )
    
    /**
     * 差异类型
     */
    enum class DifferenceType {
        NEW_PLAYLIST,      // 新歌单
        MISSING_SONGS      // 缺失歌曲
    }
    
    /**
     * 歌单差异信息
     */
    data class PlaylistDifference(
        val playlistName: String,
        val type: DifferenceType,
        val missingSongs: Int,
        val existingSongs: Int,
        val totalSongs: Int
    )
    
    /**
     * 差异分析结果
     */
    data class DifferenceAnalysis(
        val backupDate: String,
        val differences: List<PlaylistDifference>,
        val totalMissingSongs: Int
    )
}
