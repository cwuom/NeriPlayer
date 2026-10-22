package moe.ouom.neriplayer.ui.screen.safemode

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.startup.safemode.SafeModeManager
import moe.ouom.neriplayer.data.config.ConfigExportOptions
import moe.ouom.neriplayer.ui.screen.tab.settings.backup.SettingsConfigExportOptionsDialog

@Composable
internal fun SafeModeExportCard(
    busy: Boolean,
    onShowMessage: (String, SnackbarDuration) -> Unit
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val scope = rememberCoroutineScope()
    var exportingKind by remember { mutableStateOf<SafeModeExportKind?>(null) }
    var showConfigExportOptions by remember { mutableStateOf(false) }
    var configExportOptions by remember { mutableStateOf<ConfigExportOptions?>(null) }
    var configExportPassword by remember { mutableStateOf<CharArray?>(null) }
    val isExporting = exportingKind != null

    fun cancelConfigExport() {
        configExportPassword?.fill('\u0000')
        configExportPassword = null
        configExportOptions = null
        showConfigExportOptions = false
    }

    DisposableEffect(Unit) {
        onDispose { configExportPassword?.fill('\u0000') }
    }

    fun exportData(kind: SafeModeExportKind, uri: Uri) {
        if (exportingKind != null) return
        val options = configExportOptions
        if (kind == SafeModeExportKind.Config && options == null) {
            onShowMessage(
                composeResources.getString(CoreCommonR.string.settings_config_export_options_expired),
                SnackbarDuration.Long
            )
            return
        }
        val password = if (kind == SafeModeExportKind.Config) configExportPassword else null
        if (kind == SafeModeExportKind.Config) {
            configExportOptions = null
            configExportPassword = null
        }
        exportingKind = kind
        val job = scope.launch {
            try {
                val result = when (kind) {
                    SafeModeExportKind.Config -> SafeModeManager.exportConfigBackup(
                        context, uri, requireNotNull(options), password
                    )
                    SafeModeExportKind.Playlists -> SafeModeManager.exportPlaylistBackup(context, uri)
                }
                result.fold(
                    onSuccess = { fileName ->
                        onShowMessage(
                            composeResources.getString(kind.successMessageRes, fileName),
                            SnackbarDuration.Short
                        )
                    },
                    onFailure = { error ->
                        onShowMessage(
                            composeResources.getString(
                                CoreCommonR.string.safe_mode_export_failed,
                                error.message ?: error.javaClass.simpleName
                            ),
                            SnackbarDuration.Long
                        )
                    }
                )
            } finally {
                password?.fill('\u0000')
                exportingKind = null
            }
        }
        job.invokeOnCompletion { password?.fill('\u0000') }
    }

    val configExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) {
            cancelConfigExport()
            return@rememberLauncherForActivityResult
        }
        exportData(SafeModeExportKind.Config, uri)
    }

    val playlistExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        exportData(SafeModeExportKind.Playlists, uri)
    }

    if (showConfigExportOptions) {
        SettingsConfigExportOptionsDialog(
            onConfirm = { includeLoginState, password ->
                if (showConfigExportOptions) {
                    cancelConfigExport()
                    configExportOptions = ConfigExportOptions(includeLoginState)
                    configExportPassword = password?.toCharArray()
                    try {
                        configExportLauncher.launch(SafeModeManager.generateConfigBackupFileName(context))
                    } catch (error: Exception) {
                        cancelConfigExport()
                        if (error is CancellationException) throw error
                        onShowMessage(
                            composeResources.getString(CoreCommonR.string.safe_mode_export_failed, error.javaClass.simpleName),
                            SnackbarDuration.Long
                        )
                    }
                }
            },
            onDismiss = ::cancelConfigExport
        )
    }

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(CoreCommonR.string.safe_mode_data_export_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(CoreCommonR.string.safe_mode_data_export_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isExporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(exportingKind?.progressMessageRes ?: CoreCommonR.string.safe_mode_exporting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = {
                        cancelConfigExport()
                        showConfigExportOptions = true
                    },
                    enabled = !busy && !isExporting && configExportOptions == null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(CoreCommonR.string.safe_mode_export_config))
                }
                Button(
                    onClick = {
                        playlistExportLauncher.launch(SafeModeManager.generatePlaylistBackupFileName(context))
                    },
                    enabled = !busy && !isExporting && configExportOptions == null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(CoreCommonR.string.safe_mode_export_playlists))
                }
            }
        }
    }
}

private enum class SafeModeExportKind(
    val successMessageRes: Int,
    val progressMessageRes: Int
) {
    Config(
        successMessageRes = CoreCommonR.string.safe_mode_export_config_done,
        progressMessageRes = CoreCommonR.string.safe_mode_exporting_config
    ),
    Playlists(
        successMessageRes = CoreCommonR.string.safe_mode_export_playlists_done,
        progressMessageRes = CoreCommonR.string.safe_mode_exporting_playlists
    )
}
