package moe.ouom.neriplayer.ui.screen.tab.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.component.common.CleanSegmentedTabs
import moe.ouom.neriplayer.ui.component.common.NeriChoiceChip
import moe.ouom.neriplayer.ui.theme.NeriDesign

/**
 * 媒体库内的二级分类（歌单/艺人/专辑等）
 *
 * 与顶部平台标签拉开层级: Material 用一行筛选标签, Clean 顶部已是胶囊行, 这里用分段控件;
 * 左右边距与搜索框一致
 */
@Composable
internal fun LibrarySecondaryTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (NeriDesign.isClean) {
        CleanSegmentedTabs(
            labels = labels,
            selectedIndex = selectedIndex,
            onSelected = onSelected,
            modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            NeriChoiceChip(
                selected = selectedIndex == index,
                onClick = { onSelected(index) },
                label = label
            )
        }
    }
}
