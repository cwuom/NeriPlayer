package moe.ouom.neriplayer.ui.component.playback

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.metadata.SongMetadataApplyOptions
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.stringResId
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsCheckbox
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriMotion

internal fun defaultMetadataLyricSources(
    preference: LyricSourcePreference,
    selectedPlatform: MusicPlatform
): Set<EditableLyricMatchSource> = setOf(
    preference.matchSource ?: when (selectedPlatform) {
        MusicPlatform.CLOUD_MUSIC -> EditableLyricMatchSource.CLOUD_MUSIC
        MusicPlatform.QQ_MUSIC -> EditableLyricMatchSource.QQ_MUSIC
    }
)

internal fun canApplyManualMetadataSelection(options: SongMetadataApplyOptions): Boolean =
    !(options.originalLyrics || options.translatedLyrics || options.romanizedLyrics) ||
        options.lyricSources.isNotEmpty()

@Composable
internal fun ManualMetadataApplyOptionsDialog(
    selectedSong: SongSearchInfo,
    isLocalSong: Boolean,
    initialOptions: SongMetadataApplyOptions,
    onDismiss: () -> Unit,
    onApply: (SongMetadataApplyOptions) -> Unit
) {
    var options by remember(selectedSong.id, selectedSong.source) {
        mutableStateOf(initialOptions.copy(writeLocalMetadata = isLocalSong))
    }
    val canApply = canApplyManualMetadataSelection(options)
    DensityScaledAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("metadata-apply-options-dialog"),
        title = { Text(stringResource(AppR.string.metadata_apply_options_title)) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            selectedSong.songName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (selectedSong.singer.isNotBlank()) {
                            Text(
                                selectedSong.singer,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    ManualMetadataApplyOptionsContent(options) { options = it }
                    if (!canApply) {
                        Text(
                            stringResource(CoreCommonR.string.lyrics_match_no_source_selected),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (isLocalSong) {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Text(
                            stringResource(AppR.string.metadata_apply_destination),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Row(
                            Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ManualMetadataChoice(
                                selected = options.writeLocalMetadata,
                                onClick = { options = options.copy(writeLocalMetadata = true) },
                                label = stringResource(AppR.string.metadata_apply_destination_file),
                                modifier = Modifier.weight(1f).fillMaxHeight().testTag("metadata-apply-write")
                            )
                            ManualMetadataChoice(
                                selected = !options.writeLocalMetadata,
                                onClick = { options = options.copy(writeLocalMetadata = false) },
                                label = stringResource(AppR.string.metadata_apply_destination_app),
                                modifier = Modifier.weight(1f).fillMaxHeight().testTag("metadata-apply-app-only")
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            HapticTextButton(
                enabled = canApply,
                onClick = { onApply(options.copy(writeLocalMetadata = isLocalSong && options.writeLocalMetadata)) },
                modifier = Modifier.testTag("metadata-apply-confirm")
            ) {
                Text(stringResource(AppR.string.metadata_apply_confirm))
            }
        },
        dismissButton = {
            HapticTextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("metadata-apply-cancel")
            ) { Text(stringResource(CoreCommonR.string.action_cancel)) }
        }
    )
}

@Composable
private fun ManualMetadataApplyOptionsContent(
    options: SongMetadataApplyOptions,
    onChange: (SongMetadataApplyOptions) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(NeriDesign.tokens.sectionSpacing)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(AppR.string.metadata_apply_lyric_sources), style = MaterialTheme.typography.labelLarge)
            EditableLyricMatchSource.entries.chunked(2).forEach { sources ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sources.forEach { source ->
                        ManualMetadataChoice(
                            selected = source in options.lyricSources,
                            onClick = {
                                onChange(options.copy(lyricSources = if (source in options.lyricSources) {
                                    options.lyricSources - source
                                } else options.lyricSources + source))
                            },
                            label = stringResource(source.stringResId()),
                            multiSelect = true,
                            modifier = Modifier.weight(1f).fillMaxHeight().testTag("metadata-source-${source.name}")
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(AppR.string.metadata_apply_lyric_content), style = MaterialTheme.typography.labelLarge)
            Surface(shape = NeriDesign.tokens.cardShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Column {
                    ManualMetadataLyricRow(
                        selected = options.originalLyrics,
                        onClick = { onChange(options.copy(originalLyrics = !options.originalLyrics)) },
                        label = stringResource(CoreCommonR.string.lyrics_original),
                        modifier = Modifier.testTag("metadata-content-original")
                    )
                    ManualMetadataLyricRow(
                        selected = options.translatedLyrics,
                        onClick = { onChange(options.copy(translatedLyrics = !options.translatedLyrics)) },
                        label = stringResource(CoreCommonR.string.lyrics_translation),
                        modifier = Modifier.testTag("metadata-content-translated")
                    )
                    ManualMetadataLyricRow(
                        selected = options.romanizedLyrics,
                        onClick = { onChange(options.copy(romanizedLyrics = !options.romanizedLyrics)) },
                        label = stringResource(AppR.string.metadata_apply_romanized),
                        modifier = Modifier.testTag("metadata-content-romanized")
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(options.preferWordTimed, role = Role.Switch) {
                        onChange(options.copy(preferWordTimed = it))
                    }.testTag("metadata-prefer-word-timed"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(CoreCommonR.string.settings_prefer_word_timed_lyrics),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                MiuixSettingsSwitch(
                    checked = options.preferWordTimed,
                    onCheckedChange = null
                )
            }
        }
    }
}

@Composable
private fun ManualMetadataChoice(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    multiSelect: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val clean = NeriDesign.isClean
    val selectedFill = if (clean) colors.primary else colors.secondaryContainer
    val selectedContent = if (clean) colors.onPrimary else colors.onSecondaryContainer
    val fill by animateColorAsState(
        targetValue = if (selected) selectedFill else colors.surfaceContainerHighest,
        animationSpec = NeriMotion.smooth(),
        label = "metadata_choice_fill"
    )
    val content by animateColorAsState(
        targetValue = if (selected) selectedContent else colors.onSurface,
        animationSpec = NeriMotion.smooth(),
        label = "metadata_choice_content"
    )
    val selection = if (multiSelect) {
        Modifier.toggleable(selected, role = Role.Checkbox, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick)
    }
    Surface(
        modifier = modifier.heightIn(min = 48.dp).clip(NeriDesign.tokens.cardShape).then(selection),
        shape = NeriDesign.tokens.cardShape,
        color = fill,
        contentColor = content,
        border = if (clean) null else BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant)
    ) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ManualMetadataLyricRow(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(selected, role = Role.Checkbox, onValueChange = { onClick() })
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        MiuixSettingsCheckbox(checked = selected, onCheckedChange = null)
    }
}
