package moe.ouom.neriplayer.ui.viewmodel

import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage

import moe.ouom.neriplayer.api.sync.github.GitHubApiException
import moe.ouom.neriplayer.api.sync.github.TokenExpiredException

import moe.ouom.neriplayer.data.sync.host.createGitHubSyncClient

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
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.data.model.sync.DEFAULT_SYNC_AUTO_ENABLED
import moe.ouom.neriplayer.data.sync.github.*
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException
import moe.ouom.neriplayer.data.sync.store.secure.SyncStorageUnavailableException

/**
 * GitHub 同步 ViewModel
 */
class GitHubSyncViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(GitHubSyncUiState())
    val uiState: StateFlow<GitHubSyncUiState> = _uiState

    private var storage: SecureTokenStorage? = null
    internal var syncOperation: (suspend () -> Result<SyncResult>)? = null
    internal var targetSyncOperation: (suspend (String) -> Result<SyncResult>)? = null
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var syncJob: Job? = null
    private var completionTimeJob: Job? = null
    private var configurationJob: Job? = null
    private var autoSyncJob: Job? = null
    @Volatile private var configurationGeneration = 0L

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        bindSyncOperations(appContext)
        viewModelScope.launch {
            try {
                val store = accessStorage(appContext) {
                    loadConfiguration(it)
                    it
                }
                if (completionTimeJob?.isActive != true) {
                    completionTimeJob = viewModelScope.launch {
                        try {
                            store.observeLastCompletedSyncTime().collect { timestamp ->
                                _uiState.update { it.copy(lastSyncTime = timestamp) }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            _uiState.update { it.copy(errorMessage = storageUnavailableText()) }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SyncStorageUnavailableException) {
                _uiState.update { it.copy(errorMessage = storageUnavailableText()) }
            }
        }
    }

    private fun bindSyncOperations(context: Context) {
        if (syncOperation == null) syncOperation = {
            withContext(ioDispatcher) { GitHubSyncManager.getInstance(context).performSync() }
        }
        if (targetSyncOperation == null) {
            targetSyncOperation = { target ->
                withContext(ioDispatcher) { GitHubSyncManager.getInstance(context).performSyncForTarget(target) }
            }
        }
    }

    private suspend fun <T> accessStorage(context: Context, action: (SecureTokenStorage) -> T): T =
        withContext(ioDispatcher) {
            try {
                val store = storage ?: SecureTokenStorage(context).also { storage = it }
                if (!store.isPersistent) throw SyncStorageUnavailableException()
                action(store)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw if (error is SyncStorageUnavailableException) error else SyncStorageUnavailableException(error)
            }
        }

    private fun storageUnavailableText(): UiText = feedbackText(CoreCommonR.string.sync_secure_storage_unavailable)

    private fun loadConfiguration(store: SecureTokenStorage) {
        val configured = store.isConfigured()
        val autoSyncEnabled = store.isAutoSyncEnabled()
        val repoOwner = store.getRepoOwner().orEmpty()
        val repoName = store.getRepoName().orEmpty()
        val lastSyncTime = store.getLastCompletedSyncTime()
        _uiState.update {
            it.copy(isConfigured = configured, autoSyncEnabled = autoSyncEnabled,
                repoOwner = repoOwner, repoName = repoName, lastSyncTime = lastSyncTime)
        }
    }

    fun resetTokenValidation() {
        configurationGeneration++
        configurationJob?.cancel()
        configurationJob = null
        _uiState.update {
            it.copy(tokenValid = false, isValidating = false, isCreatingRepo = false, isCheckingRepo = false,
                username = "", errorMessage = null, successMessage = null)
        }
    }

    fun validateToken(context: Context, token: String) {
        val appContext = context.applicationContext
        bindSyncOperations(appContext)
        val normalizedToken = token.trim()
        resetTokenValidation()
        if (normalizedToken.isBlank()) {
            _uiState.update { it.copy(errorMessage = feedbackText(CoreCommonR.string.github_token_required)) }
            return
        }
        val generation = configurationGeneration
        _uiState.update { it.copy(isValidating = true) }
        configurationJob = viewModelScope.launch {
            try {
                accessStorage(appContext) { }
                val result = withContext(ioDispatcher) { createGitHubSyncClient(appContext, normalizedToken).validateToken() }
                coroutineContext.ensureActive()
                if (generation != configurationGeneration) return@launch
                if (result.isSuccess) {
                    val username = result.getOrThrow()
                    accessStorage(appContext) {
                        it.saveToken(normalizedToken)
                        loadConfiguration(it)
                    }
                    coroutineContext.ensureActive()
                    if (generation == configurationGeneration) _uiState.update {
                        it.copy(tokenValid = true, username = username,
                            successMessage = feedbackText(CoreCommonR.string.github_token_verify_success, username))
                    }
                } else {
                    _uiState.update { it.copy(errorMessage = feedbackText(
                        CoreCommonR.string.github_token_verify_failed,
                        syncFailureText(result.exceptionOrNull(), CoreCommonR.string.github_sync_failed_message)
                    )) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == configurationGeneration) _uiState.update {
                    it.copy(errorMessage = configurationFailure(error, CoreCommonR.string.github_token_verify_failed))
                }
            } finally {
                if (generation == configurationGeneration) _uiState.update { it.copy(isValidating = false) }
            }
        }
    }

    private fun configurationFailure(error: Throwable, @StringRes message: Int): UiText =
        if (error is SyncStorageUnavailableException) storageUnavailableText()
        else feedbackText(message, syncFailureText(error, CoreCommonR.string.github_sync_failed_message))

    private suspend fun authenticationFailed(
        context: Context,
        generation: Long,
        metadataGuard: ((() -> Unit) -> Boolean)?
    ) {
        try {
            accessStorage(context) { store ->
                var updated = false
                val pause = {
                    if (generation == configurationGeneration) {
                        store.setAutoSyncEnabled(false)
                        updated = true
                    }
                }
                if (metadataGuard == null) pause() else metadataGuard(pause)
                if (updated) {
                    val finish = {
                        if (generation == configurationGeneration) {
                            GitHubSyncWorker.cancelAllSync(context)
                            _uiState.update { state ->
                                if (generation != configurationGeneration) state
                                else state.copy(isSyncing = false, tokenValid = false, autoSyncEnabled = false,
                                    successMessage = null, errorMessage = feedbackText(CoreCommonR.string.github_token_expired))
                            }
                        }
                    }
                    if (metadataGuard == null) finish() else metadataGuard(finish)
                }
            }
        } catch (_: SyncStorageUnavailableException) {
            _uiState.update { state ->
                if (generation != configurationGeneration) state
                else state.copy(isSyncing = false, errorMessage = storageUnavailableText())
            }
        }
    }

    fun createRepository(context: Context, repoName: String) {
        if (configurationJob?.isActive == true) return
        val appContext = context.applicationContext
        val generation = configurationGeneration
        _uiState.update { it.copy(isCreatingRepo = true, errorMessage = null, successMessage = null) }
        configurationJob = viewModelScope.launch {
            try {
                val (metadataGuard, token) = accessStorage(appContext) {
                    it.captureSyncMetadataGuard() to it.getToken()
                }
                if (token.isNullOrBlank()) {
                    _uiState.update { it.copy(tokenValid = false, errorMessage = feedbackText(CoreCommonR.string.github_token_required)) }
                    return@launch
                }
                val result = withContext(ioDispatcher) { createGitHubSyncClient(appContext, token).createRepository(repoName) }
                coroutineContext.ensureActive()
                if (generation != configurationGeneration) return@launch
                if (result.isSuccess) {
                    val repo = result.getOrThrow()
                    accessStorage(appContext) {
                        it.saveRepository(repo.fullName.substringBefore('/'), repo.name)
                        loadConfiguration(it)
                    }
                    coroutineContext.ensureActive()
                    if (generation == configurationGeneration) _uiState.update {
                        it.copy(successMessage = feedbackText(CoreCommonR.string.github_repo_create_success, repo.fullName))
                    }
                } else {
                    val error = result.exceptionOrNull()
                    if (error is TokenExpiredException) authenticationFailed(appContext, generation, metadataGuard)
                    else _uiState.update { it.copy(errorMessage = configurationFailure(
                        error ?: IllegalStateException(), CoreCommonR.string.github_repo_create_failed
                    )) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == configurationGeneration) _uiState.update {
                    it.copy(errorMessage = configurationFailure(error, CoreCommonR.string.github_repo_create_failed))
                }
            } finally {
                if (generation == configurationGeneration) _uiState.update { it.copy(isCreatingRepo = false) }
            }
        }
    }

    fun useExistingRepository(context: Context, fullRepoName: String) {
        if (configurationJob?.isActive == true) return
        val parts = fullRepoName.trim().split("/")
        if (parts.size != 2 || parts.any(String::isBlank)) {
            _uiState.update { it.copy(errorMessage = feedbackText(CoreCommonR.string.github_repo_format_error)) }
            return
        }
        val appContext = context.applicationContext
        val owner = parts[0]
        val repo = parts[1]
        val generation = configurationGeneration
        _uiState.update { it.copy(isCheckingRepo = true, errorMessage = null, successMessage = null) }
        configurationJob = viewModelScope.launch {
            try {
                val (metadataGuard, token) = accessStorage(appContext) {
                    it.captureSyncMetadataGuard() to it.getToken()
                }
                if (token.isNullOrBlank()) {
                    _uiState.update { it.copy(tokenValid = false, errorMessage = feedbackText(CoreCommonR.string.github_token_required)) }
                    return@launch
                }
                val result = withContext(ioDispatcher) { createGitHubSyncClient(appContext, token).checkRepository(owner, repo) }
                coroutineContext.ensureActive()
                if (generation != configurationGeneration) return@launch
                if (result.isSuccess) {
                    accessStorage(appContext) {
                        it.saveRepository(owner, repo)
                        loadConfiguration(it)
                    }
                    coroutineContext.ensureActive()
                    if (generation == configurationGeneration) _uiState.update {
                        it.copy(successMessage = feedbackText(CoreCommonR.string.github_repo_config_success, "$owner/$repo"))
                    }
                } else {
                    val error = result.exceptionOrNull()
                    if (error is TokenExpiredException) {
                        authenticationFailed(appContext, generation, metadataGuard)
                    } else _uiState.update {
                        it.copy(errorMessage = if (error is GitHubApiException && error.statusCode == 404)
                            feedbackText(CoreCommonR.string.github_repo_not_found, "$owner/$repo")
                        else configurationFailure(error ?: IllegalStateException(), CoreCommonR.string.github_sync_failed))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == configurationGeneration) _uiState.update {
                    it.copy(errorMessage = configurationFailure(error, CoreCommonR.string.github_sync_failed))
                }
            } finally {
                if (generation == configurationGeneration) _uiState.update { it.copy(isCheckingRepo = false) }
            }
        }
    }

    /**
     * 执行同步
     */
    fun performSyncForTarget(
        context: Context,
        targetId: String,
        onFinished: () -> Unit = {},
        onUpgradeRequired: ((SyncProtocolUpgradeChallenge) -> Unit)? = null
    ) {
        val operation = targetSyncOperation ?: return
        if (targetId.isBlank()) return
        startSync(context, { operation(targetId) }, onUpgradeRequired, onFinished)
    }

    fun performSync(context: Context, onUpgradeRequired: ((SyncProtocolUpgradeChallenge) -> Unit)? = null) {
        val operation = syncOperation ?: return
        startSync(context, operation, onUpgradeRequired)
    }

    private fun startSync(
        context: Context,
        operation: suspend () -> Result<SyncResult>,
        onUpgradeRequired: ((SyncProtocolUpgradeChallenge) -> Unit)?,
        onFinished: () -> Unit = {}
    ) {
        if (syncJob?.isActive == true) return
        val syncConfigurationGeneration = configurationGeneration
        val appContext = context.applicationContext
        _uiState.value = _uiState.value.copy(isSyncing = true, errorMessage = null, syncResult = null, successMessage = null)

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var metadataGuard: ((() -> Unit) -> Boolean)? = null
            val result = try {
                if (storage != null) metadataGuard = accessStorage(appContext) { it.captureSyncMetadataGuard() }
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            coroutineContext.ensureActive()
            if (syncJob !== coroutineContext[Job]) return@launch

            if (result.isSuccess) {
                val syncResult = result.getOrNull()!!
                if (syncResult.success) {
                    val lastSyncTime = try {
                        if (storage == null) _uiState.value.lastSyncTime
                        else accessStorage(appContext) { it.getLastCompletedSyncTime() }
                    } catch (_: SyncStorageUnavailableException) {
                        _uiState.update { it.copy(isSyncing = false, errorMessage = storageUnavailableText()) }
                        return@launch
                    }
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            syncResult = syncResult,
                            lastSyncTime = lastSyncTime,
                            successMessage = syncResultText(syncResult, webDav = false)
                        )
                    }

                    if (_uiState.value.autoSyncEnabled) {
                        GitHubSyncWorker.schedulePeriodicSync(appContext)
                    }
                } else {
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        errorMessage = syncResultText(syncResult, webDav = false)
                    )
                }
            } else {
                val error = result.exceptionOrNull()
                val challenge = (error as? SyncProtocolUpgradeRequiredException)?.challenge
                if (challenge != null && onUpgradeRequired != null) {
                    _uiState.value = _uiState.value.copy(isSyncing = false)
                    onUpgradeRequired(challenge)
                    return@launch
                }
                if (error is GitHubSyncInProgressException) {
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        successMessage = feedbackText(CoreCommonR.string.github_sync_in_progress)
                    )
                    return@launch
                }
                if (error is TokenExpiredException) {
                    if (syncConfigurationGeneration == configurationGeneration) {
                        authenticationFailed(appContext, syncConfigurationGeneration, metadataGuard)
                    }
                    else _uiState.update { it.copy(isSyncing = false) }
                } else {
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        errorMessage = if (error is SyncStorageUnavailableException) storageUnavailableText() else feedbackText(
                            CoreCommonR.string.github_sync_failed,
                            syncFailureText(error, CoreCommonR.string.github_sync_failed_message)
                        )
                    )
                }
            }
        }
        syncJob = job
        job.invokeOnCompletion {
            if (syncJob === job) {
                syncJob = null
                _uiState.value = _uiState.value.copy(isSyncing = false)
                onFinished()
            }
        }
        job.start()
    }

    /**
     * 切换自动同步
     */
    fun toggleAutoSync(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch {
            try {
                accessStorage(appContext) { it.setAutoSyncEnabled(enabled) }
                _uiState.update { it.copy(autoSyncEnabled = enabled) }
                if (enabled) GitHubSyncWorker.schedulePeriodicSync(appContext)
                else GitHubSyncWorker.cancelAllSync(appContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SyncStorageUnavailableException) {
                _uiState.update { it.copy(errorMessage = storageUnavailableText()) }
            }
        }
    }

    /**
     * 清除配置
     */
    fun clearConfiguration(context: Context) {
        val appContext = context.applicationContext
        val activeSync = syncJob
        syncJob = null
        activeSync?.cancel()
        configurationGeneration++
        configurationJob?.cancel()
        autoSyncJob?.cancel()
        val generation = configurationGeneration
        _uiState.update {
            it.copy(isSyncing = false, isValidating = false, isCreatingRepo = false, isCheckingRepo = false,
                errorMessage = null, successMessage = null, syncResult = null)
        }
        configurationJob = viewModelScope.launch {
            try {
                accessStorage(appContext) { it.clearAll() }
                if (generation == configurationGeneration) {
                    GitHubSyncWorker.cancelAllSync(appContext)
                    _uiState.update {
                        GitHubSyncUiState(isSyncing = it.isSyncing, syncResult = it.syncResult,
                            successMessage = it.successMessage, errorMessage = it.errorMessage)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SyncStorageUnavailableException) {
                if (generation == configurationGeneration) _uiState.update { it.copy(errorMessage = storageUnavailableText()) }
            }
        }
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
    val autoSyncEnabled: Boolean = DEFAULT_SYNC_AUTO_ENABLED,
    val username: String = "",
    val repoOwner: String = "",
    val repoName: String = "",
    val lastSyncTime: Long = 0L,
    val syncResult: SyncResult? = null,
    val successMessage: UiText? = null,
    val errorMessage: UiText? = null
)
