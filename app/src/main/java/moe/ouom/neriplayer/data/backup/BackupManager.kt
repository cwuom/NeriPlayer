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
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.SystemLocalPlaylists
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.sync.github.SyncPlaylist
import moe.ouom.neriplayer.util.NPLogger
import java.io.IOException
import java.io.InputStream
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
    }
    
    /**
     * 备份数据结构
     */
    data class BackupData(
        val version: String = "2.0",
        val timestamp: Long = System.currentTimeMillis(),
        val playlists: List<SyncPlaylist>,
        val exportDate: String = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
    )
    
    /**
     * 导出歌单到指定URI
     */
    suspend fun exportPlaylists(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val playlistRepo = LocalPlaylistRepository.getInstance(context)
            val playlists = playlistRepo.playlists.value

            // 使用SyncPlaylist转换，确保使用网络地址
            val syncPlaylists = playlists.map { playlist ->
                SyncPlaylist.fromLocalPlaylist(playlist, System.currentTimeMillis(), context)
            }

            val backupData = BackupData(
                playlists = syncPlaylists,
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
            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException(context.getString(R.string.error_cannot_open_input))

            val json = inputStream.bufferedReader().use { it.readText() }
            val backupData = gson.fromJson<BackupData>(json, object : TypeToken<BackupData>() {}.type)

            if (backupData.playlists.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("No playlist data in backup file"))  // Localized
            }

            val playlistRepo = LocalPlaylistRepository.getInstance(context)
            val currentPlaylists = playlistRepo.playlists.value.toMutableList()

            var importedCount = 0
            var skippedCount = 0
            var mergedCount = 0

            for (syncPlaylist in backupData.playlists) {
                val importedSystemDescriptor = SystemLocalPlaylists.resolve(
                    syncPlaylist.id,
                    syncPlaylist.name,
                    context
                )
                // 转换为LocalPlaylist
                val importedPlaylist = syncPlaylist.toLocalPlaylist().copy(
                    id = importedSystemDescriptor?.id ?: syncPlaylist.id,
                    name = importedSystemDescriptor?.currentName ?: syncPlaylist.name
                )

                val existingIndex = currentPlaylists.indexOfFirst { playlist ->
                    val existingSystemDescriptor = SystemLocalPlaylists.resolve(
                        playlist.id,
                        playlist.name,
                        context
                    )

                    when {
                        existingSystemDescriptor != null && importedSystemDescriptor != null ->
                            existingSystemDescriptor.id == importedSystemDescriptor.id

                        else ->
                            playlist.id == importedPlaylist.id || playlist.name == importedPlaylist.name
                    }
                }

                if (existingIndex != -1) {
                    // 如果存在同名歌单，进行智能合并
                    val existingPlaylist = currentPlaylists[existingIndex]
                    val mergeResult = mergePlaylists(existingPlaylist, importedPlaylist)

                    if (mergeResult.hasChanges) {
                        currentPlaylists[existingIndex] = mergeResult.mergedPlaylist
                        mergedCount++
                        NPLogger.d(
                            TAG,
                            context.resources.getQuantityString(
                                R.plurals.backup_playlist_merged,
                                mergeResult.addedSongs,
                                importedPlaylist.name,
                                mergeResult.addedSongs
                            )
                        )
                    } else {
                        skippedCount++
                        NPLogger.d(TAG, context.getString(R.string.backup_playlist_no_update, importedPlaylist.name))
                    }
                } else {
                    // 创建新的歌单
                    val newPlaylist = LocalPlaylist(
                        id = System.currentTimeMillis() + importedCount,
                        name = importedPlaylist.name,
                        songs = importedPlaylist.songs.toMutableList()
                    )

                    currentPlaylists.add(newPlaylist)
                    importedCount++
                    NPLogger.d(
                        TAG,
                        context.resources.getQuantityString(
                            R.plurals.backup_playlist_created,
                            newPlaylist.songs.size,
                            importedPlaylist.name,
                            newPlaylist.songs.size
                        )
                    )
                }
            }

            // 更新仓库
            playlistRepo.updatePlaylists(currentPlaylists)

            val result = ImportResult(
                importedCount = importedCount,
                skippedCount = skippedCount,
                mergedCount = mergedCount,
                totalCount = backupData.playlists.size,
                backupDate = backupData.exportDate
            )

            NPLogger.d(TAG, context.getString(R.string.backup_import_success_detail, result))
            Result.success(result)

        } catch (e: Exception) {
            NPLogger.e(TAG, context.getString(R.string.backup_import_failed), e)
            Result.failure(e)
        }
    }

    /**
     * 智能合并歌单，只添加缺失的歌曲
     */
    private fun mergePlaylists(existing: LocalPlaylist, imported: LocalPlaylist): MergeResult {
        val existingSongIds = existing.songs.map { it.identity() }.toSet()
        val newSongs = imported.songs.filter { it.identity() !in existingSongIds }
        
        if (newSongs.isEmpty()) {
            return MergeResult(
                mergedPlaylist = existing,
                hasChanges = false,
                addedSongs = 0
            )
        }
        
        val mergedSongs = (existing.songs + newSongs).toMutableList()
        val mergedPlaylist = existing.copy(songs = mergedSongs)
        
        return MergeResult(
            mergedPlaylist = mergedPlaylist,
            hasChanges = true,
            addedSongs = newSongs.size
        )
    }
    
    /**
     * 分析备份文件与当前歌单的差异
     */
    suspend fun analyzeDifferences(uri: Uri): Result<DifferenceAnalysis> = withContext(Dispatchers.IO) {
        try {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException(context.getString(R.string.error_cannot_open_input))

            val json = inputStream.bufferedReader().use { it.readText() }
            val backupData = gson.fromJson<BackupData>(json, object : TypeToken<BackupData>() {}.type)

            val playlistRepo = LocalPlaylistRepository.getInstance(context)
            val currentPlaylists = playlistRepo.playlists.value

            val differences = mutableListOf<PlaylistDifference>()

            for (syncPlaylist in backupData.playlists) {
                val syncSystemDescriptor = SystemLocalPlaylists.resolve(
                    syncPlaylist.id,
                    syncPlaylist.name,
                    context
                )
                val currentPlaylist = currentPlaylists.find { playlist ->
                    val currentSystemDescriptor = SystemLocalPlaylists.resolve(
                        playlist.id,
                        playlist.name,
                        context
                    )

                    when {
                        currentSystemDescriptor != null && syncSystemDescriptor != null ->
                            currentSystemDescriptor.id == syncSystemDescriptor.id

                        else ->
                            playlist.id == syncPlaylist.id || playlist.name == syncPlaylist.name
                    }
                }

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
                    val currentSongIds = currentPlaylist.songs.map { it.identity() }.toSet()
                    val missingSongs = syncPlaylist.songs.filter { it.identity() !in currentSongIds }

                    if (missingSongs.isNotEmpty()) {
                        differences.add(PlaylistDifference(
                            playlistName = syncPlaylist.name,
                            type = DifferenceType.MISSING_SONGS,
                            missingSongs = missingSongs.size,
                            existingSongs = currentPlaylist.songs.size,
                            totalSongs = syncPlaylist.songs.size
                        ))
                    }
                }
            }

            val analysis = DifferenceAnalysis(
                backupDate = backupData.exportDate,
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
