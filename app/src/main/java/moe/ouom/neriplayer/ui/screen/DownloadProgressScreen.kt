package moe.ouom.neriplayer.ui.screen

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
 * File: moe.ouom.neriplayer.ui.screen/DownloadProgressScreen
 * Updated: 2026/3/23
 */


import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.BatchDownloadOverallProgress
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.core.download.ExplicitDownloadResumeCandidate
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.formatDownloadTransferProgress
import moe.ouom.neriplayer.core.download.isDownloadTaskCancellable
import moe.ouom.neriplayer.core.download.visibleDownloadProgressTasks
import moe.ouom.neriplayer.core.download.visibleExplicitResumeCandidates
import moe.ouom.neriplayer.core.download.execution.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.PersistentDownloadClearFenceStore
import moe.ouom.neriplayer.core.download.execution.WAITING_STORAGE_MUTATION_OPERATION_STATE
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.core.download.execution.loadExplicitDownloadResumeCandidates
import moe.ouom.neriplayer.core.download.execution.resumeExplicitDownload
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback

private const val INITIAL_DOWNLOAD_PROGRESS_PROBE_ATTEMPTS = 3
private const val INITIAL_DOWNLOAD_PROGRESS_PROBE_DELAY_MS = 250L
private const val DOWNLOAD_PROGRESS_BOOTSTRAP_RECHECK_INITIAL_DELAY_MS = 750L
private const val DOWNLOAD_PROGRESS_BOOTSTRAP_RECHECK_MAX_DELAY_MS = 5_000L

internal data class DownloadProgressBootstrapState(
    val durablePendingSongKeys: Set<String> = emptySet(),
    val explicitResumeCandidates: List<ExplicitDownloadResumeCandidate> = emptyList(),
    val clearFenceActive: Boolean = false
)

internal enum class DownloadProgressPagePresentation {
    LOADING,
    UNAVAILABLE,
    EMPTY,
    CLEARING,
    CONTENT
}

internal enum class DownloadProgressInitialProbeState {
    LOADING,
    RESOLVED,
    UNAVAILABLE
}

internal fun resolveDownloadProgressPagePresentation(
    initialProbeState: DownloadProgressInitialProbeState,
    hasVisibleContent: Boolean,
    hasKnownPendingTasks: Boolean,
    isClearing: Boolean,
    isClearPresentationCleared: Boolean
): DownloadProgressPagePresentation {
    if (isClearPresentationCleared) {
        return DownloadProgressPagePresentation.EMPTY
    }
    if (isClearing) {
        return DownloadProgressPagePresentation.CLEARING
    }
    if (hasVisibleContent || hasKnownPendingTasks) {
        return DownloadProgressPagePresentation.CONTENT
    }
    return when (initialProbeState) {
        DownloadProgressInitialProbeState.LOADING -> DownloadProgressPagePresentation.LOADING
        DownloadProgressInitialProbeState.RESOLVED -> DownloadProgressPagePresentation.EMPTY
        DownloadProgressInitialProbeState.UNAVAILABLE -> DownloadProgressPagePresentation.UNAVAILABLE
    }
}

internal fun shouldRecheckDownloadProgressBootstrap(
    initialProbeState: DownloadProgressInitialProbeState,
    clearFenceActive: Boolean,
    isClearing: Boolean,
    isClearPresentationCleared: Boolean
): Boolean {
    return !isClearing &&
        !isClearPresentationCleared &&
        (
            initialProbeState == DownloadProgressInitialProbeState.UNAVAILABLE ||
                clearFenceActive
            )
}

private sealed interface DownloadProgressBootstrapProbeResult {
    data class Resolved(val state: DownloadProgressBootstrapState) :
        DownloadProgressBootstrapProbeResult

    data object Unavailable : DownloadProgressBootstrapProbeResult
}

