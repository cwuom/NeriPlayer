package moe.ouom.neriplayer.ui.viewmodel

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
 * File: moe.ouom.neriplayer.ui.viewmodel/BackupRestoreViewModel
 * Created: 2025/8/11
 */

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.backup.BackupManager
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.ui.i18n.UiText

/**
 * 备份与恢复的ViewModel
 */
class BackupRestoreViewModel internal constructor(
    private val playlistCountSourceFactory: PlaylistCountSourceFactory
) : ViewModel() {

    constructor() : this(DefaultPlaylistCountSourceFactory)

    private val _uiState = MutableStateFlow(BackupRestoreUiState())
    val uiState: StateFlow<BackupRestoreUiState> = _uiState

    private var backupManager: BackupManager? = null
    private var strings: BackupRestoreStrings? = null
    private var playlistCountJob: Job? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        strings = BackupRestoreStrings.create()
        if (backupManager == null) {
            backupManager = BackupManager(appContext)
        }
        observePlaylistCount(appContext)
    }

    fun observePlaylistCount(context: Context) {
        if (playlistCountJob?.isActive == true) {
            return
        }
        val appContext = context.applicationContext
        playlistCountJob?.cancel()
        playlistCountJob = viewModelScope.launch {
            try {
                val source = playlistCountSourceFactory.create(appContext)
                if (!source.awaitInitialized()) {
                    return@launch
                }
                source.playlistCount.collect { count ->
                    _uiState.update { state ->
                        state.copy(currentPlaylistCount = count)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                NPLogger.e("BackupRestoreViewModel", "Failed to observe playlist count", error)
            }
        }
    }
    
    /**
     * 导出歌单
     */
    fun exportPlaylists(uri: Uri) {
        val manager = backupManager ?: return
        val resources = strings ?: return

        _uiState.value = _uiState.value.copy(
            isExporting = true,
            exportProgress = resources.exportProgress
        )

        viewModelScope.launch {
            val result = manager.exportPlaylists(uri)

            result.fold(
                onSuccess = { fileName ->
                    _uiState.value = _uiState.value.copy(
                        isExporting = false,
                        exportProgress = null,
                        lastExportSuccess = true,
                        lastExportMessage = resources.exportSuccess(fileName)
                    )
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        isExporting = false,
                        exportProgress = null,
                        lastExportSuccess = false,
                        lastExportMessage = resources.exportFailed(transferFailureText(exception))
                    )
                }
            )
        }
    }
    
    /**
     * 导入歌单
     */
    fun importPlaylists(uri: Uri) {
        val manager = backupManager ?: return
        val resources = strings ?: return

        _uiState.value = _uiState.value.copy(
            isImporting = true,
            importProgress = resources.importProgress
        )

        viewModelScope.launch {
            val result = manager.importPlaylists(uri)

            result.fold(
                onSuccess = { importResult ->
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        importProgress = null,
                        lastImportSuccess = true,
                        lastImportMessage = resources.importSummary(importResult)
                    )
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        importProgress = null,
                        lastImportSuccess = false,
                        lastImportMessage = resources.importFailed(transferFailureText(exception))
                    )
                }
            )
        }
    }
    
    /**
     * 清除导出状态
     */
    fun clearExportStatus() {
        NPLogger.d("BackupRestoreViewModel", "clearExportStatus called")
        _uiState.value = _uiState.value.copy(
            lastExportSuccess = null,
            lastExportMessage = null
        )
    }
    
    /**
     * 清除导入状态
     */
    fun clearImportStatus() {
        NPLogger.d("BackupRestoreViewModel", "clearImportStatus called")
        _uiState.value = _uiState.value.copy(
            lastImportSuccess = null,
            lastImportMessage = null
        )
    }
    
    /**
     * 生成备份文件名
     */
    fun generateBackupFileName(): String {
        return backupManager?.generateBackupFileName() ?: "neriplayer_backup.json"
    }

    override fun onCleared() {
        backupManager = null
        strings = null
    }

    internal class BackupRestoreStrings private constructor() {
        val exportProgress: UiText = UiText.Resource(CoreCommonR.string.playlist_export_progress)
        val importProgress: UiText = UiText.Resource(CoreCommonR.string.playlist_importing)
        val analysisProgress: UiText = UiText.Resource(CoreCommonR.string.playlist_analyzing)

        fun exportSuccess(fileName: String): UiText =
            feedbackValue(UiText.Resource(CoreCommonR.string.playlist_export_success), fileName)

        fun exportFailed(message: Any?): UiText =
            feedbackValue(UiText.Resource(CoreCommonR.string.playlist_export_failed), message ?: "")

        fun importSummary(importResult: BackupManager.ImportResult): UiText = feedbackLines(buildList {
            add(UiText.Resource(CoreCommonR.string.playlist_import_complete))
            add(UiText.Plural(CoreCommonR.plurals.playlist_import_count, importResult.importedCount,
                listOf(importResult.importedCount)))
            if (importResult.hasMerged) {
                add(UiText.Plural(CoreCommonR.plurals.playlist_merge_count, importResult.mergedCount,
                    listOf(importResult.mergedCount)))
            }
            if (importResult.hasSkipped) {
                add(UiText.Plural(CoreCommonR.plurals.playlist_skip_count, importResult.skippedCount,
                    listOf(importResult.skippedCount)))
            }
            add(UiText.Resource(CoreCommonR.string.playlist_backup_date, listOf(importResult.backupDate)))
        })

        fun importFailed(message: Any?): UiText =
            feedbackValue(UiText.Resource(CoreCommonR.string.playlist_import_failed), message ?: "")

        fun analysisFailed(message: String): UiText =
            UiText.Resource(CoreCommonR.string.playlist_analysis_failed, listOf(message))

        companion object {
            fun create(): BackupRestoreStrings {
                return BackupRestoreStrings()
            }
        }
    }
}

/**
 * 备份与恢复的UI状态
 */
data class BackupRestoreUiState(
    val currentPlaylistCount: Int = 0,
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val isAnalyzing: Boolean = false,
    val exportProgress: UiText? = null,
    val importProgress: UiText? = null,
    val analysisProgress: UiText? = null,
    val lastExportSuccess: Boolean? = null,
    val lastExportMessage: UiText? = null,
    val lastImportSuccess: Boolean? = null,
    val lastImportMessage: UiText? = null,
    val differenceAnalysis: BackupManager.DifferenceAnalysis? = null,
    val lastAnalysisError: UiText? = null
)

internal fun interface PlaylistCountSourceFactory {
    suspend fun create(context: Context): PlaylistCountSource
}

internal interface PlaylistCountSource {
    val playlistCount: StateFlow<Int>
    suspend fun awaitInitialized(): Boolean
}

private object DefaultPlaylistCountSourceFactory : PlaylistCountSourceFactory {
    override suspend fun create(context: Context): PlaylistCountSource {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            LocalPlaylistRepositoryCountSource(LocalPlaylistRepository.getInstance(appContext))
        }
    }
}

private class LocalPlaylistRepositoryCountSource(
    private val repository: LocalPlaylistRepository
) : PlaylistCountSource {
    override val playlistCount: StateFlow<Int> = repository.playlistCount

    override suspend fun awaitInitialized(): Boolean {
        return repository.awaitInitialized()
    }
}
