package moe.ouom.neriplayer.ui.screen.tab.settings.component.floating

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_LONG_LINE_SCROLL
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_LONG_LINE_WRAP
import moe.ouom.neriplayer.data.model.settings.lyrics.FloatingLyricsPreferences
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSegmentedTabs

@Composable
internal fun FloatingLyricsLayoutSettings(
    preferences: FloatingLyricsPreferences,
    onSentenceCountChange: (Int) -> Unit,
    onLongLineModeChange: (String) -> Unit
) {
    FloatingLyricsLayoutChoice(
        title = stringResource(CoreCommonR.string.settings_floating_lyrics_sentence_count),
        description = stringResource(CoreCommonR.string.settings_floating_lyrics_sentence_count_desc),
        labels = listOf(
            stringResource(CoreCommonR.string.settings_floating_lyrics_sentence_one),
            stringResource(CoreCommonR.string.settings_floating_lyrics_sentence_two),
            stringResource(CoreCommonR.string.settings_floating_lyrics_sentence_three)
        ),
        selectedIndex = preferences.sentenceCount - 1,
        onSelectedIndexChange = { onSentenceCountChange(it + 1) }
    )
    FloatingLyricsLayoutChoice(
        title = stringResource(CoreCommonR.string.settings_floating_lyrics_long_line_mode),
        description = stringResource(CoreCommonR.string.settings_floating_lyrics_long_line_mode_desc),
        labels = listOf(
            stringResource(CoreCommonR.string.settings_floating_lyrics_long_line_scroll),
            stringResource(CoreCommonR.string.settings_floating_lyrics_long_line_wrap)
        ),
        selectedIndex = if (preferences.longLineMode == FLOATING_LYRICS_LONG_LINE_WRAP) 1 else 0,
        onSelectedIndexChange = {
            onLongLineModeChange(if (it == 1) FLOATING_LYRICS_LONG_LINE_WRAP else FLOATING_LYRICS_LONG_LINE_SCROLL)
        }
    )
}

@Composable
private fun FloatingLyricsLayoutChoice(
    title: String,
    description: String,
    labels: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(description)
                MiuixSettingsSegmentedTabs(
                    labels = labels,
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = onSelectedIndexChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp)
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
