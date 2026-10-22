package moe.ouom.neriplayer.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.config.AppConfigImportResult
import moe.ouom.neriplayer.data.config.ConfigFileManager
import moe.ouom.neriplayer.data.config.ConfigImportCancelledException
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.common.logging.NPLogger

class ConfigTransferViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ConfigTransferUiState())
    val uiState: StateFlow<ConfigTransferUiState> = _uiState

    private var configFileManager: ConfigFileManager? = null
    private var strings: ConfigTransferStrings? = null
    private var pendingCompatibilityConfirmation: CompletableDeferred<Boolean>? = null
    private var importJob: Job? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        strings = ConfigTransferStrings.from(appContext)
        if (configFileManager == null) {
            configFileManager = ConfigFileManager(
                context = appContext,
                listenTogetherPreferences = AppContainer.listenTogetherPreferences,
                neteaseCookieRepo = AppContainer.neteaseCookieRepo,
                biliCookieRepo = AppContainer.biliCookieRepo,
                youTubeAuthRepo = AppContainer.youtubeAuthRepo
            )
        }
    }

    fun exportConfig(uri: Uri) {
        val manager = configFileManager ?: return
        val resources = strings ?: return

        _uiState.value = _uiState.value.copy(
            isExporting = true,
            exportProgress = resources.exporting
        )

        viewModelScope.launch {
            val result = manager.exportConfig(uri)
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

    fun importConfig(uri: Uri) {
        if (_uiState.value.isImporting) return
        val manager = configFileManager ?: return
        val resources = strings ?: return

        _uiState.value = _uiState.value.copy(
            isImporting = true,
            importProgress = resources.importing,
            lastImportSuccess = null,
            lastImportMessage = null
        )

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = manager.importConfig(uri, ::awaitCompatibilityStorageConfirmation)
                result.fold(
                    onSuccess = { importResult ->
                        _uiState.value = _uiState.value.copy(
                            isImporting = false,
                            importProgress = null,
                            lastImportSuccess = true,
                            lastImportMessage = resources.importSuccess(importResult),
                            importRequiresActivityRecreate = importResult.requiresActivityRecreate
                        )
                    },
                    onFailure = { exception ->
                        val cancelled = exception is ConfigImportCancelledException
                        _uiState.value = _uiState.value.copy(
                            isImporting = false,
                            importProgress = null,
                            lastImportSuccess = if (cancelled) null else false,
                            lastImportMessage = if (cancelled) null else resources.importFailed(exception.message),
                            importRequiresActivityRecreate = false
                        )
                    }
                )
            } finally {
                if (importJob === currentCoroutineContext().job) {
                    importJob = null
                    clearPendingImportState()
                }
            }
        }
        importJob = job
        job.start()
    }

    private suspend fun awaitCompatibilityStorageConfirmation(): Boolean = withContext(Dispatchers.Main.immediate) {
        val confirmation = CompletableDeferred<Boolean>()
        pendingCompatibilityConfirmation = confirmation
        _uiState.value = _uiState.value.copy(requiresAuthStorageConfirmation = true)
        try {
            confirmation.await()
        } finally {
            if (pendingCompatibilityConfirmation === confirmation) {
                pendingCompatibilityConfirmation = null
                _uiState.value = _uiState.value.copy(requiresAuthStorageConfirmation = false)
            }
        }
    }

    fun confirmAuthStorageImport() {
        pendingCompatibilityConfirmation?.complete(true)
    }

    fun cancelAuthStorageImport() {
        val job = importJob ?: return
        importJob = null
        job.cancel()
        pendingCompatibilityConfirmation?.cancel()
        pendingCompatibilityConfirmation = null
        clearPendingImportState()
    }

    private fun clearPendingImportState() {
        _uiState.value = _uiState.value.copy(
            isImporting = false,
            importProgress = null,
            requiresAuthStorageConfirmation = false
        )
    }

    fun clearExportStatus() {
        NPLogger.d("ConfigTransferViewModel", "clearExportStatus called")
        _uiState.value = _uiState.value.copy(
            lastExportSuccess = null,
            lastExportMessage = null
        )
    }

    fun clearImportStatus() {
        NPLogger.d("ConfigTransferViewModel", "clearImportStatus called")
        _uiState.value = _uiState.value.copy(
            lastImportSuccess = null,
            lastImportMessage = null
        )
    }

    fun consumeImportRecreateRequest() {
        _uiState.value = _uiState.value.copy(importRequiresActivityRecreate = false)
    }

    fun generateBackupFileName(): String {
        return configFileManager?.generateBackupFileName() ?: "neriplayer_config.json"
    }

    fun generateConfigFileName(): String = generateBackupFileName()

    override fun onCleared() {
        cancelAuthStorageImport()
        configFileManager = null
        strings = null
    }

    internal data class ConfigTransferStrings(
        private val context: Context
    ) {
        val exporting: String = context.getString(CoreCommonR.string.settings_config_exporting)
        val importing: String = context.getString(CoreCommonR.string.settings_config_importing)
        private val exportSuccessPrefix: String =
            context.getString(CoreCommonR.string.settings_config_export_success)
        private val exportFailedPrefix: String =
            context.getString(CoreCommonR.string.settings_config_export_failed)
        private val importSuccessPrefix: String =
            context.getString(CoreCommonR.string.settings_config_import_success)
        private val importFailedPrefix: String =
            context.getString(CoreCommonR.string.settings_config_import_failed)

        fun exportSuccess(fileName: String): String = "$exportSuccessPrefix: $fileName"

        fun exportFailed(message: String?): String = "$exportFailedPrefix: ${message.orEmpty()}"

        fun importFailed(message: String?): String = "$importFailedPrefix: ${message.orEmpty()}"

        private fun quantityText(resId: Int, count: Int): String {
            return context.resources.getQuantityString(resId, count, count)
        }

        fun importSuccess(result: AppConfigImportResult): String {
            return buildString {
                append(importSuccessPrefix)
                append('\n')
                append(
                    quantityText(
                        CoreCommonR.plurals.settings_config_import_restored_settings,
                        result.restoredSettingsCount
                    )
                )
                append('\n')
                append(
                    quantityText(
                        CoreCommonR.plurals.settings_config_import_restored_listen_together,
                        result.restoredListenTogetherCount
                    )
                )
                append('\n')
                append(
                    quantityText(
                        CoreCommonR.plurals.settings_config_import_restored_auth,
                        result.restoredAuthCount
                    )
                )
                append('\n')
                append(
                    quantityText(
                        CoreCommonR.plurals.settings_config_import_restored_sync,
                        result.restoredSyncCount
                    )
                )

                if (result.warnings.isNotEmpty()) {
                    append('\n')
                    append('\n')
                    append(context.getString(CoreCommonR.string.settings_config_import_warning_title))
                    result.warnings.forEach { warning ->
                        append('\n')
                        append("- ")
                        append(warning)
                    }
                }

                if (result.requiresActivityRecreate) {
                    append('\n')
                    append('\n')
                    append(context.getString(CoreCommonR.string.settings_config_import_restart_hint))
                }
            }
        }

        companion object {
            fun from(context: Context): ConfigTransferStrings = ConfigTransferStrings(context)
        }
    }
}

data class ConfigTransferUiState(
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val exportProgress: String? = null,
    val importProgress: String? = null,
    val lastExportSuccess: Boolean? = null,
    val lastExportMessage: String? = null,
    val lastImportSuccess: Boolean? = null,
    val lastImportMessage: String? = null,
    val requiresAuthStorageConfirmation: Boolean = false,
    val importRequiresActivityRecreate: Boolean = false
)
