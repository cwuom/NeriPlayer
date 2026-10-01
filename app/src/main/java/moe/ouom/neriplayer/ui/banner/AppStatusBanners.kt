package moe.ouom.neriplayer.ui.banner

import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.data.model.download.ManagedLibraryProcessingState
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import kotlin.math.abs

internal const val MANAGED_LIBRARY_PROCESSING_Z_INDEX = 3f
internal val MANAGED_LIBRARY_PROCESSING_REVEAL_EDGE = 96.dp
internal val MANAGED_LIBRARY_PROCESSING_DRAG_THRESHOLD = 24.dp
internal fun shouldExpandManagedProcessingBannerFromDrag(
    startY: Float,
    totalX: Float,
    totalY: Float,
    edgePx: Float,
    thresholdPx: Float
): Boolean = startY <= edgePx &&
    totalY >= thresholdPx &&
    totalY > abs(totalX)

@Composable
internal fun OfflineModeBottomBanner() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(CoreCommonR.string.offline_mode_bottom_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

internal fun Modifier.managedProcessingRevealGesture(
    collapsed: Boolean,
    edgePx: Float,
    thresholdPx: Float,
    onExpand: () -> Unit
): Modifier {
    if (!collapsed) return this
    return pointerInput(collapsed, edgePx, thresholdPx) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial
            )
            if (down.position.y <= edgePx) {
                trackManagedProcessingRevealDrag(
                    pointerId = down.id,
                    tracker = ManagedProcessingRevealDragTracker(
                        startX = down.position.x,
                        startY = down.position.y,
                        edgePx = edgePx,
                        thresholdPx = thresholdPx
                    ),
                    onExpand = onExpand
                )
            }
        }
    }
}

private suspend fun AwaitPointerEventScope.trackManagedProcessingRevealDrag(
    pointerId: PointerId,
    tracker: ManagedProcessingRevealDragTracker,
    onExpand: () -> Unit
) {
    while (tracker.active) {
        val change = awaitPointerEvent(PointerEventPass.Final)
            .changes.firstOrNull { it.id == pointerId }
        processManagedProcessingRevealChange(tracker, change, onExpand)
    }
}

private fun processManagedProcessingRevealChange(
    tracker: ManagedProcessingRevealDragTracker,
    change: PointerInputChange?,
    onExpand: () -> Unit
) {
    change?.let { applyManagedProcessingRevealChange(tracker, it, onExpand) }
        ?: tracker.cancel()
}

private fun applyManagedProcessingRevealChange(
    tracker: ManagedProcessingRevealDragTracker,
    change: PointerInputChange,
    onExpand: () -> Unit
) {
    if (tracker.onMotion(change.position.x, change.position.y, change.pressed)) {
        change.consume()
        onExpand()
    }
}

internal class ManagedProcessingRevealDragTracker(
    private val startX: Float,
    private val startY: Float,
    private val edgePx: Float,
    private val thresholdPx: Float
) {
    private var previousX = startX
    private var previousY = startY
    private var totalX = 0f
    private var totalY = 0f
    var active = true
        private set

    fun onMotion(x: Float, y: Float, pressed: Boolean): Boolean {
        totalX += x - previousX
        totalY += y - previousY
        previousX = x
        previousY = y
        val expanded = pressed && shouldExpandManagedProcessingBannerFromDrag(
            startY = startY,
            totalX = totalX,
            totalY = totalY,
            edgePx = edgePx,
            thresholdPx = thresholdPx
        )
        active = pressed && !expanded
        return expanded
    }

    fun cancel() {
        active = false
    }
}

internal fun managedProcessingTitleResource(
    reason: ManagedLibraryProcessingReason?
): Int? = when (reason) {
    ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE ->
        CoreCommonR.string.managed_library_processing_upgrade_title
    ManagedLibraryProcessingReason.DIRECTORY_CHANGE ->
        CoreCommonR.string.managed_library_processing_directory_title
    null -> null
}

internal fun managedProcessingStageResource(
    stage: ManagedDownloadStorage.MigrationStage?
): Int? = when (stage) {
    ManagedDownloadStorage.MigrationStage.PREPARING ->
        CoreCommonR.string.settings_download_directory_migrating_stage_preparing
    ManagedDownloadStorage.MigrationStage.COPYING ->
        CoreCommonR.string.settings_download_directory_migrating_stage_copying
    ManagedDownloadStorage.MigrationStage.REWRITING_METADATA ->
        CoreCommonR.string.settings_download_directory_migrating_stage_rewriting
    ManagedDownloadStorage.MigrationStage.VERIFYING ->
        CoreCommonR.string.settings_download_directory_migrating_stage_verifying
    ManagedDownloadStorage.MigrationStage.CLEANING_UP ->
        CoreCommonR.string.settings_download_directory_migrating_stage_cleanup
    ManagedDownloadStorage.MigrationStage.FINALIZING ->
        CoreCommonR.string.settings_download_directory_migrating
    null -> null
}

