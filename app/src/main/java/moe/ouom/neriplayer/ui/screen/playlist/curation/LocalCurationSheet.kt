package moe.ouom.neriplayer.ui.screen.playlist.curation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.format.formatFileSize
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet

internal enum class LocalCurationScreen {
    CONFIGURE,
    ANALYZING,
    REVIEW,
    APPLYING,
    FINISHED
}

internal fun LocalCurationSession?.toScreen(): LocalCurationScreen = when (this?.stage) {
    null -> LocalCurationScreen.CONFIGURE
    LocalCurationStage.ANALYZING -> LocalCurationScreen.ANALYZING
    LocalCurationStage.REVIEW -> LocalCurationScreen.REVIEW
    LocalCurationStage.APPLYING -> LocalCurationScreen.APPLYING
    LocalCurationStage.FINISHED -> LocalCurationScreen.FINISHED
}

/**
 * 批量词图入口。会话由 [LocalCurationRuntime] 持有：关闭面板不会中断后台分析或写入，
 * 再次打开时直接回到当前进度；只有完成后关闭才会清空会话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalCurationSheet(
    songs: List<SongItem>,
    onDismissRequest: () -> Unit,
    coordinator: LocalCurationCoordinator = LocalCurationRuntime.coordinator
) {
    val session by coordinator.session.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf(coordinator.lastOptions()) }

    fun dismissAnimated() {
        scope.launch {
            runCatching { sheetState.hide() }
            if (coordinator.session.value?.stage == LocalCurationStage.FINISHED) {
                coordinator.dismiss()
            }
            onDismissRequest()
        }
    }

    ModalBottomSheet(
        onDismissRequest = ::dismissAnimated,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
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
            LocalCurationHeader(
                session = session,
                songCount = songs.size,
                onClose = ::dismissAnimated
            )
            AnimatedContent(
                targetState = session.toScreen(),
                transitionSpec = {
                    (fadeIn(tween(240, delayMillis = 80, easing = FastOutSlowInEasing)) +
                        slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 10 })
                        .togetherWith(fadeOut(tween(120)))
                        .using(SizeTransform(clip = false))
                },
                label = "local-curation-stage"
            ) { screen ->
                when (screen) {
                    LocalCurationScreen.CONFIGURE -> LocalCurationConfigureContent(
                        songs = songs,
                        options = options,
                        onOptionsChange = { options = it },
                        onStart = { coordinator.start(songs, options) }
                    )
                    LocalCurationScreen.ANALYZING,
                    LocalCurationScreen.APPLYING -> session?.let { current ->
                        LocalCurationProgressContent(
                            session = current,
                            onStop = coordinator::cancel
                        )
                    }
                    LocalCurationScreen.REVIEW -> session?.let { current ->
                        LocalCurationReviewContent(
                            session = current,
                            onLyricSelected = coordinator::setLyricSelected,
                            onCoverSelected = coordinator::setCoverSelected,
                            onChooseAlternative = coordinator::chooseLyricAlternative,
                            onSelectAll = coordinator::setAllSelected,
                            onDiscard = coordinator::dismiss,
                            onApply = { coordinator.apply() }
                        )
                    }
                    LocalCurationScreen.FINISHED -> session?.let { current ->
                        LocalCurationFinishedContent(
                            session = current,
                            onRetry = { coordinator.retryFailed() },
                            onDone = ::dismissAnimated
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalCurationHeader(
    session: LocalCurationSession?,
    songCount: Int,
    onClose: () -> Unit
) {
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
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(CoreCommonR.string.local_curation_title),
                style = MaterialTheme.typography.titleLarge
            )
            AnimatedContent(
                targetState = localCurationHeaderSubtitle(session, songCount),
                transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(120))) },
                label = "local-curation-subtitle"
            ) { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        HapticIconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(CoreCommonR.string.cd_close))
        }
    }
}

@Composable
private fun localCurationHeaderSubtitle(session: LocalCurationSession?, songCount: Int): String {
    return when (session?.stage) {
        null -> pluralStringResource(CoreCommonR.plurals.local_curation_song_count, songCount, songCount)
        LocalCurationStage.ANALYZING -> stringResource(
            CoreCommonR.string.local_curation_analyzing_progress,
            session.analyzedCount,
            session.total
        )
        LocalCurationStage.REVIEW -> pluralStringResource(
            CoreCommonR.plurals.local_curation_song_count,
            session.total,
            session.total
        )
        LocalCurationStage.APPLYING -> stringResource(
            CoreCommonR.string.local_curation_applying_progress,
            session.applyingDoneCount,
            session.applyTotal
        )
        LocalCurationStage.FINISHED -> stringResource(
            if (session.cancelled) {
                CoreCommonR.string.local_curation_finished_stopped
            } else {
                CoreCommonR.string.local_curation_finished_title
            }
        )
    }
}

/** 面板内可滚动区域的最大高度，按当前窗口高度取比例，分屏与小窗下同样适用 */
@Composable
internal fun localCurationMaxHeight(fraction: Float): Dp {
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    return with(LocalDensity.current) { (windowHeightPx * fraction).toDp() }
}

@Composable
internal fun localCurationSizeDeltaText(bytes: Long): String {
    return if (bytes >= 0L) {
        stringResource(CoreCommonR.string.local_curation_review_grow, formatFileSize(bytes))
    } else {
        stringResource(CoreCommonR.string.local_curation_review_shrink, formatFileSize(-bytes))
    }
}
