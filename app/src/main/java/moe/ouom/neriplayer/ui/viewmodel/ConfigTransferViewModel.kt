package moe.ouom.neriplayer.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.config.AppConfigImportResult
import moe.ouom.neriplayer.data.config.ConfigFileManager
import moe.ouom.neriplayer.util.NPLogger

class ConfigTransferViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ConfigTransferUiState())
    val uiState: StateFlow<ConfigTransferUiState> = _uiState

    private var configFileManager: ConfigFileManager? = null
    private var strings: ConfigTransferStrings? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        strings = ConfigTransferStrings.from(appContext)
        if (configFileManager == null) {
            configFileManager = ConfigFileManager(appContext)
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
        val manager = configFileManager ?: return
        val resources = strings ?: return

        _uiState.value = _uiState.value.copy(
            isImporting = true,
            importProgress = resources.importing
        )

        viewModelScope.launch {
            val result = manager.importConfig(uri)
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
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        importProgress = null,
                        lastImportSuccess = false,
                        lastImportMessage = resources.importFailed(exception.message),
                        importRequiresActivityRecreate = false
                    )
                }
            )
        }
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
        super.onCleared()
        configFileManager = null
        strings = null
    }

    private data class ConfigTransferStrings(
        private val context: Context
    ) {
        val exporting: String = context.getString(R.string.settings_config_exporting)
        val importing: String = context.getString(R.string.settings_config_importing)
        private val exportSuccessPrefix: String =
            context.getString(R.string.settings_config_export_success)
        private val exportFailedPrefix: String =
            context.getString(R.string.settings_config_export_failed)
        private val importSuccessPrefix: String =
            context.getString(R.string.settings_config_import_success)
        private val importFailedPrefix: String =
            context.getString(R.string.settings_config_import_failed)

        fun exportSuccess(fileName: String): String = "$exportSuccessPrefix: $fileName"

        fun exportFailed(message: String?): String = "$exportFailedPrefix: ${message.orEmpty()}"

        fun importFailed(message: String?): String = "$importFailedPrefix: ${message.orEmpty()}"

        fun importSuccess(result: AppConfigImportResult): String {
            return buildString {
                append(importSuccessPrefix)
                append('\n')
                append(
                    context.getString(
                        R.string.settings_config_import_restored_settings,
                        result.restoredSettingsCount
                    )
                )
                append('\n')
                append(
                    context.getString(
                        R.string.settings_config_import_restored_listen_together,
                        result.restoredListenTogetherCount
                    )
                )
                append('\n')
                append(
                    context.getString(
                        R.string.settings_config_import_restored_auth,
                        result.restoredAuthCount
                    )
                )
                append('\n')
                append(
                    context.getString(
                        R.string.settings_config_import_restored_sync,
                        result.restoredSyncCount
                    )
                )

                if (result.warnings.isNotEmpty()) {
                    append('\n')
                    append('\n')
                    append(context.getString(R.string.settings_config_import_warning_title))
                    result.warnings.forEach { warning ->
                        append('\n')
                        append("- ")
                        append(warning)
                    }
                }

                if (result.requiresActivityRecreate) {
                    append('\n')
                    append('\n')
                    append(context.getString(R.string.settings_config_import_restart_hint))
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
    val importRequiresActivityRecreate: Boolean = false
)
