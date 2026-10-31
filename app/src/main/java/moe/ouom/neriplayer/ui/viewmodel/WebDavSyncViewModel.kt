package moe.ouom.neriplayer.ui.viewmodel

import moe.ouom.neriplayer.data.sync.host.createWebDavSyncClient

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicLong
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.i18n.UiText
import moe.ouom.neriplayer.data.model.sync.DEFAULT_SYNC_AUTO_ENABLED
import moe.ouom.neriplayer.api.sync.webdav.WebDavAuthException
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.store.secure.SyncStorageUnavailableException
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
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var syncJob: Job? = null
    private var completionTimeJob: Job? = null
    private var configurationJob: Job? = null
    private var autoSyncJob: Job? = null
    // 清除已进入底层后无法可靠取消，新保存必须等待它结束
    private val configurationWrites = Mutex()
    private val configurationGeneration = AtomicLong()

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
                            reportStorageFailure()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SyncStorageUnavailableException) {
                reportStorageFailure()
            }
        }
    }

    private fun bindSyncOperations(context: Context) {
        if (syncOperation == null) syncOperation = { WebDavSyncManager.getInstance(context).performSync() }
        if (targetSyncOperation == null) {
            targetSyncOperation = { target -> WebDavSyncManager.getInstance(context).performSyncForTarget(target) }
        }
    }

    private suspend fun <T> accessStorage(context: Context, action: (WebDavStorage) -> T): T =
        withContext(ioDispatcher) {
            try {
                val store = storage ?: WebDavStorage(context).also { storage = it }
                if (!store.isPersistent) throw SyncStorageUnavailableException()
                action(store)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw if (error is SyncStorageUnavailableException) error else SyncStorageUnavailableException(error)
            }
        }

    private fun reportStorageFailure() {
        _uiState.update {
            it.copy(errorMessage = feedbackText(CoreCommonR.string.sync_secure_storage_unavailable), successMessage = null)
        }
    }

    private fun beginConfigurationChange(): Long {
        val generation = configurationGeneration.incrementAndGet()
        configurationJob?.cancel()
        return generation
    }

    private suspend fun editConfiguration(
        context: Context,
        generation: Long,
        action: (WebDavStorage) -> Unit
    ): Boolean = configurationWrites.withLock {
        if (generation != configurationGeneration.get()) return@withLock false
        accessStorage(context) { store ->
            if (generation != configurationGeneration.get()) false else {
                action(store)
                true
            }
        }
    }

    private fun loadConfiguration(store: WebDavStorage) {
        _uiState.update {
            it.copy(
                isConfigured = store.isConfigured(),
                autoSyncEnabled = store.isAutoSyncEnabled(),
                serverUrl = store.getServerUrl().orEmpty(),
                basePath = store.getBasePath(),
                username = store.getUsername().orEmpty(),
                lastSyncTime = store.getLastCompletedSyncTime(),
                errorMessage = null
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
        bindSyncOperations(appContext)
        val normalizedServerUrl = serverUrl.trim()
        val normalizedUsername = username.trim()
        val normalizedBasePath = basePath.trim()
        if (normalizedServerUrl.isBlank() || normalizedUsername.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = feedbackText(CoreCommonR.string.webdav_required_fields)
            )
            return
        }

        val generation = beginConfigurationChange()
        _uiState.value = _uiState.value.copy(isValidating = true, errorMessage = null, successMessage = null)

        configurationJob = viewModelScope.launch {
            try {
                accessStorage(appContext) { }
                val result = withContext(ioDispatcher) {
                    createWebDavSyncClient(appContext, normalizedUsername, password)
                        .validateConnection(normalizedServerUrl, normalizedBasePath)
                }
                if (generation != configurationGeneration.get()) return@launch

                if (result.isSuccess) {
                    val saved = editConfiguration(appContext, generation) { store ->
                        store.saveConfiguration(normalizedServerUrl, normalizedUsername, password, normalizedBasePath)
                    }
                    if (!saved || generation != configurationGeneration.get()) return@launch
                    _uiState.update {
                        it.copy(isConfigured = true, serverUrl = normalizedServerUrl, basePath = normalizedBasePath,
                            username = normalizedUsername, successMessage = feedbackText(CoreCommonR.string.webdav_validate_success))
                    }
                } else {
                    _uiState.update {
                        it.copy(errorMessage = feedbackText(CoreCommonR.string.webdav_validate_failed,
                            syncFailureText(result.exceptionOrNull(), CoreCommonR.string.webdav_sync_failed_message)))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SyncStorageUnavailableException) {
                if (generation == configurationGeneration.get()) reportStorageFailure()
            } catch (error: Exception) {
                if (generation == configurationGeneration.get()) _uiState.update {
                    it.copy(errorMessage = feedbackText(
                        CoreCommonR.string.webdav_validate_failed, syncFailureText(error, CoreCommonR.string.webdav_sync_failed_message)
                    ))
                }
            } finally {
                if (generation == configurationGeneration.get()) _uiState.update { it.copy(isValidating = false) }
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
            val result = try {
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
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        reportStorageFailure()
                        return@launch
                    }
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            syncResult = syncResult,
                            lastSyncTime = lastSyncTime,
                            successMessage = syncResultText(syncResult, webDav = true)
                        )
                    }
                    if (_uiState.value.autoSyncEnabled) {
                        try {
                            WebDavSyncWorker.schedulePeriodicSync(appContext)
                        } catch (_: SyncStorageUnavailableException) {
                            reportStorageFailure()
                        }
                    }
                } else {
                    _uiState.value = _uiState.value.copy(isSyncing = false, errorMessage = syncResultText(syncResult, webDav = true))
                }
            } else {
                val error = result.exceptionOrNull()
                if (error is SyncStorageUnavailableException) {
                    reportStorageFailure()
                    return@launch
                }
                val challenge = (error as? SyncProtocolUpgradeRequiredException)?.challenge
                if (challenge != null && onUpgradeRequired != null) {
                    _uiState.value = _uiState.value.copy(isSyncing = false)
                    onUpgradeRequired(challenge)
                    return@launch
                }
                if (error is WebDavSyncInProgressException) {
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        successMessage = feedbackText(CoreCommonR.string.webdav_sync_in_progress)
                    )
                    return@launch
                }
                if (error is WebDavAuthException) {
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        errorMessage = feedbackText(CoreCommonR.string.webdav_auth_failed)
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        errorMessage = feedbackText(
                            CoreCommonR.string.webdav_sync_failed,
                            syncFailureText(error, CoreCommonR.string.webdav_sync_failed_message)
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
        val generation = configurationGeneration.get()
        autoSyncJob?.cancel()
        autoSyncJob = viewModelScope.launch {
            try {
                val saved = editConfiguration(appContext, generation) { it.setAutoSyncEnabled(enabled) }
                if (!saved || generation != configurationGeneration.get()) return@launch
                _uiState.update { it.copy(autoSyncEnabled = enabled) }
                if (enabled) {
                    WebDavSyncWorker.schedulePeriodicSync(appContext)
                } else {
                    WebDavSyncWorker.cancelAllSync(appContext)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SyncStorageUnavailableException) {
                if (generation == configurationGeneration.get()) reportStorageFailure()
            }
        }
    }

    fun clearConfiguration(context: Context) {
        val appContext = context.applicationContext
        val activeSync = syncJob
        syncJob = null
        activeSync?.cancel()
        val generation = beginConfigurationChange()
        autoSyncJob?.cancel()
        _uiState.update {
            it.copy(isSyncing = false, isValidating = false, errorMessage = null, successMessage = null, syncResult = null)
        }
        configurationJob = viewModelScope.launch {
            try {
                val cleared = editConfiguration(appContext, generation) { it.clearAll() }
                if (!cleared || generation != configurationGeneration.get()) return@launch
                WebDavSyncWorker.cancelAllSync(appContext)
                _uiState.update {
                    WebDavSyncUiState(isSyncing = it.isSyncing, syncResult = it.syncResult,
                        successMessage = it.successMessage, errorMessage = it.errorMessage)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SyncStorageUnavailableException) {
                if (generation == configurationGeneration.get()) reportStorageFailure()
            }
        }
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
    val successMessage: UiText? = null,
    val errorMessage: UiText? = null
)
