package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.stats.PlaybackStatsRank
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.theme.NeriDesign

@Composable
internal fun StatsRankDetailDialog(
    rank: PlaybackStatsRank,
    position: Int,
    album: Boolean,
    offlineMode: Boolean,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val clean = NeriDesign.isClean
    DensityScaledAlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(
                Modifier.fillMaxWidth().testTag("statsRankDetail").verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatsArtwork(rank.coverUrl, offlineMode, 56.dp, round = !album)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(if (album) R.string.stats_album_rank else R.string.stats_artist_rank, position),
                            color = colors.onSurfaceVariant,
                            style = typography.labelMedium
                        )
                        Text(rank.name, Modifier.semantics { heading() }, color = colors.onSurface,
                            style = typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NeriDesign.tokens.cardShape,
                    color = if (clean) colors.onSurface.copy(alpha = 0.055f) else colors.primaryContainer,
                    contentColor = if (clean) colors.onSurface else colors.onPrimaryContainer
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(stringResource(R.string.stats_total_time), style = typography.labelMedium)
                        Text(
                            statsDuration(rank.totalListenMs, exact = true),
                            modifier = Modifier.fillMaxWidth(),
                            style = typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 28.sp),
                            maxLines = 1, softWrap = false
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.stats_share_label), Modifier.weight(1f),
                                style = typography.bodySmall)
                            Text(statsPercent(rank.share, precise = true), style = typography.labelLarge,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                            .background(if (clean) colors.outlineVariant else colors.onPrimaryContainer.copy(alpha = 0.14f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(rank.share.toFloat().coerceIn(0f, 1f))
                                .background(if (clean) colors.primary else colors.onPrimaryContainer, CircleShape))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    RankCount(stringResource(R.string.stats_track_count), rank.trackCount, Modifier.weight(1f))
                    RankCount(stringResource(R.string.stats_total_plays), rank.totalPlayCount, Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            HapticTextButton(onClick = onDismiss) { Text(stringResource(R.string.stats_rank_detail_close)) }
        }
    )
}

@Composable
private fun RankCount(label: String, count: Long, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(statsNumber(count), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 22.sp),
            maxLines = 1, softWrap = false)
    }
}