private suspend fun loadDownloadProgressBootstrapState(
    context: android.content.Context
): DownloadProgressBootstrapProbeResult {
    var lastSuccessfulState: DownloadProgressBootstrapState? = null
    repeat(INITIAL_DOWNLOAD_PROGRESS_PROBE_ATTEMPTS) { attempt ->
        val state = runCatching {
            readDownloadProgressBootstrapState(context)
        }.getOrNull()
        if (state != null) {
            lastSuccessfulState = state
            if (
                state.clearFenceActive ||
                    state.durablePendingSongKeys.isNotEmpty() ||
                    state.explicitResumeCandidates.isNotEmpty()
            ) {
                return DownloadProgressBootstrapProbeResult.Resolved(state)
            }
        }
        if (attempt < INITIAL_DOWNLOAD_PROGRESS_PROBE_ATTEMPTS - 1) {
            kotlinx.coroutines.delay(INITIAL_DOWNLOAD_PROGRESS_PROBE_DELAY_MS)
        }
    }
    return lastSuccessfulState?.let(DownloadProgressBootstrapProbeResult::Resolved)
        ?: DownloadProgressBootstrapProbeResult.Unavailable
}

private suspend fun readDownloadProgressBootstrapState(
    context: android.content.Context
): DownloadProgressBootstrapState = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    if (PersistentDownloadClearFenceStore.isActive(appContext)) {
        return@withContext DownloadProgressBootstrapState(clearFenceActive = true)
    }

    val durablePendingSongKeys = linkedSetOf<String>()
    durablePendingSongKeys += ManagedDownloadStorage.listPendingQueuedDownloads(appContext)
        .map { entry -> entry.song.stableKey() }
    durablePendingSongKeys += ManagedDownloadStorage.listPendingResumableDownloads(appContext)
        .map { entry -> entry.song.stableKey() }
    durablePendingSongKeys += DownloadExecutionRoomStore.listByStates(
        context = appContext,
        states = DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES +
            DownloadExecutionRoomStore.IN_FLIGHT_OPERATION_STATES +
            WAITING_STORAGE_MUTATION_OPERATION_STATE,
        excludeUserStoppedOperations = true
    ).map { entry -> entry.request.song.stableKey() }

    if (PersistentDownloadClearFenceStore.isActive(appContext)) {
        return@withContext DownloadProgressBootstrapState(clearFenceActive = true)
    }

    DownloadProgressBootstrapState(
        durablePendingSongKeys = durablePendingSongKeys,
        explicitResumeCandidates = loadExplicitDownloadResumeCandidates(appContext)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("AssignedValueIsNeverRead")
fun DownloadProgressScreen(
    onBack: () -> Unit,
    listState: LazyListState
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val batchDownloadProgress by GlobalDownloadManager.batchDownloadProgressFlow
        .collectAsStateWithLifecycle()
    val downloadTasks by GlobalDownloadManager.downloadTasks.collectAsStateWithLifecycle()
    val isClearingDownloadTasks by GlobalDownloadManager.isClearingDownloadTasks
        .collectAsStateWithLifecycle()
    val isDownloadTaskClearPresentationCleared by
        GlobalDownloadManager.isDownloadTaskClearPresentationCleared
            .collectAsStateWithLifecycle()
    var bootstrapProbeResult by remember(context) {
        mutableStateOf<DownloadProgressBootstrapProbeResult?>(null)
    }
    var explicitResumeCandidates by remember {
        mutableStateOf<List<ExplicitDownloadResumeCandidate>>(emptyList())
    }
    val taskPresenceKey = remember(downloadTasks) {
        downloadTasks
            .filter { task ->
                task.status == DownloadStatus.QUEUED ||
                    task.status == DownloadStatus.DOWNLOADING ||
                    task.status == DownloadStatus.WAITING_NETWORK
            }
            .map { task -> task.song.stableKey() }
            .toSet()
    }
    LaunchedEffect(
        context,
        taskPresenceKey,
        isClearingDownloadTasks,
        isDownloadTaskClearPresentationCleared
    ) {
        if (isClearingDownloadTasks || isDownloadTaskClearPresentationCleared) {
            bootstrapProbeResult = DownloadProgressBootstrapProbeResult.Resolved(
                DownloadProgressBootstrapState()
            )
            explicitResumeCandidates = emptyList()
            return@LaunchedEffect
        }
        val nextBootstrapProbeResult = loadDownloadProgressBootstrapState(context)
        bootstrapProbeResult = nextBootstrapProbeResult
        val nextBootstrapState = (nextBootstrapProbeResult as?
            DownloadProgressBootstrapProbeResult.Resolved)?.state
        explicitResumeCandidates = visibleExplicitResumeCandidates(
            candidates = nextBootstrapState?.explicitResumeCandidates.orEmpty(),
            activeSongKeys = taskPresenceKey
        )
    }
    val bootstrapState = (bootstrapProbeResult as?
        DownloadProgressBootstrapProbeResult.Resolved)?.state
    val initialProbeState = when (bootstrapProbeResult) {
        null -> DownloadProgressInitialProbeState.LOADING
        is DownloadProgressBootstrapProbeResult.Resolved ->
            DownloadProgressInitialProbeState.RESOLVED

        DownloadProgressBootstrapProbeResult.Unavailable ->
            DownloadProgressInitialProbeState.UNAVAILABLE
    }
    val effectiveIsClearing = isClearingDownloadTasks ||
        bootstrapState?.clearFenceActive == true
    val shouldRecheckBootstrap = shouldRecheckDownloadProgressBootstrap(
        initialProbeState = initialProbeState,
        clearFenceActive = bootstrapState?.clearFenceActive == true,
        isClearing = isClearingDownloadTasks,
        isClearPresentationCleared = isDownloadTaskClearPresentationCleared
    )
    LaunchedEffect(context, taskPresenceKey, shouldRecheckBootstrap) {
        if (!shouldRecheckBootstrap) {
            return@LaunchedEffect
        }
        var delayMs = DOWNLOAD_PROGRESS_BOOTSTRAP_RECHECK_INITIAL_DELAY_MS
        while (true) {
            kotlinx.coroutines.delay(delayMs)
            val nextBootstrapProbeResult = loadDownloadProgressBootstrapState(context)
            bootstrapProbeResult = nextBootstrapProbeResult
            val nextBootstrapState = (nextBootstrapProbeResult as?
                DownloadProgressBootstrapProbeResult.Resolved)?.state
            explicitResumeCandidates = visibleExplicitResumeCandidates(
                candidates = nextBootstrapState?.explicitResumeCandidates.orEmpty(),
                activeSongKeys = taskPresenceKey
            )
            if (!shouldRecheckDownloadProgressBootstrap(
                    initialProbeState = when (nextBootstrapProbeResult) {
                        is DownloadProgressBootstrapProbeResult.Resolved ->
                            DownloadProgressInitialProbeState.RESOLVED

                        DownloadProgressBootstrapProbeResult.Unavailable ->
                            DownloadProgressInitialProbeState.UNAVAILABLE
                    },
                    clearFenceActive = nextBootstrapState?.clearFenceActive == true,
                    isClearing = isClearingDownloadTasks,
                    isClearPresentationCleared = isDownloadTaskClearPresentationCleared
                )
            ) {
                return@LaunchedEffect
            }
            delayMs = (delayMs * 2).coerceAtMost(
                DOWNLOAD_PROGRESS_BOOTSTRAP_RECHECK_MAX_DELAY_MS
            )
        }
    }
    val explicitResumeSongKeys = remember(explicitResumeCandidates) {
        explicitResumeCandidates.mapTo(linkedSetOf()) { candidate ->
            candidate.song.stableKey()
        }
    }
    val pendingTaskCount = remember(
        taskPresenceKey,
        bootstrapProbeResult,
        explicitResumeSongKeys,
        isDownloadTaskClearPresentationCleared
    ) {
        if (isDownloadTaskClearPresentationCleared) {
            0
        } else {
            (taskPresenceKey +
                (bootstrapState?.durablePendingSongKeys.orEmpty() - explicitResumeSongKeys))
                .size
        }
    }
    val visibleBatchProgress = batchDownloadProgress?.takeUnless {
        isDownloadTaskClearPresentationCleared
    }
    val visibleTasks = if (isDownloadTaskClearPresentationCleared) {
        emptyList()
    } else {
        visibleDownloadProgressTasks(downloadTasks)
    }
    val displayedPendingTaskCount = pendingTaskCount + explicitResumeCandidates.size
    val pagePresentation = resolveDownloadProgressPagePresentation(
        initialProbeState = initialProbeState,
        hasVisibleContent = visibleBatchProgress != null ||
            visibleTasks.isNotEmpty() ||
            explicitResumeCandidates.isNotEmpty(),
        hasKnownPendingTasks = pendingTaskCount > 0,
        isClearing = effectiveIsClearing,
        isClearPresentationCleared = isDownloadTaskClearPresentationCleared
    )
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.download_clear_confirm_title)) },
            text = { Text(stringResource(R.string.download_clear_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.performHapticFeedback()
                        bootstrapProbeResult = DownloadProgressBootstrapProbeResult.Resolved(
                            DownloadProgressBootstrapState()
                        )
                        explicitResumeCandidates = emptyList()
                        GlobalDownloadManager.clearAllDownloadTasks()
                        showClearDialog = false
                    }
                ) {
                    Text(stringResource(R.string.download_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.download_cancel_action))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // 顶部栏
        TopAppBar(
            title = {
                Column {
                    Text(
                        stringResource(R.string.download_progress),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = when {
                            pagePresentation == DownloadProgressPagePresentation.LOADING ->
                                stringResource(R.string.download_loading_tasks)

                            pagePresentation == DownloadProgressPagePresentation.UNAVAILABLE ->
                                stringResource(R.string.download_loading_tasks_recovering)

                            effectiveIsClearing ->
                                stringResource(R.string.download_clearing_tasks)

                            visibleBatchProgress != null -> stringResource(
                                R.string.download_progress_with_percentage,
                                visibleBatchProgress.completedSongs,
                                visibleBatchProgress.totalSongs,
                                visibleBatchProgress.percentage
                            )

                            displayedPendingTaskCount > 0 -> pluralStringResource(
                                R.plurals.download_tasks_count,
                                displayedPendingTaskCount,
                                displayedPendingTaskCount
                            )

                            else -> stringResource(R.string.download_no_tasks)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent
            ),
            actions = {
                IconButton(
                    enabled = !effectiveIsClearing,
                    onClick = {
                        context.performHapticFeedback()
                        showClearDialog = true
                    }
                ) {
                    Icon(Icons.Default.ClearAll, contentDescription = stringResource(R.string.download_clear_completed))
                }
            }
        )

        when (pagePresentation) {
            DownloadProgressPagePresentation.LOADING -> {
                DownloadProgressInitialLoadingContent()
            }

            DownloadProgressPagePresentation.UNAVAILABLE -> {
                DownloadProgressBootstrapUnavailableContent()
            }

            DownloadProgressPagePresentation.EMPTY,
            DownloadProgressPagePresentation.CLEARING -> {
                DownloadProgressEmptyContent(
                    isClearing = pagePresentation == DownloadProgressPagePresentation.CLEARING
                )
            }

            DownloadProgressPagePresentation.CONTENT -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = 16.dp + miniPlayerHeight
                    )
                ) {
                    visibleBatchProgress?.let { progress ->
                        item(key = "batch-overall-progress") {
                            BatchDownloadOverallProgressCard(progress = progress)
                        }
                    }
                    if (visibleBatchProgress == null && pendingTaskCount > 0 && visibleTasks.isEmpty()) {
                        item(key = "pending-download-summary") {
                            PendingDownloadSummaryCard(count = pendingTaskCount)
                        }
                    }
                    if (explicitResumeCandidates.isNotEmpty()) {
                        item(key = "explicit-resume-summary") {
                            ExplicitResumeSummaryCard(
                                count = explicitResumeCandidates.size
                            )
                        }
                        items(
                            items = explicitResumeCandidates,
                            key = { candidate -> candidate.operationId },
                            contentType = { "explicit-resume" }
                        ) { candidate ->
                            ExplicitResumeTaskItem(
                                candidate = candidate,
                                onResume = {
                                    if (!effectiveIsClearing) {
                                        context.performHapticFeedback()
                                        coroutineScope.launch {
                                            val schedule = runCatching {
                                                resumeExplicitDownload(context, candidate)
                                            }.getOrNull()
                                            if (schedule is moe.ouom.neriplayer.core.download.execution.DownloadExecutionSchedule.Scheduled) {
                                                explicitResumeCandidates = explicitResumeCandidates
                                                    .filterNot { item ->
                                                        item.operationId == candidate.operationId
                                                    }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                    items(
                        items = visibleTasks,
                        key = { it.song.stableKey() },
                        contentType = { task -> task.status }
                    ) { task ->
                        val songKey = task.song.stableKey()
                        DownloadTaskItem(
                            task = task,
                            onCancel = {
                                context.performHapticFeedback()
                                GlobalDownloadManager.cancelDownloadTask(songKey)
                            },
                            actionsEnabled = !effectiveIsClearing,
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(durationMillis = 250),
                                fadeOutSpec = tween(durationMillis = 250),
                                placementSpec = tween(durationMillis = 250)
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressInitialLoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun DownloadProgressBootstrapUnavailableContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.download_loading_tasks_recovering),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DownloadProgressEmptyContent(isClearing: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Outlined.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(
                    if (isClearing) {
                        R.string.download_clearing_tasks
                    } else {
                        R.string.download_no_tasks
                    }
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DownloadTaskItem(
    task: DownloadTask,
    onCancel: () -> Unit,
    actionsEnabled: Boolean,
    modifier: Modifier = Modifier,
    onResume: () -> Unit = {}
) {
    val songName = remember(task.song) { task.song.displayName() }
    val songArtist = remember(task.song) { task.song.displayArtist() }
    val shape = RoundedCornerShape(12.dp)
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        fallbackColor = baseColor.copy(alpha = 0.3f),
        tintColor = baseColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DownloadTaskStatusIcon(task.status)

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = songName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = songArtist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                DownloadTaskActionButton(
                    task = task,
                    onCancel = onCancel,
                    onResume = onResume,
                    actionsEnabled = actionsEnabled
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            DownloadTaskProgressSection(task = task)
        }
    }
}

@Composable
private fun BatchDownloadOverallProgressCard(progress: BatchDownloadOverallProgress) {
    val shape = RoundedCornerShape(12.dp)
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        fallbackColor = baseColor.copy(alpha = 0.3f),
        tintColor = baseColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(
                    R.string.download_progress_with_percentage,
                    progress.completedSongs,
                    progress.totalSongs,
                    progress.percentage
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun PendingDownloadSummaryCard(count: Int) {
    val shape = RoundedCornerShape(12.dp)
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        fallbackColor = baseColor.copy(alpha = 0.3f),
        tintColor = baseColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = pluralStringResource(R.plurals.download_tasks_count, count, count),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun ExplicitResumeSummaryCard(count: Int) {
    val shape = RoundedCornerShape(12.dp)
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        fallbackColor = baseColor.copy(alpha = 0.3f),
        tintColor = baseColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.download_explicit_resume_count,
                    count,
                    count
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(R.string.download_explicit_resume_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExplicitResumeTaskItem(
    candidate: ExplicitDownloadResumeCandidate,
    onResume: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        fallbackColor = baseColor.copy(alpha = 0.3f),
        tintColor = baseColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.PauseCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.song.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.download_explicit_resume_status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = candidate.song.displayArtist(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onResume) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.download_resume),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun DownloadTaskStatusIcon(status: DownloadStatus) {
    Icon(
        imageVector = when (status) {
            DownloadStatus.QUEUED -> Icons.Default.Schedule
            DownloadStatus.DOWNLOADING -> Icons.Default.CloudDownload
            DownloadStatus.WAITING_NETWORK -> Icons.Default.Schedule
            DownloadStatus.COMPLETED -> Icons.Default.CheckCircle
            DownloadStatus.FAILED -> Icons.Default.Error
            DownloadStatus.CANCELLED -> Icons.Default.Cancel
        },
        contentDescription = null,
        tint = when (status) {
            DownloadStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
            DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
            DownloadStatus.WAITING_NETWORK -> MaterialTheme.colorScheme.onSurfaceVariant
            DownloadStatus.COMPLETED -> Color(0xFF4CAF50)
            DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
            DownloadStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.size(24.dp)
    )
}

@Composable
private fun DownloadTaskActionButton(
    task: DownloadTask,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    actionsEnabled: Boolean
) {
    when (task.status) {
        DownloadStatus.QUEUED,
        DownloadStatus.WAITING_NETWORK,
        DownloadStatus.DOWNLOADING -> {
            val cancellable = isDownloadTaskCancellable(task)
            val actionEnabled = cancellable && actionsEnabled
            IconButton(onClick = onCancel, enabled = actionEnabled) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(
                        if (actionEnabled) R.string.download_cancel_download else R.string.download_finalizing
                    ),
                    tint = if (actionEnabled) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        DownloadStatus.CANCELLED,
        DownloadStatus.FAILED -> {
            IconButton(onClick = onResume, enabled = actionsEnabled) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.download_to_local),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        else -> Unit
    }
}

@Composable
private fun DownloadTaskProgressSection(task: DownloadTask) {
    when (task.status) {
        DownloadStatus.QUEUED -> {
            Text(
                text = stringResource(R.string.download_queued_status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DownloadStatus.WAITING_NETWORK -> {
            Text(
                text = stringResource(R.string.download_waiting_network_recovery),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            task.progress?.let { progress ->
                DownloadTaskRetainedProgress(progress)
            }
        }

        DownloadStatus.DOWNLOADING -> {
            val progress = task.progress
            if (progress == null) {
                DownloadTaskIndeterminateProgress()
                return
            }
            if (progress.stage == AudioDownloadManager.DownloadStage.WAITING_RETRY) {
                Text(
                    text = stringResource(R.string.download_waiting_network_recovery),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DownloadTaskRetainedProgress(progress)
                return
            }
            if (progress.stage == AudioDownloadManager.DownloadStage.FINALIZING) {
                Text(
                    text = stringResource(R.string.download_finalizing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                DownloadTaskIndeterminateProgress()
                return
            }
            Text(
                text = formatDownloadTransferProgress(progress),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (progress.totalBytes > 0L) {
                val progressFraction = remember(progress.bytesRead, progress.totalBytes) {
                    (progress.bytesRead.toFloat() / progress.totalBytes.toFloat())
                        .coerceIn(0f, 1f)
                }
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
            } else {
                DownloadTaskIndeterminateProgress()
            }
        }

        DownloadStatus.COMPLETED -> {
            Text(
                text = stringResource(R.string.download_completed),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF4CAF50)
            )
        }

        DownloadStatus.FAILED -> {
            Text(
                text = stringResource(R.string.download_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        DownloadStatus.CANCELLED -> {
            Text(
                text = stringResource(R.string.download_cancelled_status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DownloadTaskRetainedProgress(progress: AudioDownloadManager.DownloadProgress) {
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = formatDownloadTransferProgress(progress, showSpeed = false),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary
    )
    Spacer(modifier = Modifier.height(4.dp))
    if (progress.totalBytes > 0L) {
        val progressFraction = (progress.bytesRead.toFloat() / progress.totalBytes.toFloat())
            .coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
        )
    } else {
        DownloadTaskIndeterminateProgress()
    }
}

@Composable
private fun DownloadTaskIndeterminateProgress() {
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
    )
}
