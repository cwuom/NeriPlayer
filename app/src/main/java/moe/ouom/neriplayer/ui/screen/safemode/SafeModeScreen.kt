package moe.ouom.neriplayer.ui.screen.safemode

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.util.crash.CrashReportStore
import moe.ouom.neriplayer.core.startup.safemode.SafeModeManager

@Composable
fun SafeModeScreen(
    onRestoreNormal: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<CrashReportStore.PendingCrashReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var showResetLoginDialog by remember { mutableStateOf(false) }
    var showResetSettingsDialog by remember { mutableStateOf(false) }
    var exportReport by remember { mutableStateOf<CrashReportStore.PendingCrashReport?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        val currentReport = exportReport ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                SafeModeManager.exportCrashReport(
                    context = context,
                    reportFile = currentReport.file,
                    destination = uri
                )
            }.onSuccess {
                Toast.makeText(context, context.getString(R.string.log_exported), Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    context.getString(R.string.log_export_failed, error.message ?: error.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        report = SafeModeManager.readPendingCrashReport(context)
        loading = false
    }

    if (showResetLoginDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showResetLoginDialog = false },
            title = { Text(stringResource(R.string.safe_mode_reset_login_confirm_title)) },
            text = { Text(stringResource(R.string.safe_mode_reset_login_confirm_message)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching {
                                SafeModeManager.clearAllCookiesAndLoginOptions(context)
                            }.onSuccess {
                                showResetLoginDialog = false
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.safe_mode_reset_login_done),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.safe_mode_reset_login_failed,
                                        error.message ?: error.javaClass.simpleName
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            busy = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { showResetLoginDialog = false }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showResetSettingsDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showResetSettingsDialog = false },
            title = { Text(stringResource(R.string.safe_mode_reset_settings_confirm_title)) },
            text = { Text(stringResource(R.string.safe_mode_reset_settings_confirm_message)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching {
                                SafeModeManager.resetAppSettings(context)
                            }.onSuccess {
                                showResetSettingsDialog = false
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.safe_mode_reset_settings_done),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }.onFailure { error ->
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.safe_mode_reset_settings_failed,
                                        error.message ?: error.javaClass.simpleName
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            busy = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { showResetSettingsDialog = false }
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SafeModeHeader()

            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                CrashLogPreviewCard(
                    report = report,
                    onCopy = {
                        val currentReport = report ?: return@CrashLogPreviewCard
                        scope.launch {
                            val fullContent = SafeModeManager.readFullCrashReport(currentReport.file)
                                ?: currentReport.previewContent
                            val copied = copyLogToClipboard(
                                clipboardManager = context.getSystemService(ClipboardManager::class.java),
                                content = fullContent
                            )
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (copied) {
                                        R.string.log_copied
                                    } else {
                                        R.string.log_cannot_read
                                    }
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onExport = {
                        val currentReport = report ?: return@CrashLogPreviewCard
                        exportReport = currentReport
                        exportLauncher.launch(currentReport.file.name)
                    }
                )
            }

            SafeModeExportCard(busy = busy)

            RecoveryActionCard(
                busy = busy,
                onResetLogin = { showResetLoginDialog = true },
                onResetSettings = { showResetSettingsDialog = true },
                onRestoreNormal = onRestoreNormal
            )
        }
    }
}

@Composable
private fun SafeModeHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.safe_mode_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.safe_mode_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.safe_mode_component_lock),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun CrashLogPreviewCard(
    report: CrashReportStore.PendingCrashReport?,
    onCopy: () -> Unit,
    onExport: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.safe_mode_log_preview),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (report == null) {
                Text(
                    text = stringResource(R.string.safe_mode_log_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            Text(
                text = stringResource(R.string.safe_mode_log_file, report.file.name),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (report.previewTruncated) {
                Text(
                    text = stringResource(R.string.startup_crash_report_truncated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            HorizontalDivider()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = report.previewContent.ifBlank {
                        stringResource(R.string.log_cannot_read)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.safe_mode_copy_log))
                }
                Button(
                    onClick = onExport,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.safe_mode_export_log))
                }
            }
        }
    }
}

@Composable
private fun RecoveryActionCard(
    busy: Boolean,
    onResetLogin: () -> Unit,
    onResetSettings: () -> Unit,
    onRestoreNormal: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.safe_mode_reset_login),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.safe_mode_reset_login_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onResetLogin,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.safe_mode_reset_login))
            }
            HorizontalDivider()
            Text(
                text = stringResource(R.string.safe_mode_reset_settings),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.safe_mode_reset_settings_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onResetSettings,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.safe_mode_reset_settings))
            }
            HorizontalDivider()
            Text(
                text = stringResource(R.string.safe_mode_restore_normal_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onRestoreNormal,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.safe_mode_restore_normal))
            }
        }
    }
}

private fun copyLogToClipboard(
    clipboardManager: ClipboardManager?,
    content: String
): Boolean {
    if (content.isBlank()) {
        return false
    }
    clipboardManager ?: return false
    clipboardManager.setPrimaryClip(
        ClipData.newPlainText("safe_mode_crash_log", content)
    )
    return true
}