internal data class ManagedProcessingCount(val processed: Int, val total: Int)

internal fun managedProcessingCount(
    state: ManagedLibraryProcessingState,
    migrationProgress: ManagedDownloadStorage.MigrationProgress?
): ManagedProcessingCount? {
    val processed = managedProcessingProcessedCount(migrationProgress?.stageProcessed, state.processed)
    val total = managedProcessingTotalCount(migrationProgress?.stageTotal, state.total)
    return if (processed != null && total != null) {
        ManagedProcessingCount(processed, total)
    } else {
        null
    }
}

private fun managedProcessingProcessedCount(stage: Int?, overall: Int?): Int? =
    (stage ?: overall)?.coerceAtLeast(0)

private fun managedProcessingTotalCount(stage: Int?, overall: Int?): Int? =
    positiveManagedProcessingTotal(stage) ?: positiveManagedProcessingTotal(overall)

private fun positiveManagedProcessingTotal(value: Int?): Int? =
    if (value != null && value > 0) value else null

internal fun managedProcessingFraction(
    count: ManagedProcessingCount?,
    migrationProgress: ManagedDownloadStorage.MigrationProgress?
): Float? {
    val stageFraction = count?.let {
        (it.processed.toFloat() / it.total.toFloat()).coerceIn(0f, 1f)
    }
    return migrationProgress?.fraction?.coerceIn(0f, 1f) ?: stageFraction
}

internal data class ManagedProcessingBytes(
    val labelResource: Int,
    val completed: Long,
    val total: Long
)

internal fun managedProcessingBytes(
    migrationProgress: ManagedDownloadStorage.MigrationProgress?
): ManagedProcessingBytes? {
    val progress = migrationProgress ?: return null
    val bytes = when (progress.stage) {
        ManagedDownloadStorage.MigrationStage.VERIFYING ->
            ManagedProcessingBytes(
                CoreCommonR.string.settings_download_directory_migrating_verification_progress_bytes,
                progress.verifiedBytes,
                progress.verificationBytesTotal
            )
        ManagedDownloadStorage.MigrationStage.COPYING ->
            ManagedProcessingBytes(
                CoreCommonR.string.settings_download_directory_migrating_progress_bytes,
                progress.copiedBytes,
                progress.totalBytes
            )
        else -> return null
    }
    return bytes.takeIf { it.total > 0L }
}

private fun Float?.orZero(): Float = this ?: 0f

@Composable
internal fun ManagedLibraryProcessingBanner(
    state: ManagedLibraryProcessingState,
    migrationProgress: ManagedDownloadStorage.MigrationProgress?,
    onCollapsedChange: (Boolean) -> Unit,
    modifier: Modifier,
    interactive: Boolean
) {
    val titleResource = managedProcessingTitleResource(state.reason) ?: return
    val waitingForRetry = state is ManagedLibraryProcessingState.WaitingForRetry
    val count = managedProcessingCount(state, migrationProgress)
    val progressFraction = managedProcessingFraction(count, migrationProgress)
    val animatedProgressFraction by animateFloatAsState(
        targetValue = progressFraction.orZero(),
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "managed library processing progress"
    )
    val dragThresholdPx = with(LocalDensity.current) {
        MANAGED_LIBRARY_PROCESSING_DRAG_THRESHOLD.toPx()
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .animateContentSize(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            )
            .managedProcessingCollapseGesture(interactive, dragThresholdPx, onCollapsedChange)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            ManagedProcessingHeader(
                title = stringResource(titleResource),
                interactive = interactive,
                onCollapsedChange = onCollapsedChange
            )
            ManagedProcessingSubtitle(waitingForRetry)
            managedProcessingStageRow(migrationProgress).Render()
            ManagedProcessingCountText(count)
            ManagedProcessingByteText(managedProcessingBytes(migrationProgress))
            managedProcessingFileRow(migrationProgress).Render()
            ManagedProcessingProgressIndicator(waitingForRetry, progressFraction, animatedProgressFraction)
        }
    }
}

