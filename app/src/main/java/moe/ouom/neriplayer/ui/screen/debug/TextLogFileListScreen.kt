package moe.ouom.neriplayer.ui.screen.debug

import android.annotation.SuppressLint
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import moe.ouom.neriplayer.ui.screen.tab.settings.page.MiuixSettingsSectionCard
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape

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
    fileIcon: ImageVector,
    fileHue: Color
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val showClearConfirmDialog = remember { mutableStateOf(false) }
    // listing touches the disk; doing it during composition stalled the enter transition
    val logFilesState = remember { mutableStateOf<List<File>?>(null) }
    LaunchedEffect(Unit) {
        logFilesState.value = withContext(Dispatchers.IO) { listTextLogFiles(resolveDirectory()) }
    }

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
        containerColor = Color.Transparent,
        snackbarHost = {
            NeriSnackbarHost(hostState = snackbarHostState, bottomPadding = LocalMiniPlayerHeight.current)
        },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
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
                    if (!logFilesState.value.isNullOrEmpty()) {
                        IconButton(onClick = { showClearConfirmDialog.value = true }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(CoreCommonR.string.log_clear))
                        }
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = logFilesState.value,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { files -> files?.isEmpty() },
            label = "text_log_files"
        ) { files ->
            when {
                files == null -> Box(Modifier.fillMaxSize())
                files.isEmpty() -> TextLogEmptyState(padding, fileIcon, fileHue, emptyTitleRes, emptyHintRes)
                else -> TextLogFileList(files, padding, fileIcon, fileHue, onLogFileClick)
            }
        }
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

private val LogGlyphShape = ContinuousRoundedCornerShape(9.dp)

@Composable
private fun LogGlyph(icon: ImageVector, hue: Color, size: Int = 32) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(LogGlyphShape)
            .background(hue),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.6f).dp))
    }
}

@Composable
private fun TextLogEmptyState(
    padding: PaddingValues,
    icon: ImageVector,
    hue: Color,
    @StringRes titleRes: Int,
    @StringRes hintRes: Int
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 32.dp)
            .padding(bottom = LocalMiniPlayerHeight.current),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LogGlyph(icon, hue, size = 52)
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(hintRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TextLogFileList(
    files: List<File>,
    padding: PaddingValues,
    icon: ImageVector,
    hue: Color,
    onLogFileClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + LocalMiniPlayerHeight.current + 24.dp
        )
    ) {
        item(key = "files") {
            MiuixSettingsSectionCard {
                files.forEachIndexed { index, file ->
                    TextLogFileRow(file, icon, hue) { onLogFileClick(file.absolutePath) }
                    if (index != files.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 60.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextLogFileRow(file: File, icon: ImageVector, hue: Color, onClick: () -> Unit) {
    val meta = remember(file) { formatLogFileMeta(file) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LogGlyph(icon, hue)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    return "$date · ${size} KB"
}
