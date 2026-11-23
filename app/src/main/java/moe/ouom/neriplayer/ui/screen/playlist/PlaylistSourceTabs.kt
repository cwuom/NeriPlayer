package moe.ouom.neriplayer.ui.screen.playlist

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.component.common.CleanSegmentedTabs
import moe.ouom.neriplayer.ui.theme.NeriDesign

internal const val PlaylistPaneSourceTabsTag = "playlist-pane-source-tabs"

/**
 * 歌单内的来源切换: 平板侧栏里落在头部色上, 用白色分段胶囊和播放按钮呼应;
 * 其余场景 Clean 用分段控件, Material 保留带指示条的标签行
 */
@Composable
internal fun PlaylistSourceTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        LocalPlaylistHeroInPane.current -> PlaylistPaneSegmentedTabs(labels, selectedIndex, onSelected, modifier)
        NeriDesign.isClean -> CleanSegmentedTabs(
            labels = labels,
            selectedIndex = selectedIndex,
            onSelected = onSelected,
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp)
        )
        else -> Column(modifier) {
            PrimaryTabRow(
                selectedTabIndex = selectedIndex,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                labels.forEachIndexed { index, label ->
                    Tab(
                        selected = index == selectedIndex,
                        onClick = { onSelected(index) },
                        text = { Text(label) }
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun PlaylistPaneSegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier
) {
    val shape = if (NeriDesign.isClean) PlaylistPaneSegmentShapeClean else CircleShape
    val heroColor = playlistHeroBackgroundColor()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 16.dp)
            .height(48.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.12f))
            .selectableGroup()
            .testTag(PlaylistPaneSourceTabsTag)
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val segmentColor by animateColorAsState(
                targetValue = if (selected) Color.White else Color.White.copy(alpha = 0f),
                label = "paneSegment"
            )
            val textColor by animateColorAsState(
                targetValue = if (selected) heroColor else Color.White.copy(alpha = 0.8f),
                label = "paneSegmentText"
            )
            // 点按区域占满 48dp 高, 选中底色往里收 4dp, 视觉上是胶囊里的一颗胶囊
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelected(index) })
                    .padding(4.dp)
                    .clip(shape)
                    .background(segmentColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

private val PlaylistPaneSegmentShapeClean = RoundedCornerShape(12.dp)
