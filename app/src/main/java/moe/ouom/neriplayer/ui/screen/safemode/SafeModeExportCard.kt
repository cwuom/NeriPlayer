package moe.ouom.neriplayer.ui.screen.safemode

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.startup.safemode.SafeModeManager

@Composable
internal fun SafeModeExportCard(
    busy: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportingKind by remember { mutableStateOf<SafeModeExportKind?>(null) }
    val isExporting = exportingKind != null

    fun exportData(kind: SafeModeExportKind, uri: Uri) {
        scope.launch {
            exportingKind = kind
            val result = when (kind) {
                SafeModeExportKind.Config -> SafeModeManager.exportConfigBackup(context, uri)
                SafeModeExportKind.Playlists -> SafeModeManager.exportPlaylistBackup(context, uri)
            }
            result.fold(
                onSuccess = { fileName ->
                    Toast.makeText(
                        context,
                        context.getString(kind.successMessageRes, fileName),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = { error ->
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.safe_mode_export_failed,
                            error.message ?: error.javaClass.simpleName
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
            exportingKind = null
        }
    }

    val configExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        exportData(SafeModeExportKind.Config, uri)
    }

    val playlistExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        exportData(SafeModeExportKind.Playlists, uri)
    }

    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.safe_mode_data_export_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.safe_mode_data_export_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isExporting) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(exportingKind?.progressMessageRes ?: R.string.safe_mode_exporting),
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
                        configExportLauncher.launch(SafeModeManager.generateConfigBackupFileName(context))
                    },
                    enabled = !busy && !isExporting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.safe_mode_export_config))
                }
                Button(
                    onClick = {
                        playlistExportLauncher.launch(SafeModeManager.generatePlaylistBackupFileName(context))
                    },
                    enabled = !busy && !isExporting,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.safe_mode_export_playlists))
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
        successMessageRes = R.string.safe_mode_export_config_done,
        progressMessageRes = R.string.safe_mode_exporting_config
    ),
    Playlists(
        successMessageRes = R.string.safe_mode_export_playlists_done,
        progressMessageRes = R.string.safe_mode_exporting_playlists
    )
}
