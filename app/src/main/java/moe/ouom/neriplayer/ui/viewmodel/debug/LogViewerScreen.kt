package moe.ouom.neriplayer.ui.viewmodel.debug

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
 * File: moe.ouom.neriplayer.ui.viewmodel.debug/LogViewerScreen
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.feedback.NeriSnackbarHost
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.util.ClipboardCopyResult
import moe.ouom.neriplayer.ui.util.copyPlainTextSafely
import java.io.File
import java.io.FileNotFoundException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import moe.ouom.neriplayer.R as AppR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    filePath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val snackbarHostState = remember { SnackbarHostState() }

    var logContent by remember { mutableStateOf<List<String>?>(null) }
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
                            snackbarHostState.showNeriSnackbar(composeResources.getString(AppR.string.log_exported))
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            snackbarHostState.showNeriSnackbar(composeResources.getString(AppR.string.log_export_failed, e.message))
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
                    logContent = emptyList()
                    snackbarHostState.showNeriSnackbar(composeResources.getString(AppR.string.log_cannot_read))
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = {
            NeriSnackbarHost(hostState = snackbarHostState, bottomPadding = LocalMiniPlayerHeight.current)
        },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        File(decodedFilePath).name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(CoreCommonR.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val fullText = logContent.orEmpty().joinToString("\n")
                        coroutineScope.launch {
                            val messageRes = when (
                                val result = clipboard.copyPlainTextSafely("text", fullText)
                            ) {
                                is ClipboardCopyResult.Copied -> if (result.wasTruncated) {
                                    CoreCommonR.string.toast_copy_truncated
                                } else {
                                    AppR.string.log_copied
                                }
                                ClipboardCopyResult.TransactionTooLarge -> CoreCommonR.string.toast_copy_failed
                            }
                            snackbarHostState.showNeriSnackbar(composeResources.getString(messageRes))
                        }
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(AppR.string.debug_copy_all))
                    }
                    IconButton(onClick = {
                        exportLogLauncher.launch(File(decodedFilePath).name)
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(AppR.string.log_export))
                    }
                }
            )
        }
    ) { padding ->
        val lines = logContent
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .padding(horizontal = 12.dp)
                .padding(bottom = LocalMiniPlayerHeight.current + 16.dp)
        ) {
            AnimatedVisibility(visible = lines != null, enter = fadeIn(), exit = fadeOut()) {
                MiuixSettingsSectionCard(modifier = Modifier.fillMaxSize()) {
                    LogLines(lines.orEmpty())
                }
            }
        }
    }
}

/**
 * Opens already scrolled to the newest line, so the reader never watches the list jump there,
 * and tints lines by level so failures stand out in a long log.
 */
@Composable
private fun LogLines(lines: List<String>) {
    val listState = remember(lines) { LazyListState(firstVisibleItemIndex = (lines.size - 1).coerceAtLeast(0)) }
    val colors = MaterialTheme.colorScheme
    val gutterWidth = remember(lines.size) { lines.size.toString().length }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(lines) { index, line ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text(
                    text = (index + 1).toString().padStart(gutterWidth),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(end = 10.dp, top = 1.dp)
                )
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = when (logLineLevel(line)) {
                        LogLineLevel.Error -> colors.error
                        LogLineLevel.Warning -> Color(0xFFFF9F0A)
                        LogLineLevel.Other -> colors.onSurface
                    }
                )
            }
        }
    }
}

internal enum class LogLineLevel { Error, Warning, Other }

private val ErrorMarkers = listOf(" E/", " E ", "[E]", "ERROR", "FATAL", "Exception", "at ")
private val WarningMarkers = listOf(" W/", " W ", "[W]", "WARN")

internal fun logLineLevel(line: String): LogLineLevel = when {
    ErrorMarkers.any { marker ->
        if (marker == "at ") line.trimStart().startsWith(marker) else line.contains(marker)
    } -> LogLineLevel.Error
    WarningMarkers.any(line::contains) -> LogLineLevel.Warning
    else -> LogLineLevel.Other
}
