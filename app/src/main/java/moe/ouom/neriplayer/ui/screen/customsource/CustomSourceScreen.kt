package moe.ouom.neriplayer.ui.screen.customsource

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
 * File: moe.ouom.neriplayer.ui.screen.customsource/CustomSourceScreen
 * Created: 2026/7/26
 *
 * 自定义音源(兼容洛雪音乐 LX 脚本)管理界面。
 */

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.customsource.CustomAudioSource
import moe.ouom.neriplayer.ui.viewmodel.customsource.CustomSourceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomSourceScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val vm: CustomSourceViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                CustomSourceViewModel(appContext as Application)
            }
        }
    )
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPasteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()?.let { content ->
                vm.importScript(content)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text(stringResource(R.string.custom_source_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.custom_source_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 导入按钮
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { filePicker.launch("*/*") },
                        enabled = !ui.busy
                    ) { Text(stringResource(R.string.custom_source_import_file)) }
                    OutlinedButton(
                        onClick = { showPasteDialog = true },
                        enabled = !ui.busy
                    ) { Text(stringResource(R.string.custom_source_import_paste)) }
                    OutlinedButton(
                        onClick = { vm.testActiveSource() },
                        enabled = !ui.busy
                    ) { Text(stringResource(R.string.custom_source_test)) }
                }
            }

            // 优先模式开关
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.custom_source_priority_title),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(R.string.custom_source_priority_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = ui.priorityMode,
                            onCheckedChange = { vm.setPriorityMode(it) }
                        )
                    }
                }
            }

            if (ui.sources.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.custom_source_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            } else {
                items(ui.sources, key = { it.id }) { source ->
                    SourceCard(
                        source = source,
                        onEnabledChange = { enabled -> vm.setEnabled(source.id, enabled) },
                        onDelete = { vm.delete(source.id) }
                    )
                }
            }
        }
    }

    if (showPasteDialog) {
        PasteScriptDialog(
            onDismiss = { showPasteDialog = false },
            onConfirm = { text ->
                showPasteDialog = false
                vm.importScript(text)
            }
        )
    }
}

@Composable
private fun SourceCard(
    source: CustomAudioSource,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = source.enabled,
                onClick = { onEnabledChange(!source.enabled) }
            )
            Column(Modifier.weight(1f)) {
                Text(
                    source.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = buildString {
                    append("v${source.version}")
                    if (source.author.isNotBlank()) append(" · ${source.author}")
                    val platforms = source.supportedSources.keys.joinToString(", ")
                    if (platforms.isNotBlank()) append(" · $platforms")
                }
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = null)
            }
        }
    }
}

@Composable
private fun PasteScriptDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_source_import_paste)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                maxLines = 12,
                placeholder = { Text("// LX Music source script...") }
            )
        },
        confirmButton = {
            OutlinedButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank()
            ) { Text(stringResource(R.string.custom_source_import_confirm)) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.custom_source_cancel))
            }
        }
    )
}
