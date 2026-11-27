package moe.ouom.neriplayer.ui.screen.history.stats

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.component.common.CleanSegmentedTabs
import moe.ouom.neriplayer.ui.theme.NeriDesign

@Composable
internal fun StatsViewSelector(ranking: Boolean, change: (Boolean) -> Unit) {
    val labels = listOf(stringResource(R.string.stats_overview_tab), stringResource(R.string.stats_tracks_tab))
    val selected = if (ranking) 1 else 0
    if (NeriDesign.isClean) {
        CleanSegmentedTabs(labels, selected, { change(it == 1) }, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
    } else {
        PrimaryTabRow(selectedTabIndex = selected, containerColor = Color.Transparent) {
            labels.forEachIndexed { index, label ->
                Tab(selected = index == selected, onClick = { change(index == 1) }, text = { Text(label) })
            }
        }
    }
}
