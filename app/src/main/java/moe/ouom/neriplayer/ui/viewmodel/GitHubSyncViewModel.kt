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
 * File: moe.ouom.neriplayer.ui.viewmodel/GitHubSyncViewModel
 * Created: 2025/1/7
 */

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.github.*

/**
 * GitHub 同步 ViewModel
 */
class GitHubSyncViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(GitHubSyncUiState())
    val uiState: StateFlow<GitHubSyncUiState> = _uiState

    private var storage: SecureTokenStorage? = null
    private var syncManager: GitHubSyncManager? = null

    fun initialize(context: Context) {
        if (storage == null) {
            storage = SecureTokenStorage(context)
            syncManager = GitHubSyncManager.getInstance(context)
            loadConfiguration()
        }
    }

    /**
     * 加载配置
     */
    private fun loadConfiguration() {
        val store = storage ?: return
        _uiState.value = _uiState.value.copy(
            isConfigured = store.isConfigured(),
            autoSyncEnabled = store.isAutoSyncEnabled(),
            repoOwner = store.getRepoOwner() ?: "",
            repoName = store.getRepoName() ?: "",
            lastSyncTime = store.getLastSyncTime()
        )
    }

    /**
     * 验证Token
     */
    fun validateToken(context: Context, token: String) {
        _uiState.value = _uiState.value.copy(isValidating = true, errorMessage = null)

        viewModelScope.launch {
            val apiClient = GitHubApiClient(token)
            val result = apiClient.validateToken()

            if (result.isSuccess) {
                val username = result.getOrNull() ?: "Unknown"
                storage?.saveToken(token)
                _uiState.value = _uiState.value.copy(
                    isValidating = false,
                    tokenValid = true,
                    username = username,
                    successMessage = "Token验证成功: $username"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isValidating = false,
                    tokenValid = false,
                    errorMessage = "Token验证失败: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    /**
     * 创建仓库
     */
    fun createRepository(context: Context, repoName: String) {
        val token = storage?.getToken()
        if (token == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "请先配置Token")
            return
        }

        _uiState.value = _uiState.value.copy(isCreatingRepo = true, errorMessage = null)

        viewModelScope.launch {
            val apiClient = GitHubApiClient(token)
            val result = apiClient.createRepository(repoName)

            if (result.isSuccess) {
                val repo = result.getOrNull()!!
                storage?.saveRepository(repo.fullName.split("/")[0], repo.name)
                _uiState.value = _uiState.value.copy(
                    isCreatingRepo = false,
                    repoOwner = repo.fullName.split("/")[0],
                    repoName = repo.name,
                    isConfigured = true,
                    successMessage = "仓库创建成功: ${repo.fullName}"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isCreatingRepo = false,
                    errorMessage = "仓库创建失败: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    /**
     * 使用现有仓库
     */
    fun useExistingRepository(context: Context, fullRepoName: String) {
        val token = storage?.getToken()
        if (token == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "请先配置Token")
            return
        }

        val parts = fullRepoName.split("/")
        if (parts.size != 2) {
            _uiState.value = _uiState.value.copy(errorMessage = "仓库格式错误,应为: owner/repo")
            return
        }

        val owner = parts[0]
        val repo = parts[1]

        _uiState.value = _uiState.value.copy(isCheckingRepo = true, errorMessage = null)

        viewModelScope.launch {
            val apiClient = GitHubApiClient(token)
            val result = apiClient.checkRepository(owner, repo)

            if (result.isSuccess) {
                storage?.saveRepository(owner, repo)
                _uiState.value = _uiState.value.copy(
                    isCheckingRepo = false,
                    repoOwner = owner,
                    repoName = repo,
                    isConfigured = true,
                    successMessage = "仓库配置成功: $fullRepoName"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isCheckingRepo = false,
                    errorMessage = "仓库不存在或无权访问: ${result.exceptionOrNull()?.message}"
                )
            }
        }
    }

    /**
     * 执行同步
     */
    fun performSync(context: Context) {
        _uiState.value = _uiState.value.copy(isSyncing = true, errorMessage = null, syncResult = null)

        viewModelScope.launch {
            val manager = syncManager ?: return@launch
            val result = manager.performSync()

            if (result.isSuccess) {
                val syncResult = result.getOrNull()!!
                storage?.saveLastSyncTime(System.currentTimeMillis())
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    syncResult = syncResult,
                    lastSyncTime = System.currentTimeMillis(),
                    successMessage = syncResult.message
                )

                // 启动定期同步
                if (_uiState.value.autoSyncEnabled) {
                    GitHubSyncWorker.schedulePeriodicSync(context)
                }
            } else {
                val error = result.exceptionOrNull()
                // 检查是否是Token过期
                if (error is TokenExpiredException) {
                    // Token过期，清除配置
                    clearConfiguration(context)
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        errorMessage = "GitHub Token 已过期，请重新配置"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        errorMessage = "同步失败: ${error?.message}"
                    )
                }
            }
        }
    }

    /**
     * 切换自动同步
     */
    fun toggleAutoSync(context: Context, enabled: Boolean) {
        storage?.setAutoSyncEnabled(enabled)
        _uiState.value = _uiState.value.copy(autoSyncEnabled = enabled)

        if (enabled) {
            GitHubSyncWorker.schedulePeriodicSync(context)
        } else {
            GitHubSyncWorker.cancelAllSync(context)
        }
    }

    /**
     * 清除配置
     */
    fun clearConfiguration(context: Context) {
        storage?.clearAll()
        GitHubSyncWorker.cancelAllSync(context)
        _uiState.value = GitHubSyncUiState()
    }

    /**
     * 清除消息
     */
    fun clearMessages() {
        _uiState.value = _uiState.value.copy(
            successMessage = null,
            errorMessage = null
        )
    }
}

/**
 * GitHub 同步 UI 状态
 */
data class GitHubSyncUiState(
    val isConfigured: Boolean = false,
    val isValidating: Boolean = false,
    val isCreatingRepo: Boolean = false,
    val isCheckingRepo: Boolean = false,
    val isSyncing: Boolean = false,
    val tokenValid: Boolean = false,
    val autoSyncEnabled: Boolean = false,
    val username: String = "",
    val repoOwner: String = "",
    val repoName: String = "",
    val lastSyncTime: Long = 0L,
    val syncResult: SyncResult? = null,
    val successMessage: String? = null,
    val errorMessage: String? = null
)
