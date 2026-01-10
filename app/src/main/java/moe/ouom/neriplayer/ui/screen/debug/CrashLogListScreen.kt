package moe.ouom.neriplayer.ui.screen.debug

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.debug/CrashLogListScreen
 * Created: 2025/1/11
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogListScreen(
    onBack: () -> Unit,
    onLogFileClick: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val showClearConfirmDialog = remember { mutableStateOf(false) }

    val crashLogFilesState = remember {
        mutableStateOf(
            run {
                val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                val crashDir = File(baseDir, "crashes")
                crashDir.listFiles { file ->
                    file.isFile && file.name.endsWith(".txt")
                }?.sortedByDescending { it.lastModified() } ?: emptyList()
            }
        )
    }

    if (showClearConfirmDialog.value) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog.value = false },
            title = { Text(stringResource(R.string.dialog_confirm_clear)) },
            text = { Text(stringResource(R.string.crash_log_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmDialog.value = false
                        coroutineScope.launch {
                            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                            val crashDir = File(baseDir, "crashes")
                            var clearedCount = 0
                            withContext(Dispatchers.IO) {
                                crashDir.listFiles { file ->
                                    file.isFile && file.name.endsWith(".txt")
                                }?.forEach {
                                    if (it.delete()) {
                                        clearedCount++
                                    }
                                }
                            }
                            crashLogFilesState.value = emptyList()
                            snackbarHostState.showSnackbar(context.getString(R.string.crash_log_cleared, clearedCount))
                        }
                    }
                ) {
                    Text(stringResource(R.string.common_clear_all), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog.value = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        snackbarHost = {
            val miniH = LocalMiniPlayerHeight.current
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .padding(bottom = miniH)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding()
            )
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.crash_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (crashLogFilesState.value.isNotEmpty()) {
                        IconButton(onClick = { showClearConfirmDialog.value = true }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.log_clear))
                        }
                    }
                }
            )
        }
    ) { padding ->
        val miniH = LocalMiniPlayerHeight.current

        LazyColumn(modifier = Modifier
            .padding(padding)
            .padding(bottom = miniH)
        ) {
            if (crashLogFilesState.value.isEmpty()) {
                item {
                    ListItem(
                        headlineContent = { Text("暂无崩溃日志") },
                        supportingContent = { Text("应用崩溃时会自动记录日志") }
                    )
                }
            } else {
                items(crashLogFilesState.value) { file ->
                    ListItem(
                        headlineContent = { Text(file.name) },
                        supportingContent = { Text(formatFileMeta(file)) },
                        leadingContent = { Icon(Icons.Outlined.Error, null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable { onLogFileClick(file.absolutePath) }
                    )
                }
            }
        }
    }
}

private fun formatFileMeta(file: File): String {
    val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    val size = file.length() / 1024 // KB
    return "$date - ${size}KB"
}
