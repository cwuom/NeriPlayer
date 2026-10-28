package moe.ouom.neriplayer.ui.screen.playlist.curation

import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.data.local.media.curation.EmbeddedCoverSizePreset
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSegmentedTabs
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSlider
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import kotlin.math.roundToInt
import moe.ouom.neriplayer.R as AppR

private val lyricModes = listOf(
    LocalCurationLyricMode.SKIP,
    LocalCurationLyricMode.FILL_MISSING,
    LocalCurationLyricMode.REMATCH_ALL,
    LocalCurationLyricMode.REEMBED_CURRENT
)
private val coverModes = listOf(
    LocalCurationCoverMode.SKIP,
    LocalCurationCoverMode.FILL_MISSING,
    LocalCurationCoverMode.REPLACE_ALL
)
private val coverSizes = listOf(
    EmbeddedCoverSizePreset.COMPACT,
    EmbeddedCoverSizePreset.STANDARD,
    EmbeddedCoverSizePreset.ORIGINAL
)

internal fun canStartLocalCuration(songCount: Int, options: LocalCurationOptions): Boolean {
    if (songCount <= 0 || !options.hasWork) return false
    val coverSourceMissing = options.coverMode != LocalCurationCoverMode.SKIP &&
        !options.useFolderCover && !options.useOnlineCover
    return !coverSourceMissing
}

@Composable
internal fun LocalCurationConfigureContent(
    songs: List<SongItem>,
    options: LocalCurationOptions,
    onOptionsChange: (LocalCurationOptions) -> Unit,
    onStart: () -> Unit
) {
    val songCount = songs.size
    val maxHeight = localCurationMaxHeight(0.62f)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            modifier = Modifier
                .heightIn(max = maxHeight)
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            CurationModeSection(
                title = stringResource(AppR.string.local_curation_section_lyrics),
                labels = listOf(
                    stringResource(AppR.string.local_curation_mode_skip),
                    stringResource(AppR.string.local_curation_mode_fill),
                    stringResource(AppR.string.local_curation_mode_rematch),
                    stringResource(AppR.string.local_curation_mode_reembed)
                ),
                selectedIndex = lyricModes.indexOf(options.lyricMode),
                onSelected = { onOptionsChange(options.copy(lyricMode = lyricModes[it])) },
                description = stringResource(lyricModeDescription(options.lyricMode))
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CurationModeSection(
                    title = stringResource(AppR.string.local_curation_section_cover),
                    labels = listOf(
                        stringResource(AppR.string.local_curation_mode_skip),
                        stringResource(AppR.string.local_curation_mode_fill),
                        stringResource(AppR.string.local_curation_mode_replace)
                    ),
                    selectedIndex = coverModes.indexOf(options.coverMode),
                    onSelected = { onOptionsChange(options.copy(coverMode = coverModes[it])) },
                    description = stringResource(coverModeDescription(options.coverMode))
                )
                AnimatedVisibility(
                    visible = options.coverMode != LocalCurationCoverMode.SKIP,
                    enter = expandVertically(tween(280, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                    exit = shrinkVertically(tween(240, easing = FastOutSlowInEasing)) + fadeOut(tween(140))
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CoverOptions(options = options, onOptionsChange = onOptionsChange)
                        if (options.useFolderCover) LocalCurationFolderAccessCard(songs)
                    }
                }
            }
            ParallelismSection(options = options, onOptionsChange = onOptionsChange)
            CurationHintCard(text = stringResource(AppR.string.local_curation_background_hint))
        }
        MiuixSettingsButton(
            onClick = onStart,
            enabled = canStartLocalCuration(songCount, options),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (songCount > 0) {
                    stringResource(AppR.string.local_curation_start) + " · " +
                        pluralStringResource(AppR.plurals.local_curation_song_count, songCount, songCount)
                } else {
                    stringResource(AppR.string.local_curation_no_local_songs)
                }
            )
        }
    }
}

