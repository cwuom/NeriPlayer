package moe.ouom.neriplayer.ui.screen.playlist.curation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.stringResId
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsInlineMessage
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton

@Composable
internal fun LocalCurationProgressContent(
    session: LocalCurationSession,
    onStop: () -> Unit
) {
    val applying = session.stage == LocalCurationStage.APPLYING
    val done = if (applying) session.applyingDoneCount else session.analyzedCount
    val total = if (applying) session.applyTotal else session.total
    val fraction by animateFloatAsState(
        targetValue = if (total > 0) done.toFloat() / total else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "local-curation-progress"
    )
    val visibleItems = if (applying) {
        session.items.filter {
            it.hasSelection || it.phase == LocalCurationItemPhase.APPLIED ||
                it.phase == LocalCurationItemPhase.FAILED
        }
    } else {
        session.items
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            if (applying) {
                                CoreCommonR.string.local_curation_applying_progress
                            } else {
                                CoreCommonR.string.local_curation_analyzing_progress
                            },
                            done,
                            total
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${(fraction * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    strokeCap = StrokeCap.Round,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
                if (!applying) LocalCurationStatsRow(session)
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(visibleItems, key = LocalCurationItem::key) { item ->
                LocalCurationActivityRow(item = item, modifier = Modifier.animateItem())
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MiuixSettingsOutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (applying) {
                            CoreCommonR.string.local_curation_stop_applying
                        } else {
                            CoreCommonR.string.local_curation_stop_analyzing
                        }
                    )
                )
            }
            if (applying) {
                Text(
                    text = stringResource(CoreCommonR.string.local_curation_stop_applying_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
internal fun LocalCurationFinishedContent(
    session: LocalCurationSession,
    onRetry: () -> Unit,
    onDone: () -> Unit
) {
    val hasFailures = session.failedCount > 0
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val badgeScale by animateFloatAsState(
        targetValue = if (revealed) 1f else 0.6f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow),
        label = "local-curation-finished-badge"
    )
    val failedItems = session.items.filter { it.phase == LocalCurationItemPhase.FAILED }
    var writeAccessDenied by remember { mutableStateOf(false) }
    val requestWriteAccess = rememberLocalCurationWriteAccessRequester(
        songs = { failedItems.map { it.song } },
        onGranted = onRetry,
        onDenied = { writeAccessDenied = true }
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(76.dp)
                .graphicsLayer {
                    scaleX = badgeScale
                    scaleY = badgeScale
                }
                .clip(CircleShape)
                .background(
                    if (hasFailures) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (hasFailures) Icons.Outlined.ErrorOutline else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (hasFailures) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
                modifier = Modifier.size(40.dp)
            )
        }
        Text(
            text = pluralStringResource(
                CoreCommonR.plurals.local_curation_written_count,
                session.appliedCount,
                session.appliedCount
            ),
            style = MaterialTheme.typography.titleLarge
        )
        if (hasFailures) {
            Text(
                text = pluralStringResource(
                    CoreCommonR.plurals.local_curation_failed_count,
                    session.failedCount,
                    session.failedCount
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .weight(1f, fill = false)
            ) {
                items(failedItems, key = LocalCurationItem::key) { item ->
                    LocalCurationActivityRow(item = item)
                }
            }
            if (writeAccessDenied) {
                MiuixSettingsInlineMessage(
                    message = stringResource(CoreCommonR.string.local_curation_write_access_denied),
                    isSuccess = false,
                    onClose = { writeAccessDenied = false }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (hasFailures) {
                MiuixSettingsOutlinedButton(
                    onClick = requestWriteAccess,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(CoreCommonR.string.local_curation_retry_failed))
                }
            }
            MiuixSettingsButton(onClick = onDone, modifier = Modifier.weight(1f)) {
                Text(stringResource(CoreCommonR.string.action_done))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalCurationStatsRow(session: LocalCurationSession) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CurationStatPill(
            text = stringResource(CoreCommonR.string.local_curation_stat_ready, session.count(LocalCurationItemPhase.READY)),
            dotColor = MaterialTheme.colorScheme.primary
        )
        CurationStatPill(
            text = stringResource(
                CoreCommonR.string.local_curation_stat_complete,
                session.count(LocalCurationItemPhase.COMPLETE)
            ),
            dotColor = MaterialTheme.colorScheme.tertiary
        )
        CurationStatPill(
            text = stringResource(
                CoreCommonR.string.local_curation_stat_no_match,
                session.count(LocalCurationItemPhase.NO_MATCH)
            ),
            dotColor = MaterialTheme.colorScheme.outline
        )
        val unsupported = session.count(LocalCurationItemPhase.UNSUPPORTED)
        if (unsupported > 0) {
            CurationStatPill(
                text = stringResource(CoreCommonR.string.local_curation_stat_unsupported, unsupported),
                dotColor = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun CurationStatPill(text: String, dotColor: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(text = text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun LocalCurationActivityRow(item: LocalCurationItem, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = item.phase,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.7f))
                        .togetherWith(fadeOut(tween(100)))
                },
                label = "local-curation-phase-icon"
            ) { phase ->
                LocalCurationPhaseIcon(phase)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.song.displayName(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = localCurationItemStatus(item),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.phase == LocalCurationItemPhase.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LocalCurationPhaseIcon(phase: LocalCurationItemPhase) {
    val colors = MaterialTheme.colorScheme
    when (phase) {
        LocalCurationItemPhase.ANALYZING,
        LocalCurationItemPhase.APPLYING -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp
        )
        LocalCurationItemPhase.QUEUED -> PhaseIcon(Icons.Outlined.HourglassEmpty, colors.outline)
        LocalCurationItemPhase.READY -> PhaseIcon(Icons.Outlined.TaskAlt, colors.primary)
        LocalCurationItemPhase.COMPLETE -> PhaseIcon(Icons.Outlined.CheckCircle, colors.tertiary)
        LocalCurationItemPhase.NO_MATCH -> PhaseIcon(Icons.Outlined.SearchOff, colors.outline)
        LocalCurationItemPhase.UNSUPPORTED -> PhaseIcon(Icons.Outlined.Block, colors.error)
        LocalCurationItemPhase.APPLIED -> PhaseIcon(Icons.Filled.CheckCircle, colors.primary)
        LocalCurationItemPhase.FAILED -> PhaseIcon(Icons.Outlined.ErrorOutline, colors.error)
    }
}

@Composable
private fun PhaseIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
}

@Composable
internal fun localCurationItemStatus(item: LocalCurationItem): String = when (item.phase) {
    LocalCurationItemPhase.QUEUED -> stringResource(CoreCommonR.string.local_curation_phase_queued)
    LocalCurationItemPhase.ANALYZING,
    LocalCurationItemPhase.APPLYING -> item.song.displayArtist()
    LocalCurationItemPhase.READY -> localCurationProposalSummary(item)
    LocalCurationItemPhase.COMPLETE -> stringResource(CoreCommonR.string.local_curation_phase_complete)
    LocalCurationItemPhase.NO_MATCH -> stringResource(CoreCommonR.string.local_curation_phase_no_match)
    LocalCurationItemPhase.UNSUPPORTED -> stringResource(CoreCommonR.string.local_curation_phase_unsupported)
    LocalCurationItemPhase.APPLIED -> stringResource(CoreCommonR.string.local_curation_phase_applied)
    LocalCurationItemPhase.FAILED -> stringResource(
        when (item.failure) {
            LocalCurationFailure.COVER_PREPARE -> CoreCommonR.string.local_curation_failure_cover_prepare
            LocalCurationFailure.COVER_WRITE -> CoreCommonR.string.local_curation_failure_cover_write
            LocalCurationFailure.FILE_WRITE -> CoreCommonR.string.local_curation_failure_file_write
            LocalCurationFailure.LYRICS_WRITE, null -> CoreCommonR.string.local_curation_failure_lyrics
        }
    )
}

@Composable
private fun localCurationProposalSummary(item: LocalCurationItem): String {
    val parts = buildList {
        item.lyric?.let { lyric ->
            add(
                stringResource(CoreCommonR.string.local_curation_section_lyrics) + " · " +
                    lyricOriginLabel(lyric)
            )
        }
        item.cover?.let { cover ->
            add(
                stringResource(CoreCommonR.string.local_curation_section_cover) + " · " +
                    coverOriginLabel(cover)
            )
        }
    }
    return parts.joinToString("  ")
}

@Composable
internal fun lyricOriginLabel(lyric: LocalCurationLyricProposal): String {
    val source = lyric.source
    return if (lyric.origin == LocalCurationLyricOrigin.EXISTING || source == null) {
        stringResource(CoreCommonR.string.local_curation_lyric_existing)
    } else {
        stringResource(source.stringResId())
    }
}

@Composable
internal fun coverOriginLabel(cover: LocalCurationCoverProposal): String = stringResource(
    when (cover.origin) {
        LocalCurationCoverOrigin.FOLDER -> CoreCommonR.string.local_curation_cover_folder
        LocalCurationCoverOrigin.ONLINE -> CoreCommonR.string.local_curation_cover_online
    }
)