private fun Modifier.managedProcessingCollapseGesture(
    interactive: Boolean,
    dragThresholdPx: Float,
    onCollapsedChange: (Boolean) -> Unit
): Modifier = if (interactive) {
    pointerInput(dragThresholdPx) {
        val dragTracker = ManagedProcessingCollapseDragTracker(dragThresholdPx)
        detectVerticalDragGestures(
            onDragStart = { dragTracker.reset() },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                if (dragTracker.addDrag(dragAmount)) onCollapsedChange(true)
            }
        )
    }
} else {
    this
}

internal class ManagedProcessingCollapseDragTracker(private val thresholdPx: Float) {
    private var accumulatedDragPx = 0f
    private var collapsed = false

    fun reset() {
        accumulatedDragPx = 0f
        collapsed = false
    }

    fun addDrag(dragAmount: Float): Boolean {
        if (collapsed) return false
        accumulatedDragPx += dragAmount
        if (accumulatedDragPx > -thresholdPx) return false
        collapsed = true
        return true
    }
}

@Composable
private fun ManagedProcessingHeader(
    title: String,
    interactive: Boolean,
    onCollapsedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        IconButton(enabled = interactive, onClick = { onCollapsedChange(true) }) {
            Icon(
                imageVector = Icons.Outlined.ExpandLess,
                contentDescription = stringResource(CoreCommonR.string.action_collapse)
            )
        }
    }
}

@Composable
private fun ManagedProcessingSubtitle(waitingForRetry: Boolean) {
    Text(
        text = stringResource(
            if (waitingForRetry) {
                CoreCommonR.string.managed_library_processing_retry
            } else {
                CoreCommonR.string.managed_library_processing_subtitle
            }
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

internal sealed interface ManagedProcessingOptionalRow {
    @Composable
    fun Render()
}

internal data object HiddenManagedProcessingRow : ManagedProcessingOptionalRow {
    @Composable
    override fun Render() = Unit
}

internal data class ManagedProcessingStageRow(val labelResource: Int) : ManagedProcessingOptionalRow {
    @Composable
    override fun Render() {
        Text(
            text = stringResource(labelResource),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

internal fun managedProcessingStageRow(
    migrationProgress: ManagedDownloadStorage.MigrationProgress?
): ManagedProcessingOptionalRow = managedProcessingStageResource(migrationProgress?.stage)
    ?.let(::ManagedProcessingStageRow) ?: HiddenManagedProcessingRow

@Composable
private fun ManagedProcessingCountText(count: ManagedProcessingCount?) {
    val visibleCount = count ?: return
    Text(
        text = stringResource(
            CoreCommonR.string.managed_library_processing_progress,
            visibleCount.processed,
            visibleCount.total
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ManagedProcessingByteText(bytes: ManagedProcessingBytes?) {
    val visibleBytes = bytes ?: return
    val context = LocalContext.current
    Text(
        text = stringResource(
            visibleBytes.labelResource,
            Formatter.formatShortFileSize(context, visibleBytes.completed.coerceAtLeast(0L)),
            Formatter.formatShortFileSize(context, visibleBytes.total)
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

internal data class ManagedProcessingFileRow(val fileName: String) : ManagedProcessingOptionalRow {
    @Composable
    override fun Render() {
        Text(
            text = stringResource(CoreCommonR.string.settings_download_directory_migrating_current, fileName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

internal fun managedProcessingFileRow(
    migrationProgress: ManagedDownloadStorage.MigrationProgress?
): ManagedProcessingOptionalRow = migrationProgress?.currentFileName
    ?.takeIf(String::isNotBlank)?.let(::ManagedProcessingFileRow) ?: HiddenManagedProcessingRow

@Composable
private fun ManagedProcessingProgressIndicator(
    waitingForRetry: Boolean,
    progressFraction: Float?,
    animatedProgressFraction: Float
) {
    if (waitingForRetry) return
    managedProcessingIndicatorKind(progressFraction).Render(animatedProgressFraction)
}

internal sealed interface ManagedProcessingIndicatorKind {
    @Composable
    fun Render(animatedProgressFraction: Float)
}

internal data object IndeterminateManagedProcessingIndicator : ManagedProcessingIndicatorKind {
    @Composable
    override fun Render(animatedProgressFraction: Float) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

internal data object DeterminateManagedProcessingIndicator : ManagedProcessingIndicatorKind {
    @Composable
    override fun Render(animatedProgressFraction: Float) {
        LinearProgressIndicator(
            progress = { animatedProgressFraction },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

internal fun managedProcessingIndicatorKind(fraction: Float?): ManagedProcessingIndicatorKind =
    if (fraction == null) IndeterminateManagedProcessingIndicator
    else DeterminateManagedProcessingIndicator
