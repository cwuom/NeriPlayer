package moe.ouom.neriplayer.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.bili.BiliVideoSkipTargetOption
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipInterval
import moe.ouom.neriplayer.data.platform.bili.BiliVideoSkipTarget
import moe.ouom.neriplayer.data.platform.bili.normalizeBiliVideoSkipIntervals

private const val MAX_BILI_VIDEO_SKIP_TIMESTAMP_MS = 24L * 60L * 60L * 1_000L
internal const val BILI_VIDEO_SKIP_SMALL_SEEK_STEP_MS = 1_000L
internal const val BILI_VIDEO_SKIP_LARGE_SEEK_STEP_MS = 5_000L
internal const val BILI_VIDEO_SKIP_START_INPUT_TEST_TAG = "bili_video_skip_start_input"
internal const val BILI_VIDEO_SKIP_END_INPUT_TEST_TAG = "bili_video_skip_end_input"
internal const val BILI_VIDEO_SKIP_ADD_BUTTON_TEST_TAG = "bili_video_skip_add_button"

internal fun parseBiliVideoSkipTimestamp(value: String): Long? {
    val trimmedValue = value.trim()
    val rawSeconds = trimmedValue.toLongOrNull()
    if (rawSeconds != null) {
        return rawSeconds
            .takeIf { it in 0L..(MAX_BILI_VIDEO_SKIP_TIMESTAMP_MS / 1_000L) }
            ?.times(1_000L)
    }

    val parts = trimmedValue.split(':')
    if (parts.size !in 2..3) return null
    val values = parts.map { part -> part.trim().toLongOrNull() ?: return null }
    val totalSeconds = when (parts.size) {
        2 -> {
            val minutes = values[0]
            val seconds = values[1]
            if (minutes < 0L || seconds !in 0L..59L) return null
            minutes * 60L + seconds
        }

        else -> {
            val hours = values[0]
            val minutes = values[1]
            val seconds = values[2]
            if (hours < 0L || minutes !in 0L..59L || seconds !in 0L..59L) return null
            hours * 60L * 60L + minutes * 60L + seconds
        }
    }
    if (totalSeconds > MAX_BILI_VIDEO_SKIP_TIMESTAMP_MS / 1_000L) return null
    return totalSeconds * 1_000L
}

