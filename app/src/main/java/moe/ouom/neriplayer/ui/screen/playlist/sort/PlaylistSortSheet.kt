package moe.ouom.neriplayer.ui.screen.playlist.sort

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
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
 * File: moe.ouom.neriplayer.ui.screen.playlist.sort/PlaylistSortSheet
 * Updated: 2026/11/21
 */

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.local.playlist.sort.PlaylistOrderMatch
import moe.ouom.neriplayer.data.local.playlist.sort.PlaylistSongSortDirection
import moe.ouom.neriplayer.data.local.playlist.sort.PlaylistSongSortMode
import moe.ouom.neriplayer.data.local.playlist.sort.PlaylistSongSortOrder
import moe.ouom.neriplayer.data.local.playlist.sort.matchPlaylistOrder
import moe.ouom.neriplayer.data.local.playlist.sort.parsePlaylistOrderText
import moe.ouom.neriplayer.data.local.playlist.sort.sortPlaylistSongs
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.haptic.HapticButton
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSegmentedTabs
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.R as AppR

private const val UNMATCHED_PREVIEW_LIMIT = 3
private val PREVIEW_MAX_HEIGHT = 260.dp
private const val SHEET_MAX_HEIGHT_FRACTION = 0.66f

/** 自定义顺序文件的大小上限：正常歌单文件远小于 1 MB，2 MB 足够且不会撑爆堆。 */
private const val MAX_ORDER_DOCUMENT_BYTES = 2 * 1024 * 1024

