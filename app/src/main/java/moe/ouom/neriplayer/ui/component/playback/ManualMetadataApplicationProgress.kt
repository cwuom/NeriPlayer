package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.metadata.SongMetadataApplyStage
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.viewmodel.ManualMetadataApplicationState
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriMotion
import kotlin.time.Duration.Companion.milliseconds

private const val MetadataProgressAnimationMs = 240
private const val MetadataCompletionHoldMs = 280L

@Composable
internal fun ManualMetadataApplicationProgress(
    state: ManualMetadataApplicationState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    requestKey: String = "metadata",
    onCompleted: () -> Unit = {}
) {
    val target = if (state.completed) 1f else
        (state.stage?.ordinal ?: 0) / SongMetadataApplyStage.entries.size.toFloat()
    val progress = remember(requestKey) { Animatable(target) }
    val latestOnCompleted = rememberUpdatedState(onCompleted)
    LaunchedEffect(requestKey, state.stage, state.completed) {
        progress.animateTo(target, tween(MetadataProgressAnimationMs, easing = NeriMotion.Emphasized))
        if (state.completed) {
            val completedAt = withFrameNanos { it }
            while (withFrameNanos { it } - completedAt < MetadataCompletionHoldMs.milliseconds.inWholeNanoseconds) {
                // 按可见帧保留完成状态，后台等待不提前关闭窗口
            }
            latestOnCompleted.value()
        }
    }
    Surface(
        modifier.fillMaxWidth().testTag("metadata-application-progress"),
        shape = NeriDesign.tokens.cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (state.completed) Icons.Outlined.CheckCircle else Icons.Outlined.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(if (state.completed) AppR.string.metadata_apply_completed else metadataApplyStageLabel(state.stage)),
                    modifier = Modifier.weight(1f).testTag("metadata-application-stage"),
                    style = MaterialTheme.typography.titleSmall,
                    minLines = 2,
                    maxLines = 2
                )
            }
            LinearProgressIndicator(
                progress = { progress.value },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(NeriDesign.tokens.chipShape)
                    .testTag("metadata-stage-progress"),
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(
                        AppR.string.metadata_apply_stage_count,
                        if (state.completed) SongMetadataApplyStage.entries.size else (state.stage?.ordinal ?: 0) + 1,
                        SongMetadataApplyStage.entries.size
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(Modifier.height(48.dp), contentAlignment = Alignment.CenterEnd) {
                    if (state.isRunning) {
                        HapticTextButton(
                            onClick = onCancel,
                            enabled = state.canCancel,
                            modifier = Modifier.testTag("metadata-application-cancel")
                        ) { Text(stringResource(CoreCommonR.string.action_cancel)) }
                    }
                }
            }
        }
    }
}

private fun metadataApplyStageLabel(stage: SongMetadataApplyStage?): Int = when (stage) {
    SongMetadataApplyStage.FETCHING_DETAILS, null -> AppR.string.metadata_apply_fetching_details
    SongMetadataApplyStage.FETCHING_LYRICS -> AppR.string.metadata_apply_fetching_lyrics
    SongMetadataApplyStage.PREPARING_COVER -> AppR.string.metadata_apply_preparing_cover
    SongMetadataApplyStage.PREPARING_METADATA -> AppR.string.metadata_apply_preparing_metadata
    SongMetadataApplyStage.APPLYING -> AppR.string.metadata_apply_committing
}
