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
import moe.ouom.neriplayer.data.model.config.AppConfigImportWarning
import moe.ouom.neriplayer.data.config.ConfigFileManager
import moe.ouom.neriplayer.data.config.ConfigImportCancelledException
import moe.ouom.neriplayer.data.config.ConfigExportOptions
import moe.ouom.neriplayer.data.config.ConfigBackupPasswordException
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.ui.i18n.UiText

class ConfigTransferViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ConfigTransferUiState())
    val uiState: StateFlow<ConfigTransferUiState> = _uiState

    private var configFileManager: ConfigFileManager? = null
    private var strings: ConfigTransferStrings? = null
    private var pendingCompatibilityConfirmation: CompletableDeferred<Boolean>? = null
    private var pendingPassword: CompletableDeferred<CharArray?>? = null
    private var exportOptions: ConfigExportOptions? = null
    private var exportPassword: CharArray? = null
    private var importJob: Job? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        strings = ConfigTransferStrings.create()
        if (configFileManager == null) {
            configFileManager = ConfigFileManager(
                context = appContext,
                listenTogetherPreferences = AppContainer.listenTogetherPreferences,
                neteaseCookieRepo = AppContainer.neteaseCookieRepo,
                biliCookieRepo = AppContainer.biliCookieRepo,
                youTubeAuthRepo = AppContainer.youtubeAuthRepo,
                withYouTubeAuthChange = AppContainer::changeYouTubeAuth
            )
        }
    }

    fun requestExportOptions() {
        if (_uiState.value.isExporting) return
        cancelExportSelection()
        _uiState.value = _uiState.value.copy(showExportOptions = true)
    }

    fun prepareExport(includeLoginState: Boolean, password: String?): Boolean {
        if (!_uiState.value.showExportOptions || _uiState.value.isExporting) return false
        cancelExportSelection()
        exportOptions = ConfigExportOptions(includeLoginState)
        exportPassword = password?.toCharArray()
        return true
    }

    fun cancelExportSelection() {
        exportPassword?.fill('\u0000')
        exportPassword = null
        exportOptions = null
        _uiState.value = _uiState.value.copy(showExportOptions = false)
    }

    fun exportConfig(uri: Uri) {
        val manager = configFileManager ?: return
        val resources = strings ?: return
        // 文件选择返回前进程重建时，需要重新选择保护选项
        val options = exportOptions ?: run {
            _uiState.value = _uiState.value.copy(
                lastExportSuccess = false,
                lastExportMessage = resources.exportFailed(resources.exportOptionsExpired)
            )
            return
        }
        val password = exportPassword
        exportOptions = null
        exportPassword = null

        _uiState.value = _uiState.value.copy(
            isExporting = true,
            exportProgress = resources.exporting
        )

        val job = viewModelScope.launch {
            val result = try {
                manager.exportConfig(uri, options, password)
            } finally {
                password?.fill('\u0000')
            }
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
        job.invokeOnCompletion { password?.fill('\u0000') }
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
                val result = importWithPassword(manager, uri)
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
                            lastImportMessage = if (cancelled) null else resources.importFailed(transferFailureText(exception)),
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

    private suspend fun importWithPassword(manager: ConfigFileManager, uri: Uri): Result<AppConfigImportResult> {
        var invalidPassword = false
        while (true) {
            val result = manager.importConfig(
                uri = uri,
                requestPassword = { awaitImportPassword(invalidPassword) },
                confirmCompatibilityStorage = ::awaitCompatibilityStorageConfirmation
            )
            if (result.exceptionOrNull() !is ConfigBackupPasswordException) return result
            invalidPassword = true
        }
    }

    private suspend fun awaitImportPassword(invalidPassword: Boolean): CharArray? = withContext(Dispatchers.Main.immediate) {
        val request = CompletableDeferred<CharArray?>()
        pendingPassword = request
        _uiState.value = _uiState.value.copy(requiresConfigPassword = true, isConfigPasswordInvalid = invalidPassword)
        try {
            request.await()
        } finally {
            if (pendingPassword === request) {
                pendingPassword = null
                _uiState.value = _uiState.value.copy(requiresConfigPassword = false)
            }
        }
    }

    fun confirmImportPassword(password: String) {
        val value = password.toCharArray()
        val request = pendingPassword
        val job = importJob
        if (request == null || job == null) {
            value.fill('\u0000')
            return
        }
        // 取消可能发生在密码交回 IO 协程之前，任务结束仍需清理
        job.invokeOnCompletion { value.fill('\u0000') }
        if (!request.complete(value)) value.fill('\u0000')
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
        pendingPassword?.cancel()
        pendingPassword = null
        clearPendingImportState()
    }

    private fun clearPendingImportState() {
        _uiState.value = _uiState.value.copy(
            isImporting = false,
            importProgress = null,
            requiresAuthStorageConfirmation = false,
            requiresConfigPassword = false,
            isConfigPasswordInvalid = false
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
        cancelExportSelection()
        configFileManager = null
        strings = null
    }

    internal class ConfigTransferStrings private constructor() {
        val exporting: UiText = UiText.Resource(CoreCommonR.string.settings_config_exporting)
        val importing: UiText = UiText.Resource(CoreCommonR.string.settings_config_importing)
        val exportOptionsExpired: UiText = UiText.Resource(AppR.string.settings_config_export_options_expired)

        fun exportSuccess(fileName: String): UiText =
            feedbackValue(UiText.Resource(CoreCommonR.string.settings_config_export_success), fileName)

        fun exportFailed(message: Any?): UiText =
            feedbackValue(UiText.Resource(CoreCommonR.string.settings_config_export_failed), message ?: "")

        fun importFailed(message: Any?): UiText =
            feedbackValue(UiText.Resource(CoreCommonR.string.settings_config_import_failed), message ?: "")

        fun importSuccess(result: AppConfigImportResult): UiText = feedbackLines(buildList {
            add(UiText.Resource(CoreCommonR.string.settings_config_import_success))
            add(UiText.Plural(AppR.plurals.settings_config_import_restored_settings,
                result.restoredSettingsCount, listOf(result.restoredSettingsCount)))
            add(UiText.Plural(AppR.plurals.settings_config_import_restored_listen_together,
                result.restoredListenTogetherCount, listOf(result.restoredListenTogetherCount)))
            add(UiText.Plural(AppR.plurals.settings_config_import_restored_auth,
                result.restoredAuthCount, listOf(result.restoredAuthCount)))
            add(UiText.Plural(AppR.plurals.settings_config_import_restored_sync,
                result.restoredSyncCount, listOf(result.restoredSyncCount)))
            if (result.warnings.isNotEmpty()) {
                add(UiText.Literal(""))
                add(UiText.Resource(CoreCommonR.string.settings_config_import_warning_title))
                result.warnings.forEachIndexed { index, warning ->
                    val message = when (result.warningCodes[index]) {
                        AppConfigImportWarning.INVALID_SETTING_VALUES ->
                            UiText.Resource(CoreCommonR.string.config_import_warning_invalid_setting_values)
                        AppConfigImportWarning.BACKGROUND_IMAGE ->
                            UiText.Resource(CoreCommonR.string.config_import_warning_background_image)
                        AppConfigImportWarning.DOWNLOAD_DIRECTORY ->
                            UiText.Resource(CoreCommonR.string.config_import_warning_download_directory)
                        AppConfigImportWarning.NETEASE_COOKIE ->
                            UiText.Resource(CoreCommonR.string.config_import_warning_netease_cookie)
                        null -> UiText.Literal(warning)
                    }
                    add(UiText.Resource(AppR.string.settings_config_import_warning_item, listOf(message)))
                }
            }
            if (result.requiresActivityRecreate) {
                add(UiText.Literal(""))
                add(UiText.Resource(CoreCommonR.string.settings_config_import_restart_hint))
            }
        })

        companion object {
            fun create(): ConfigTransferStrings = ConfigTransferStrings()
        }
    }
}

data class ConfigTransferUiState(
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val exportProgress: UiText? = null,
    val importProgress: UiText? = null,
    val lastExportSuccess: Boolean? = null,
    val lastExportMessage: UiText? = null,
    val lastImportSuccess: Boolean? = null,
    val lastImportMessage: UiText? = null,
    val requiresAuthStorageConfirmation: Boolean = false,
    val showExportOptions: Boolean = false,
    val requiresConfigPassword: Boolean = false,
    val isConfigPasswordInvalid: Boolean = false,
    val importRequiresActivityRecreate: Boolean = false
)