internal fun formatBiliVideoSkipTimestamp(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:" +
            seconds.toString().padStart(2, '0')
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

internal fun moveBiliVideoSkipPlaybackPosition(
    currentPositionMs: Long,
    moveForward: Boolean,
    durationMs: Long,
    stepMs: Long = BILI_VIDEO_SKIP_LARGE_SEEK_STEP_MS
): Long {
    val maximumPositionMs = durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
    val boundedCurrentPositionMs = currentPositionMs.coerceIn(0L, maximumPositionMs)
    val boundedStepMs = stepMs.coerceAtLeast(1L)
    return if (moveForward) {
        if (boundedCurrentPositionMs >= maximumPositionMs - boundedStepMs) {
            maximumPositionMs
        } else {
            boundedCurrentPositionMs + boundedStepMs
        }
    } else if (boundedCurrentPositionMs <= boundedStepMs) {
        0L
    } else {
        boundedCurrentPositionMs - boundedStepMs
    }
}

internal fun appendBiliVideoSkipInterval(
    existingIntervals: List<BiliVideoSkipInterval>,
    startMs: Long,
    endMs: Long,
    durationMs: Long
): List<BiliVideoSkipInterval> {
    return normalizeBiliVideoSkipIntervals(
        existingIntervals + BiliVideoSkipInterval(startMs = startMs, endMs = endMs),
        durationMs
    )
}

internal fun shouldReloadBiliVideoSkipIntervals(
    draftTarget: BiliVideoSkipTarget?,
    selectedTarget: BiliVideoSkipTarget,
    hasLocalIntervalEdits: Boolean
): Boolean {
    return draftTarget != selectedTarget || !hasLocalIntervalEdits
}

@Composable
internal fun BiliVideoSkipIntervalsContent(
    title: String,
    targetResolverKey: Any,
    loadTargetOptions: suspend () -> List<BiliVideoSkipTargetOption>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialTarget: BiliVideoSkipTarget? = null,
    currentPlaybackPositionMs: Long? = null,
    currentPlaybackTarget: BiliVideoSkipTarget? = null,
    currentPlaybackIsPlaying: Boolean = false,
    onTogglePlayback: (() -> Unit)? = null,
    onSeekToPlaybackPosition: ((Long) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val videoSkipRepository = AppContainer.biliVideoSkipRepository
    val rules by videoSkipRepository.rules.collectAsStateWithLifecycle()
    val inputDrafts by videoSkipRepository.drafts.collectAsStateWithLifecycle()
    var targetOptions by remember { mutableStateOf<List<BiliVideoSkipTargetOption>>(emptyList()) }
    var selectedTarget by remember { mutableStateOf<BiliVideoSkipTargetOption?>(null) }
    var hasUserSelectedTarget by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var retryToken by remember { mutableIntStateOf(0) }
    var draftIntervals by remember { mutableStateOf<List<BiliVideoSkipInterval>>(emptyList()) }
    var draftIntervalsTarget by remember { mutableStateOf<BiliVideoSkipTarget?>(null) }
    var hasLocalIntervalEdits by remember { mutableStateOf(false) }
    var startText by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    var targetMenuExpanded by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var restoredInputDraftTarget by remember { mutableStateOf<BiliVideoSkipTarget?>(null) }
    var intervalPendingDeletion by remember { mutableStateOf<BiliVideoSkipInterval?>(null) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    val invalidTimeText = stringResource(R.string.bili_video_skip_invalid_time)
    val invalidRangeText = stringResource(R.string.bili_video_skip_invalid_range)
    val exceedsDurationText = stringResource(R.string.bili_video_skip_exceeds_duration)
    val saveFailedText = stringResource(R.string.bili_video_skip_save_failed)
    val deleteConfirmationText = stringResource(R.string.bili_video_skip_delete_confirm)
    val clearConfirmationText = stringResource(R.string.bili_video_skip_clear_confirm)

    LaunchedEffect(targetResolverKey, retryToken) {
        isLoading = true
        loadFailed = false
        restoredInputDraftTarget = null
        val options = try {
            loadTargetOptions()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        targetOptions = options
        selectedTarget = options.firstOrNull { option -> option.target == initialTarget }
            ?: options.firstOrNull()
        hasUserSelectedTarget = false
        isLoading = false
        loadFailed = options.isEmpty()
    }

    LaunchedEffect(initialTarget, targetOptions, hasUserSelectedTarget) {
        if (hasUserSelectedTarget || initialTarget == null) return@LaunchedEffect
        targetOptions.firstOrNull { option -> option.target == initialTarget }?.let { option ->
            selectedTarget = option
        }
    }

    LaunchedEffect(selectedTarget?.target, rules) {
        val target = selectedTarget?.target ?: return@LaunchedEffect
        if (shouldReloadBiliVideoSkipIntervals(
                draftTarget = draftIntervalsTarget,
                selectedTarget = target,
                hasLocalIntervalEdits = hasLocalIntervalEdits
            )
        ) {
            draftIntervals = rules.firstOrNull { rule ->
                !rule.isDeleted && rule.target == target
            }?.intervals.orEmpty()
            draftIntervalsTarget = target
            hasLocalIntervalEdits = false
            inputError = null
        }
    }

    LaunchedEffect(selectedTarget, inputDrafts) {
        val target = selectedTarget?.target ?: return@LaunchedEffect
        if (restoredInputDraftTarget == target) return@LaunchedEffect
        val inputDraft = inputDrafts.firstOrNull { draft -> draft.target == target }
        startText = inputDraft?.startText.orEmpty()
        endText = inputDraft?.endText.orEmpty()
        inputError = null
        restoredInputDraftTarget = target
    }

    fun saveInputDraft(updatedStartText: String = startText, updatedEndText: String = endText) {
        selectedTarget?.target?.let { target ->
            videoSkipRepository.saveDraft(
                target = target,
                startText = updatedStartText,
                endText = updatedEndText
            )
        }
    }

    fun lockSelectedTargetForEditing() {
        hasUserSelectedTarget = true
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
        }

        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }

            loadFailed || selectedTarget == null -> {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.bili_video_skip_load_failed)) },
                    trailingContent = {
                        IconButton(onClick = { retryToken++ }) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.action_retry)
                            )
                        }
                    }
                )
            }

            else -> {
                val activeTarget = checkNotNull(selectedTarget)
                Box(modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(activeTarget.label) },
                        supportingContent = {
                            if (activeTarget.durationMs > 0L) {
                                Text(formatBiliVideoSkipTimestamp(activeTarget.durationMs))
                            }
                        },
                        leadingContent = { Icon(Icons.Outlined.SkipNext, null) },
                        modifier = Modifier.clickable(enabled = targetOptions.size > 1) {
                            targetMenuExpanded = true
                        }
                    )
                    DropdownMenu(
                        expanded = targetMenuExpanded,
                        onDismissRequest = { targetMenuExpanded = false }
                    ) {
                        targetOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.durationMs.takeIf { it > 0L }?.let { duration ->
                                            "${option.label}  ${formatBiliVideoSkipTimestamp(duration)}"
                                        } ?: option.label
                                    )
                                },
                                onClick = {
                                    selectedTarget = option
                                    hasUserSelectedTarget = true
                                    targetMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = {
                            lockSelectedTargetForEditing()
                            startText = it
                            saveInputDraft(updatedStartText = it)
                            inputError = null
                        },
                        label = { Text(stringResource(R.string.bili_video_skip_start)) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(BILI_VIDEO_SKIP_START_INPUT_TEST_TAG)
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = {
                            lockSelectedTargetForEditing()
                            endText = it
                            saveInputDraft(updatedEndText = it)
                            inputError = null
                        },
                        label = { Text(stringResource(R.string.bili_video_skip_end)) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(BILI_VIDEO_SKIP_END_INPUT_TEST_TAG)
                    )
                }
                currentPlaybackPositionMs
                    ?.takeIf { activeTarget.target == currentPlaybackTarget }
                    ?.let { positionMs ->
                    val canControlPlayback =
                        onTogglePlayback != null &&
                            onSeekToPlaybackPosition != null
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                lockSelectedTargetForEditing()
                                val timestamp = formatBiliVideoSkipTimestamp(positionMs)
                                startText = timestamp
                                saveInputDraft(updatedStartText = timestamp)
                            }
                        ) {
                            Text(stringResource(R.string.bili_video_skip_use_current_start))
                        }
                        TextButton(
                            onClick = {
                                lockSelectedTargetForEditing()
                                val timestamp = formatBiliVideoSkipTimestamp(positionMs)
                                endText = timestamp
                                saveInputDraft(updatedEndText = timestamp)
                            }
                        ) {
                            Text(stringResource(R.string.bili_video_skip_use_current_end))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                onSeekToPlaybackPosition?.invoke(
                                    moveBiliVideoSkipPlaybackPosition(
                                        currentPositionMs = positionMs,
                                        moveForward = false,
                                        durationMs = activeTarget.durationMs,
                                        stepMs = BILI_VIDEO_SKIP_LARGE_SEEK_STEP_MS
                                    )
                                )
                            },
                            enabled = canControlPlayback,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Text(stringResource(R.string.bili_video_skip_rewind_five_seconds_short))
                        }
                        IconButton(
                            onClick = {
                                onSeekToPlaybackPosition?.invoke(
                                    moveBiliVideoSkipPlaybackPosition(
                                        currentPositionMs = positionMs,
                                        moveForward = false,
                                        durationMs = activeTarget.durationMs,
                                        stepMs = BILI_VIDEO_SKIP_SMALL_SEEK_STEP_MS
                                    )
                                )
                            },
                            enabled = canControlPlayback,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Text(stringResource(R.string.bili_video_skip_rewind_one_second_short))
                        }
                        IconButton(
                            onClick = { onTogglePlayback?.invoke() },
                            enabled = canControlPlayback,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (currentPlaybackIsPlaying) {
                                    Icons.Outlined.Pause
                                } else {
                                    Icons.Outlined.PlayArrow
                                },
                                contentDescription = stringResource(
                                    if (currentPlaybackIsPlaying) {
                                        R.string.player_pause
                                    } else {
                                        R.string.player_play
                                    }
                                )
                            )
                        }
                        IconButton(
                            onClick = {
                                onSeekToPlaybackPosition?.invoke(
                                    moveBiliVideoSkipPlaybackPosition(
                                        currentPositionMs = positionMs,
                                        moveForward = true,
                                        durationMs = activeTarget.durationMs,
                                        stepMs = BILI_VIDEO_SKIP_SMALL_SEEK_STEP_MS
                                    )
                                )
                            },
                            enabled = canControlPlayback,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Text(
                                stringResource(R.string.bili_video_skip_forward_one_second_short)
                            )
                        }
                        IconButton(
                            onClick = {
                                onSeekToPlaybackPosition?.invoke(
                                    moveBiliVideoSkipPlaybackPosition(
                                        currentPositionMs = positionMs,
                                        moveForward = true,
                                        durationMs = activeTarget.durationMs,
                                        stepMs = BILI_VIDEO_SKIP_LARGE_SEEK_STEP_MS
                                    )
                                )
                            },
                            enabled = canControlPlayback,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Text(
                                stringResource(R.string.bili_video_skip_forward_five_seconds_short)
                            )
                        }
                    }
                }
                inputError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Button(
                    onClick = {
                        lockSelectedTargetForEditing()
                        val startMs = parseBiliVideoSkipTimestamp(startText)
                        val endMs = parseBiliVideoSkipTimestamp(endText)
                        inputError = when {
                            startMs == null || endMs == null -> invalidTimeText
                            endMs <= startMs -> invalidRangeText
                            activeTarget.durationMs > 0L && endMs > activeTarget.durationMs -> {
                                exceedsDurationText
                            }

                            else -> null
                        }
                        if (inputError == null) {
                            draftIntervals = appendBiliVideoSkipInterval(
                                existingIntervals = draftIntervals,
                                startMs = startMs!!,
                                endMs = endMs!!,
                                durationMs = activeTarget.durationMs
                            )
                            draftIntervalsTarget = activeTarget.target
                            hasLocalIntervalEdits = true
                            startText = ""
                            endText = ""
                            saveInputDraft(updatedStartText = "", updatedEndText = "")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag(BILI_VIDEO_SKIP_ADD_BUTTON_TEST_TAG)
                ) {
                    Text(stringResource(R.string.bili_video_skip_add))
                }

                Spacer(Modifier.height(12.dp))
                if (draftIntervals.isEmpty()) {
                    Text(
                        text = stringResource(R.string.bili_video_skip_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    draftIntervals.forEachIndexed { index, interval ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    "${formatBiliVideoSkipTimestamp(interval.startMs)} - " +
                                        formatBiliVideoSkipTimestamp(interval.endMs)
                                )
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = {
                                        lockSelectedTargetForEditing()
                                        intervalPendingDeletion = interval
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.action_delete)
                                    )
                                }
                            }
                        )
                        if (index < draftIntervals.lastIndex) HorizontalDivider()
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            lockSelectedTargetForEditing()
                            showClearConfirmation = true
                        },
                        enabled = draftIntervals.isNotEmpty() && !isSaving,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.bili_video_skip_clear))
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                isSaving = true
                                val saved = runCatching {
                                    AppContainer.biliVideoSkipRepository.replaceIntervals(
                                        target = activeTarget.target,
                                        intervals = draftIntervals,
                                        durationMs = activeTarget.durationMs
                                    )
                                }.isSuccess
                                isSaving = false
                                if (saved) {
                                    onDismiss()
                                } else {
                                    inputError = saveFailedText
                                }
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.bili_video_skip_save))
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    intervalPendingDeletion?.let { interval ->
        AlertDialog(
            onDismissRequest = { intervalPendingDeletion = null },
            title = { Text(stringResource(R.string.dialog_confirm_delete)) },
            text = { Text(deleteConfirmationText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        lockSelectedTargetForEditing()
                        draftIntervals = draftIntervals.filterNot { it == interval }
                        hasLocalIntervalEdits = true
                        intervalPendingDeletion = null
                    }
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { intervalPendingDeletion = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.dialog_confirm_clear)) },
            text = { Text(clearConfirmationText) },
            confirmButton = {
                TextButton(
                    onClick = {
                        lockSelectedTargetForEditing()
                        draftIntervals = emptyList()
                        hasLocalIntervalEdits = true
                        showClearConfirmation = false
                    }
                ) {
                    Text(stringResource(R.string.bili_video_skip_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BiliVideoSkipIntervalsSheet(
    title: String,
    targetResolverKey: Any,
    loadTargetOptions: suspend () -> List<BiliVideoSkipTargetOption>,
    initialTarget: BiliVideoSkipTarget? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        BiliVideoSkipIntervalsContent(
            title = title,
            targetResolverKey = targetResolverKey,
            loadTargetOptions = loadTargetOptions,
            initialTarget = initialTarget,
            onDismiss = onDismiss
        )
    }
}
