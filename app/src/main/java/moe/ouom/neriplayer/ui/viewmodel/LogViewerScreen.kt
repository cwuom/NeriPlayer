package moe.ouom.neriplayer.ui.viewmodel

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
 * File: moe.ouom.neriplayer.ui.viewmodel/LogViewerScreen
 * Created: 2025/8/17
 */

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    filePath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    var logContent by remember { mutableStateOf<List<String>>(emptyList()) }
    val decodedFilePath = remember { URLDecoder.decode(filePath, StandardCharsets.UTF_8.name()) }

    // SAF auncher for exporting
    val exportLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri: Uri? ->
            uri?.let {
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(it)?.use { outputStream ->
                            File(decodedFilePath).inputStream().copyTo(outputStream)
                        }
                        withContext(Dispatchers.Main) {
                            snackbarHostState.showSnackbar("日志已导出")
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            snackbarHostState.showSnackbar("导出失败: ${e.message}")
                        }
                    }
                }
            }
        }
    )

    LaunchedEffect(decodedFilePath) {
        withContext(Dispatchers.IO) {
            try {
                val lines = File(decodedFilePath).readLines()
                withContext(Dispatchers.Main) {
                    logContent = lines
                }
            } catch (e: FileNotFoundException) {
                withContext(Dispatchers.Main) {
                    snackbarHostState.showSnackbar("无法读取日志文件")
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(File(decodedFilePath).name, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val fullText = logContent.joinToString("\n")
                        clipboardManager.setText(AnnotatedString(fullText))
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("已复制到剪贴板")
                        }
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "复制全部")
                    }
                    IconButton(onClick = {
                        exportLogLauncher.launch(File(decodedFilePath).name)
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = "导出日志")
                    }
                }
            )
        }
    ) { padding ->
        val listState = rememberLazyListState()
        LaunchedEffect(logContent.size) {
            if (logContent.isNotEmpty()) {
                listState.scrollToItem(logContent.size - 1)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding)
        ) {
            items(logContent) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }
}