/**
 * 歌单排序面板：按标题 / 文件名一次性重排，或从 txt / csv 导入自定义顺序。
 *
 * 结构与「批量词图」面板一致：内容高度上限 62%，分段标签选择方式，底部单个主按钮。
 * 面板只计算顺序，落盘由调用方通过 `LocalPlaylistRepository.reorderSongs` 完成，
 * 因此关闭面板不会产生任何写入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistSortSheet(
    songs: List<SongItem>,
    onDismissRequest: () -> Unit,
    onApplyOrder: (List<SongItem>) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(PlaylistSongSortMode.TITLE) }
    var descending by remember { mutableStateOf(false) }
    var importedMatch by remember { mutableStateOf<PlaylistOrderMatch?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importMessageRes by remember { mutableStateOf<Int?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        importMessageRes = null
        scope.launch {
            val document = withContext(Dispatchers.IO) {
                runCatching { readPlaylistOrderDocument(context, uri) }
                    .getOrDefault(PlaylistOrderDocumentResult.Empty)
            }
            when (document) {
                is PlaylistOrderDocumentResult.Loaded -> {
                    // 解析与匹配都是 O(n²) 且会反复归一化字符串，不能留在主线程
                    val match = withContext(Dispatchers.Default) {
                        matchPlaylistOrder(songs, parsePlaylistOrderText(document.text).entries)
                    }
                    importing = false
                    importedMatch = match.takeIf { it.hasMatch }
                    importMessageRes = if (match.hasMatch) {
                        null
                    } else {
                        AppR.string.playlist_sort_import_empty
                    }
                }
                PlaylistOrderDocumentResult.TooLarge -> {
                    importing = false
                    importedMatch = null
                    importMessageRes = AppR.string.playlist_sort_import_too_large
                }
                PlaylistOrderDocumentResult.Empty -> {
                    importing = false
                    importedMatch = null
                    importMessageRes = AppR.string.playlist_sort_import_empty
                }
            }
        }
    }

    val order = PlaylistSongSortOrder(
        mode = mode,
        direction = if (descending) {
            PlaylistSongSortDirection.DESCENDING
        } else {
            PlaylistSongSortDirection.ASCENDING
        }
    )
    val previewSongs = remember(songs, mode, descending, importedMatch) {
        importedMatch?.orderedSongs ?: sortPlaylistSongs(songs, order)
    }

    fun dismiss() {
        scope.launch {
            runCatching { sheetState.hide() }
            onDismissRequest()
        }
    }

    fun applyOrder() {
        // 导入还在跑时应用只会提交旧预览，把用户刚选的顺序丢掉
        if (importing) return
        val ordered = previewSongs
        if (ordered.isEmpty()) return
        scope.launch {
            runCatching { sheetState.hide() }
            onApplyOrder(ordered)
        }
    }

    val maxContentHeight = playlistSortMaxContentHeight(SHEET_MAX_HEIGHT_FRACTION)

    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = sheetState,
        shape = neriRoundedShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        scrimColor = Color.Black.copy(alpha = 0.46f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .bottomSheetScrollGuard()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SortHeader(
                songCount = songs.size,
                onClose = ::dismiss
            )
            // 面板内容用 LazyColumn：上千首的预览只组合可见行，滚动也不掉帧
            LazyColumn(
                modifier = Modifier
                    .heightIn(max = maxContentHeight)
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item(key = "options") {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortSectionTitle(stringResource(AppR.string.playlist_sort_section_mode))
                    MiuixSettingsSegmentedTabs(
                        labels = listOf(
                            stringResource(AppR.string.playlist_sort_mode_title),
                            stringResource(AppR.string.playlist_sort_mode_file_name)
                        ),
                        selectedIndex = if (mode == PlaylistSongSortMode.FILE_NAME) 1 else 0,
                        onSelectedIndexChange = { index ->
                            importedMatch = null
                            importMessageRes = null
                            mode = if (index == 1) {
                                PlaylistSongSortMode.FILE_NAME
                            } else {
                                PlaylistSongSortMode.TITLE
                            }
                        }
                    )
                    SectionDescription(stringResource(AppR.string.playlist_sort_mode_desc))
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortSectionTitle(stringResource(AppR.string.playlist_sort_section_order))
                    MiuixSettingsSegmentedTabs(
                        labels = listOf(
                            stringResource(AppR.string.playlist_sort_ascending),
                            stringResource(AppR.string.playlist_sort_descending)
                        ),
                        selectedIndex = if (descending) 1 else 0,
                        onSelectedIndexChange = { index -> descending = index == 1 }
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SortSectionTitle(stringResource(AppR.string.playlist_sort_section_import))
                    SectionDescription(stringResource(AppR.string.playlist_sort_import_hint))
                    // 与面板背景拉开对比：主色容器，不要描边
                    HapticButton(
                        onClick = {
                            importMessageRes = null
                            picker.launch(
                                arrayOf(
                                    "text/*",
                                    "text/csv",
                                    "text/comma-separated-values",
                                    "application/csv",
                                    "application/vnd.ms-excel",
                                    "application/octet-stream"
                                )
                            )
                        },
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        if (importing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.TextSnippet,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (importing) {
                                    AppR.string.playlist_sort_import_busy
                                } else {
                                    AppR.string.playlist_sort_import_action
                                }
                            )
                        )
                    }
                    importMessageRes?.let { messageRes ->
                        Text(
                            text = stringResource(messageRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    importedMatch?.let { match ->
                        ImportResultBlock(
                            match = match,
                            onClear = {
                                importedMatch = null
                                importMessageRes = null
                            }
                        )
                    }
                }
                }
                }

                item(key = "preview-title") {
                    SortSectionTitle(stringResource(AppR.string.playlist_sort_section_preview))
                }
                // 预览跟随面板滚动，整份歌单都能逐行看完
                itemsIndexed(
                    items = previewSongs,
                    key = { index, song -> "preview-$index-${song.stableKey()}" }
                ) { index, song ->
                    PreviewSongRow(index = index, song = song)
                }
            }

            MiuixSettingsButton(
                onClick = ::applyOrder,
                // 导入没完成时不能应用：此刻预览还是旧顺序
                enabled = previewSongs.isNotEmpty() && !importing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(AppR.string.playlist_sort_apply))
            }
        }
    }
}

@Composable
private fun SortHeader(songCount: Int, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(AppR.string.playlist_sort_sheet_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = stringResource(AppR.string.playlist_sort_header_subtitle, songCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        HapticIconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(CoreCommonR.string.cd_close)
            )
        }
    }
}

@Composable
private fun SortSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SectionDescription(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun ImportResultBlock(match: PlaylistOrderMatch, onClear: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        Surface(
            shape = neriRoundedShape(16.dp),
            color = colors.secondaryContainer.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(
                        AppR.string.playlist_sort_import_summary,
                        match.matchedCount,
                        match.unmatchedEntries.size
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSecondaryContainer
                )
                match.unmatchedEntries.take(UNMATCHED_PREVIEW_LIMIT).forEach { entry ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(AppR.string.playlist_sort_import_unmatched, entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSecondaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (match.unmatchedEntries.size > UNMATCHED_PREVIEW_LIMIT) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            AppR.string.playlist_sort_import_unmatched_more,
                            match.unmatchedEntries.size - UNMATCHED_PREVIEW_LIMIT
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
        HapticTextButton(onClick = onClear) {
            Text(stringResource(AppR.string.playlist_sort_import_clear))
        }
    }
}

@Composable
private fun PreviewSongRow(index: Int, song: SongItem) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(colors.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = colors.onPrimary
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.displayName(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = song.displayArtist(),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun playlistSortMaxContentHeight(fraction: Float): Dp {
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    return with(LocalDensity.current) { (windowHeightPx * fraction).toDp() }
}

internal sealed interface PlaylistOrderDocumentResult {
    class Loaded(val text: String) : PlaylistOrderDocumentResult

    /** 文件为空、读不到，或解码后没有可用内容。 */
    object Empty : PlaylistOrderDocumentResult

    /** 超过 [MAX_ORDER_DOCUMENT_BYTES]，不读进内存。 */
    object TooLarge : PlaylistOrderDocumentResult
}

/**
 * 读取用户选择的顺序文件。utf-8 解码失败时回退 gbk，
 * 兼容中文 Windows 下导出的 txt / csv。
 *
 * 边读边计数，超过 [MAX_ORDER_DOCUMENT_BYTES] 立即放弃：picker 接受
 * `application/octet-stream`，误选大文件时 `readBytes()` 会先撑爆堆再报错。
 */
internal fun readPlaylistOrderDocument(context: Context, uri: Uri): PlaylistOrderDocumentResult {
    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_ORDER_DOCUMENT_BYTES) return PlaylistOrderDocumentResult.TooLarge
            output.write(chunk, 0, read)
        }
        output.toByteArray()
    } ?: return PlaylistOrderDocumentResult.Empty
    if (bytes.isEmpty()) return PlaylistOrderDocumentResult.Empty
    val utf8 = String(bytes, Charsets.UTF_8)
    val text = if (!utf8.contains('\uFFFD')) {
        utf8
    } else {
        val gbk = runCatching { String(bytes, charset("GBK")) }.getOrNull()
        if (gbk.isNullOrEmpty() || gbk.contains('\uFFFD')) utf8 else gbk
    }
    return if (text.isBlank()) {
        PlaylistOrderDocumentResult.Empty
    } else {
        PlaylistOrderDocumentResult.Loaded(text)
    }
}
