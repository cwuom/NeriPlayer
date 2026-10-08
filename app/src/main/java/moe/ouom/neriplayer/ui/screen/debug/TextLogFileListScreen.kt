package moe.ouom.neriplayer.ui.screen.debug

import android.annotation.SuppressLint
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import moe.ouom.neriplayer.ui.feedback.NeriSnackbarHost
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("LocalContextResourcesRead")
internal fun TextLogFileListScreen(
    @StringRes titleRes: Int,
    @StringRes clearConfirmRes: Int,
    @PluralsRes clearedCountRes: Int,
    @StringRes emptyTitleRes: Int,
    @StringRes emptyHintRes: Int,
    resolveDirectory: () -> File?,
    onBack: () -> Unit,
    onLogFileClick: (String) -> Unit,
    fileIcon: @Composable () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val showClearConfirmDialog = remember { mutableStateOf(false) }
    val logFilesState = remember { mutableStateOf(listTextLogFiles(resolveDirectory())) }

    if (showClearConfirmDialog.value) {
        TextLogClearConfirmDialog(
            messageRes = clearConfirmRes,
            onConfirm = {
                showClearConfirmDialog.value = false
                coroutineScope.launch {
                    val directory = resolveDirectory()
                    val clearedCount = withContext(Dispatchers.IO) { deleteTextLogFiles(directory) }
                    logFilesState.value = emptyList()
                    snackbarHostState.showNeriSnackbar(
                        context.resources.getQuantityString(clearedCountRes, clearedCount, clearedCount)
                    )
                }
            },
            onDismiss = { showClearConfirmDialog.value = false }
        )
    }

    Scaffold(
        snackbarHost = {
            val miniH = LocalMiniPlayerHeight.current
            NeriSnackbarHost(
                hostState = snackbarHostState,
                bottomPadding = miniH
            )
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(CoreCommonR.string.action_back))
                    }
                },
                actions = {
                    if (logFilesState.value.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirmDialog.value = true }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(CoreCommonR.string.log_clear))
                        }
                    }
                }
            )
        }
    ) { padding ->
        TextLogFileList(
            files = logFilesState.value,
            padding = padding,
            emptyTitleRes = emptyTitleRes,
            emptyHintRes = emptyHintRes,
            onLogFileClick = onLogFileClick,
            fileIcon = fileIcon
        )
    }
}

@Composable
private fun TextLogClearConfirmDialog(
    @StringRes messageRes: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreCommonR.string.dialog_confirm_clear)) },
        text = { Text(stringResource(messageRes)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(CoreCommonR.string.common_clear_all), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
        }
    )
}

@Composable
private fun TextLogFileList(
    files: List<File>,
    padding: PaddingValues,
    @StringRes emptyTitleRes: Int,
    @StringRes emptyHintRes: Int,
    onLogFileClick: (String) -> Unit,
    fileIcon: @Composable () -> Unit
) {
    val miniH = LocalMiniPlayerHeight.current

    LazyColumn(modifier = Modifier
        .padding(padding)
        .padding(bottom = miniH)
    ) {
        if (files.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(emptyTitleRes)) },
                    supportingContent = { Text(stringResource(emptyHintRes)) }
                )
            }
        } else {
            items(files) { file ->
                ListItem(
                    headlineContent = { Text(file.name) },
                    supportingContent = { Text(formatLogFileMeta(file)) },
                    leadingContent = fileIcon,
                    modifier = Modifier.clickable { onLogFileClick(file.absolutePath) }
                )
            }
        }
    }
}

internal fun listTextLogFiles(directory: File?): List<File> =
    directory?.listFiles { file -> isTextLogFile(file) }?.sortedByDescending { it.lastModified() } ?: emptyList()

internal fun deleteTextLogFiles(directory: File?): Int =
    directory?.listFiles { file -> isTextLogFile(file) }?.count { it.delete() } ?: 0

private fun isTextLogFile(file: File): Boolean = file.isFile && file.name.endsWith(".txt")

internal fun formatLogFileMeta(file: File): String {
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    val size = file.length() / 1024 // KB
    return "$date - ${size}KB"
}
