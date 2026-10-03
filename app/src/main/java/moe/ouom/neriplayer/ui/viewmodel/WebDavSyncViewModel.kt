package moe.ouom.neriplayer.ui.viewmodel

import moe.ouom.neriplayer.data.sync.host.createWebDavSyncClient

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.sync.DEFAULT_SYNC_AUTO_ENABLED
import moe.ouom.neriplayer.api.sync.webdav.WebDavAuthException
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncInProgressException
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncManager
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeChallenge
import moe.ouom.neriplayer.data.sync.runtime.SyncProtocolUpgradeRequiredException

class WebDavSyncViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(WebDavSyncUiState())
    val uiState: StateFlow<WebDavSyncUiState> = _uiState

    private var storage: WebDavStorage? = null
    internal var syncOperation: (suspend () -> Result<SyncResult>)? = null
    internal var targetSyncOperation: (suspend (String) -> Result<SyncResult>)? = null
    private var syncJob: Job? = null
    private var completionTimeJob: Job? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (storage == null) {
                    storage = WebDavStorage(appContext)
                    val manager = WebDavSyncManager.getInstance(appContext)
                    syncOperation = manager::performSync
                    targetSyncOperation = manager::performSyncForTarget
                }
                loadConfiguration()
            }
            if (completionTimeJob?.isActive != true) {
                val store = storage ?: return@launch
                completionTimeJob = viewModelScope.launch {
                    store.observeLastCompletedSyncTime().collect { timestamp ->
                        _uiState.update { it.copy(lastSyncTime = timestamp) }
                    }
                }
            }
        }
    }

    private fun loadConfiguration() {
        val store = storage ?: return
        _uiState.update {
            it.copy(
                isConfigured = store.isConfigured(),
                autoSyncEnabled = store.isAutoSyncEnabled(),
                serverUrl = store.getServerUrl().orEmpty(),
                basePath = store.getBasePath(),
                username = store.getUsername().orEmpty(),
                lastSyncTime = store.getLastCompletedSyncTime()
            )
        }
    }

    fun validateAndSaveConfiguration(
        context: Context,
        serverUrl: String,
        username: String,
        password: String,
        basePath: String
    ) {
        val appContext = context.applicationContext
        val normalizedServerUrl = serverUrl.trim()
        val normalizedUsername = username.trim()
        val normalizedBasePath = basePath.trim()
        if (normalizedServerUrl.isBlank() || normalizedUsername.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = appContext.getString(CoreCommonR.string.webdav_required_fields)
            )
            return
        }

        _uiState.value = _uiState.value.copy(isValidating = true, errorMessage = null)

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                createWebDavSyncClient(appContext, normalizedUsername, password)
                    .validateConnection(normalizedServerUrl, normalizedBasePath)
            }

            if (result.isSuccess) {
                storage?.saveConfiguration(
                    serverUrl = normalizedServerUrl,
                    username = normalizedUsername,
                    password = password,
                    basePath = normalizedBasePath
                )
                _uiState.value = _uiState.value.copy(
                    isConfigured = true,
                    isValidating = false,
                    serverUrl = normalizedServerUrl,
                    basePath = normalizedBasePath,
                    username = normalizedUsername,
                    successMessage = appContext.getString(CoreCommonR.string.webdav_validate_success)
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isValidating = false,
                    errorMessage = appContext.getString(
                        CoreCommonR.string.webdav_validate_failed,
                        result.exceptionOrNull()?.message
                            ?: appContext.getString(CoreCommonR.string.webdav_sync_failed_message)
                    )
                )
            }
        }
    }

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
        val appContext = context.applicationContext
        _uiState.value = _uiState.value.copy(isSyncing = true, errorMessage = null, syncResult = null, successMessage = null)

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val result = operation()
            coroutineContext.ensureActive()
            if (syncJob !== coroutineContext[Job]) return@launch
            if (result.isSuccess) {
                val syncResult = result.getOrNull()!!
                if (syncResult.success) {
                    val lastSyncTime = storage?.getLastCompletedSyncTime() ?: _uiState.value.lastSyncTime
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            syncResult = syncResult,
                            lastSyncTime = lastSyncTime,
                            successMessage = syncResult.message
                        )
                    }
                    if (_uiState.value.autoSyncEnabled) {
                        WebDavSyncWorker.schedulePeriodicSync(appContext)
                    }
                } else {
                    _uiState.value = _uiState.value.copy(isSyncing = false, errorMessage = syncResult.message)
                }
            } else {
                val error = result.exceptionOrNull()
                val challenge = (error as? SyncProtocolUpgradeRequiredException)?.challenge
                if (challenge != null && onUpgradeRequired != null) {
                    _uiState.value = _uiState.value.copy(isSyncing = false)
                    onUpgradeRequired(challenge)
                    return@launch
                }
                if (error is WebDavSyncInProgressException) {
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        successMessage = error.message
                    )
                    return@launch
                }
                if (error is WebDavAuthException) {
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        errorMessage = appContext.getString(CoreCommonR.string.webdav_auth_failed)
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        errorMessage = appContext.getString(
                            CoreCommonR.string.webdav_sync_failed,
                            error?.message ?: appContext.getString(CoreCommonR.string.webdav_sync_failed_message)
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

    fun toggleAutoSync(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        storage?.setAutoSyncEnabled(enabled)
        _uiState.value = _uiState.value.copy(autoSyncEnabled = enabled)
        if (enabled) {
            WebDavSyncWorker.schedulePeriodicSync(appContext)
        } else {
            WebDavSyncWorker.cancelAllSync(appContext)
        }
    }

    fun clearConfiguration(context: Context) {
        val appContext = context.applicationContext
        val activeSync = syncJob
        syncJob = null
        activeSync?.cancel()
        storage?.clearAll()
        WebDavSyncWorker.cancelAllSync(appContext)
        _uiState.value = WebDavSyncUiState()
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(successMessage = null, errorMessage = null)
    }
}

data class WebDavSyncUiState(
    val isConfigured: Boolean = false,
    val isValidating: Boolean = false,
    val isSyncing: Boolean = false,
    val autoSyncEnabled: Boolean = DEFAULT_SYNC_AUTO_ENABLED,
    val serverUrl: String = "",
    val basePath: String = "",
    val username: String = "",
    val lastSyncTime: Long = 0L,
    val syncResult: SyncResult? = null,
    val successMessage: String? = null,
    val errorMessage: String? = null
)
