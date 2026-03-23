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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.backup.BackupManager
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.util.NPLogger
import java.util.Locale

/**
 * 备份与恢复的ViewModel
 */
class BackupRestoreViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(BackupRestoreUiState())
    val uiState: StateFlow<BackupRestoreUiState> = _uiState

    private var backupManager: BackupManager? = null
    private var strings: BackupRestoreStrings? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        strings = BackupRestoreStrings.from(appContext)
        if (backupManager == null) {
            backupManager = BackupManager(appContext)
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
                        lastExportMessage = resources.exportFailed(exception.message)
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
                    val message = buildString {
                        append(resources.importComplete)
                        append("\n${resources.importCount(importResult.importedCount)}")
                        if (importResult.hasMerged) {
                            append("\n${resources.mergeCount(importResult.mergedCount)}")
                        }
                        if (importResult.hasSkipped) {
                            append("\n${resources.skipCount(importResult.skippedCount)}")
                        }
                        append("\n${resources.backupDate(importResult.backupDate)}")
                    }

                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        importProgress = null,
                        lastImportSuccess = true,
                        lastImportMessage = message
                    )
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        importProgress = null,
                        lastImportSuccess = false,
                        lastImportMessage = resources.importFailed(exception.message)
                    )
                }
            )
        }
    }
    
    /**
     * 分析备份文件差异
     */
    fun analyzeDifferences(uri: Uri) {
        val manager = backupManager ?: return
        val resources = strings ?: return

        _uiState.value = _uiState.value.copy(
            isAnalyzing = true,
            analysisProgress = resources.analysisProgress
        )

        viewModelScope.launch {
            val result = manager.analyzeDifferences(uri)

            result.fold(
                onSuccess = { analysis ->
                    _uiState.value = _uiState.value.copy(
                        isAnalyzing = false,
                        analysisProgress = null,
                        differenceAnalysis = analysis
                    )
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        isAnalyzing = false,
                        analysisProgress = null,
                        lastAnalysisError = resources.analysisFailed(exception.message ?: "")
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
     * 清除分析状态
     */
    fun clearAnalysisStatus() {
        NPLogger.d("BackupRestoreViewModel", "clearAnalysisStatus called")
        _uiState.value = _uiState.value.copy(
            differenceAnalysis = null,
            lastAnalysisError = null
        )
    }
    
    /**
     * 获取当前歌单数量
     */
    fun getCurrentPlaylistCount(context: Context): Int {
        return LocalPlaylistRepository.getInstance(context).playlists.value.size
    }
    
    /**
     * 生成备份文件名
     */
    fun generateBackupFileName(): String {
        return backupManager?.generateBackupFileName() ?: "neriplayer_backup.json"
    }

    override fun onCleared() {
        super.onCleared()
        backupManager = null
        strings = null
    }

    private data class BackupRestoreStrings(
        val exportProgress: String,
        val exportSuccessPrefix: String,
        val exportFailedPrefix: String,
        val importProgress: String,
        val importComplete: String,
        val importCountFormat: String,
        val mergeCountFormat: String,
        val skipCountFormat: String,
        val backupDateFormat: String,
        val importFailedPrefix: String,
        val analysisProgress: String,
        val analysisFailedFormat: String
    ) {
        fun exportSuccess(fileName: String): String = "$exportSuccessPrefix: $fileName"

        fun exportFailed(message: String?): String = "$exportFailedPrefix: $message"

        fun importCount(count: Int): String = format(importCountFormat, count)

        fun mergeCount(count: Int): String = format(mergeCountFormat, count)

        fun skipCount(count: Int): String = format(skipCountFormat, count)

        fun backupDate(date: String): String = format(backupDateFormat, date)

        fun importFailed(message: String?): String = "$importFailedPrefix: $message"

        fun analysisFailed(message: String): String = format(analysisFailedFormat, message)

        companion object {
            fun from(context: Context): BackupRestoreStrings {
                return BackupRestoreStrings(
                    exportProgress = context.getString(R.string.playlist_export_progress),
                    exportSuccessPrefix = context.getString(R.string.playlist_export_success),
                    exportFailedPrefix = context.getString(R.string.playlist_export_failed),
                    importProgress = context.getString(R.string.playlist_importing),
                    importComplete = context.getString(R.string.playlist_import_complete),
                    importCountFormat = context.getString(R.string.playlist_import_count),
                    mergeCountFormat = context.getString(R.string.playlist_merge_count),
                    skipCountFormat = context.getString(R.string.playlist_skip_count),
                    backupDateFormat = context.getString(R.string.playlist_backup_date),
                    importFailedPrefix = context.getString(R.string.playlist_import_failed),
                    analysisProgress = context.getString(R.string.playlist_analyzing),
                    analysisFailedFormat = context.getString(R.string.playlist_analysis_failed)
                )
            }

            private fun format(template: String, vararg args: Any): String {
                return String.format(Locale.getDefault(), template, *args)
            }
        }
    }
}

/**
 * 备份与恢复的UI状态
 */
data class BackupRestoreUiState(
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val isAnalyzing: Boolean = false,
    val exportProgress: String? = null,
    val importProgress: String? = null,
    val analysisProgress: String? = null,
    val lastExportSuccess: Boolean? = null,
    val lastExportMessage: String? = null,
    val lastImportSuccess: Boolean? = null,
    val lastImportMessage: String? = null,
    val differenceAnalysis: BackupManager.DifferenceAnalysis? = null,
    val lastAnalysisError: String? = null
)