@Composable
private fun CurationModeSection(
    title: String,
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    description: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        MiuixSettingsSegmentedTabs(
            labels = labels,
            selectedIndex = selectedIndex.coerceAtLeast(0),
            onSelectedIndexChange = onSelected
        )
        AnimatedContent(
            targetState = description,
            transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(120))) },
            label = "local-curation-mode-description"
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun CoverOptions(
    options: LocalCurationOptions,
    onOptionsChange: (LocalCurationOptions) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(AppR.string.local_curation_cover_size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp)
        )
        MiuixSettingsSegmentedTabs(
            labels = listOf(
                stringResource(AppR.string.local_curation_cover_size_compact),
                stringResource(AppR.string.local_curation_cover_size_standard),
                stringResource(AppR.string.local_curation_cover_size_original)
            ),
            selectedIndex = coverSizes.indexOf(options.coverSize).coerceAtLeast(0),
            onSelectedIndexChange = { onOptionsChange(options.copy(coverSize = coverSizes[it])) }
        )
        Text(
            text = stringResource(AppR.string.local_curation_cover_size_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Surface(
            shape = neriRoundedShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column {
                CurationSwitchRow(
                    title = stringResource(AppR.string.local_curation_use_folder_cover),
                    description = stringResource(AppR.string.local_curation_use_folder_cover_desc),
                    checked = options.useFolderCover,
                    onCheckedChange = { onOptionsChange(options.copy(useFolderCover = it)) }
                )
                CurationSwitchRow(
                    title = stringResource(AppR.string.local_curation_use_online_cover),
                    description = stringResource(AppR.string.local_curation_use_online_cover_desc),
                    checked = options.useOnlineCover,
                    onCheckedChange = { onOptionsChange(options.copy(useOnlineCover = it)) }
                )
            }
        }
    }
}

@Composable
private fun ParallelismSection(
    options: LocalCurationOptions,
    onOptionsChange: (LocalCurationOptions) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(AppR.string.local_curation_section_performance),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            shape = neriRoundedShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                ParallelismSlider(
                    label = stringResource(AppR.string.local_curation_search_parallelism, options.searchParallelism),
                    value = options.searchParallelism,
                    onValueChange = { onOptionsChange(options.copy(searchParallelism = it)) }
                )
                ParallelismSlider(
                    label = stringResource(AppR.string.local_curation_write_parallelism, options.writeParallelism),
                    value = options.writeParallelism,
                    onValueChange = { onOptionsChange(options.copy(writeParallelism = it)) }
                )
            }
        }
        Text(
            text = stringResource(AppR.string.local_curation_parallelism_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun ParallelismSlider(label: String, value: Int, onValueChange: (Int) -> Unit) {
    val range = LOCAL_CURATION_PARALLELISM_RANGE
    Column {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        MiuixSettingsSlider(
            value = value.toFloat(),
            onValueChange = { onValueChange(clampLocalCurationParallelism(it.roundToInt())) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1
        )
    }
}

@Composable
internal fun CurationSwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(neriRoundedShape(16.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        MiuixSettingsSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
internal fun CurationHintCard(text: String) {
    Surface(
        shape = neriRoundedShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

private fun lyricModeDescription(mode: LocalCurationLyricMode): Int = when (mode) {
    LocalCurationLyricMode.SKIP -> AppR.string.local_curation_lyric_skip_desc
    LocalCurationLyricMode.FILL_MISSING -> AppR.string.local_curation_lyric_fill_desc
    LocalCurationLyricMode.REMATCH_ALL -> AppR.string.local_curation_lyric_rematch_desc
    LocalCurationLyricMode.REEMBED_CURRENT -> AppR.string.local_curation_lyric_reembed_desc
}

private fun coverModeDescription(mode: LocalCurationCoverMode): Int = when (mode) {
    LocalCurationCoverMode.SKIP -> AppR.string.local_curation_cover_skip_desc
    LocalCurationCoverMode.FILL_MISSING -> AppR.string.local_curation_cover_fill_desc
    LocalCurationCoverMode.REPLACE_ALL -> AppR.string.local_curation_cover_replace_desc
}
