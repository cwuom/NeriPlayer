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
import moe.ouom.neriplayer.data.BackupManager
import moe.ouom.neriplayer.data.LocalPlaylistRepository

/**
 * 备份与恢复的ViewModel
 */
class BackupRestoreViewModel : ViewModel() {
    
    private val _uiState = MutableStateFlow(BackupRestoreUiState())
    val uiState: StateFlow<BackupRestoreUiState> = _uiState
    
    private var backupManager: BackupManager? = null
    
    fun initialize(context: Context) {
        if (backupManager == null) {
            backupManager = BackupManager(context)
        }
    }
    
    /**
     * 导出歌单
     */
    fun exportPlaylists(uri: Uri) {
        val manager = backupManager ?: return
        
        _uiState.value = _uiState.value.copy(
            isExporting = true,
            exportProgress = "正在导出歌单..."
        )
        
        viewModelScope.launch {
            val result = manager.exportPlaylists(uri)
            
            result.fold(
                onSuccess = { fileName ->
                    _uiState.value = _uiState.value.copy(
                        isExporting = false,
                        exportProgress = null,
                        lastExportSuccess = true,
                        lastExportMessage = "歌单导出成功: $fileName"
                    )
                },
                onFailure = { exception ->
                    _uiState.value = _uiState.value.copy(
                        isExporting = false,
                        exportProgress = null,
                        lastExportSuccess = false,
                        lastExportMessage = "导出失败: ${exception.message}"
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
        
        _uiState.value = _uiState.value.copy(
            isImporting = true,
            importProgress = "正在导入歌单..."
        )
        
        viewModelScope.launch {
            val result = manager.importPlaylists(uri)
            
            result.fold(
                onSuccess = { importResult ->
                    val message = buildString {
                        append("导入完成！")
                        append("\n成功导入: ${importResult.importedCount} 个歌单")
                        if (importResult.hasMerged) {
                            append("\n智能合并: ${importResult.mergedCount} 个歌单")
                        }
                        if (importResult.hasSkipped) {
                            append("\n跳过重复: ${importResult.skippedCount} 个歌单")
                        }
                        append("\n备份时间: ${importResult.backupDate}")
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
                        lastImportMessage = "导入失败: ${exception.message}"
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
        
        _uiState.value = _uiState.value.copy(
            isAnalyzing = true,
            analysisProgress = "正在分析差异..."
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
                        lastAnalysisError = "分析失败: ${exception.message}"
                    )
                }
            )
        }
    }
    
    /**
     * 清除导出状态
     */
    fun clearExportStatus() {
        android.util.Log.d("BackupRestoreViewModel", "clearExportStatus called")
        _uiState.value = _uiState.value.copy(
            lastExportSuccess = null,
            lastExportMessage = null
        )
    }
    
    /**
     * 清除导入状态
     */
    fun clearImportStatus() {
        android.util.Log.d("BackupRestoreViewModel", "clearImportStatus called")
        _uiState.value = _uiState.value.copy(
            lastImportSuccess = null,
            lastImportMessage = null
        )
    }
    
    /**
     * 清除分析状态
     */
    fun clearAnalysisStatus() {
        android.util.Log.d("BackupRestoreViewModel", "clearAnalysisStatus called")
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
