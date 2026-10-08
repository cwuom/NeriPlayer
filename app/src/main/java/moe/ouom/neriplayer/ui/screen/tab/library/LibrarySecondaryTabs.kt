package moe.ouom.neriplayer.ui.screen.tab.library

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface

private val LibrarySecondaryTabShape = RoundedCornerShape(24.dp)

/**
 * 媒体库内的二级分类（歌单/艺人/专辑等）
 *
 * 只用文字，层级低于页面顶部的平台标签；左右边距与搜索框一致
 */
@Composable
internal fun LibrarySecondaryTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    AdvancedGlassSurface(
        role = AdvancedGlassRole.ScreenTopTab,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(LibrarySecondaryTabShape),
        shape = LibrarySecondaryTabShape,
        fallbackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        tintColor = MaterialTheme.colorScheme.surfaceVariant
    ) {
        SecondaryTabRow(
            selectedTabIndex = selectedIndex,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = {}
        ) {
            labels.forEachIndexed { index, label ->
                Tab(
                    selected = selectedIndex == index,
                    onClick = { onSelected(index) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(label, style = MaterialTheme.typography.titleSmall) }
                )
            }
        }
    }
}
