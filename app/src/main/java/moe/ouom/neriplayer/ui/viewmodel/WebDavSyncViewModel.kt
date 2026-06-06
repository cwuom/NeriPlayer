package moe.ouom.neriplayer.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.data.sync.webdav.WebDavAuthException
import moe.ouom.neriplayer.data.sync.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncInProgressException
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncManager
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.data.sync.github.SyncResult

class WebDavSyncViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(WebDavSyncUiState())
    val uiState: StateFlow<WebDavSyncUiState> = _uiState

    private var storage: WebDavStorage? = null
    private var syncManager: WebDavSyncManager? = null

    fun initialize(context: Context) {
        if (storage == null) {
            storage = WebDavStorage(context)
            syncManager = WebDavSyncManager.getInstance(context)
            loadConfiguration()
        }
    }

    private fun loadConfiguration() {
        val store = storage ?: return
        _uiState.value = _uiState.value.copy(
            isConfigured = store.isConfigured(),
            autoSyncEnabled = store.isAutoSyncEnabled(),
            serverUrl = store.getServerUrl().orEmpty(),
            basePath = store.getBasePath(),
            username = store.getUsername().orEmpty(),
            lastSyncTime = store.getLastSyncTime()
        )
    }

    fun validateAndSaveConfiguration(
        context: Context,
        serverUrl: String,
        username: String,
        password: String,
        basePath: String
    ) {
        val normalizedServerUrl = serverUrl.trim()
        val normalizedUsername = username.trim()
        val normalizedBasePath = basePath.trim()
        if (normalizedServerUrl.isBlank() || normalizedUsername.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = context.getString(R.string.webdav_required_fields)
            )
            return
        }

        _uiState.value = _uiState.value.copy(isValidating = true, errorMessage = null)

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                WebDavApiClient(context, normalizedUsername, password)
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
                    successMessage = context.getString(R.string.webdav_validate_success)
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isValidating = false,
                    errorMessage = context.getString(
                        R.string.webdav_validate_failed,
                        result.exceptionOrNull()?.message
                            ?: context.getString(R.string.webdav_sync_failed_message)
                    )
                )
            }
        }
    }

    fun performSync(context: Context) {
        _uiState.value = _uiState.value.copy(isSyncing = true, errorMessage = null, syncResult = null)

        viewModelScope.launch {
            val manager = syncManager ?: return@launch
            val result = manager.performSync()
            if (result.isSuccess) {
                val syncResult = result.getOrNull()!!
                val lastSyncTime = storage?.getLastSyncTime() ?: _uiState.value.lastSyncTime
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    syncResult = syncResult,
                    lastSyncTime = lastSyncTime,
                    successMessage = syncResult.message
                )
                if (_uiState.value.autoSyncEnabled) {
                    WebDavSyncWorker.schedulePeriodicSync(context)
                }
            } else {
                val error = result.exceptionOrNull()
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
                        errorMessage = context.getString(R.string.webdav_auth_failed)
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        errorMessage = context.getString(
                            R.string.webdav_sync_failed,
                            error?.message ?: context.getString(R.string.webdav_sync_failed_message)
                        )
                    )
                }
            }
        }
    }

    fun toggleAutoSync(context: Context, enabled: Boolean) {
        storage?.setAutoSyncEnabled(enabled)
        _uiState.value = _uiState.value.copy(autoSyncEnabled = enabled)
        if (enabled) {
            WebDavSyncWorker.schedulePeriodicSync(context)
        } else {
            WebDavSyncWorker.cancelAllSync(context)
        }
    }

    fun clearConfiguration(context: Context) {
        storage?.clearAll()
        WebDavSyncWorker.cancelAllSync(context)
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
    val autoSyncEnabled: Boolean = false,
    val serverUrl: String = "",
    val basePath: String = "",
    val username: String = "",
    val lastSyncTime: Long = 0L,
    val syncResult: SyncResult? = null,
    val successMessage: String? = null,
    val errorMessage: String? = null
)
