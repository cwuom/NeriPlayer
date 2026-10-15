package moe.ouom.neriplayer.ui.screen.playlist.curation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.format.formatFileSize
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchConfidence
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricTimelineFit
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsDialog
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsInlineMessage
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsOutlinedButton
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsTextButton
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

private const val LYRIC_PREVIEW_LINES = 6

@Composable
internal fun LocalCurationReviewContent(
    session: LocalCurationSession,
    onLyricSelected: (String, Boolean) -> Unit,
    onCoverSelected: (String, Boolean) -> Unit,
    onChooseAlternative: (String, Int) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onDiscard: () -> Unit,
    onApply: () -> Unit
) {
    var filter by rememberSaveable { mutableStateOf(LocalCurationFilter.ALL) }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showConfirm by remember { mutableStateOf(false) }
    var writeAccessDenied by remember { mutableStateOf(false) }
    val requestWriteAccess = rememberLocalCurationWriteAccessRequester(
        songs = { session.items.filter(LocalCurationItem::hasSelection).map { it.song } },
        onGranted = {
            writeAccessDenied = false
            onApply()
        },
        onDenied = { writeAccessDenied = true }
    )
    val filtered = remember(session.items, filter) { session.items.filter { it.matches(filter) } }
    val selectableCount = session.count(LocalCurationItemPhase.READY) +
        session.count(LocalCurationItemPhase.FAILED)
    val allSelected = remember(session.items) { areAllLocalCurationProposalsSelected(session.items) }
    val sizeDelta = remember(session.items) { estimateLocalCurationBytesDelta(session.items) }
    val listMaxHeight = localCurationMaxHeight(0.48f)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (session.cancelled) {
            MiuixSettingsInlineMessage(
                message = stringResource(CoreCommonR.string.local_curation_analysis_stopped),
                isSuccess = true
            )
        }
        if (writeAccessDenied) {
            MiuixSettingsInlineMessage(
                message = stringResource(CoreCommonR.string.local_curation_write_access_denied),
                isSuccess = false,
                onClose = { writeAccessDenied = false }
            )
        }
        LocalCurationStatsRow(session)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LocalCurationFilter.entries.forEach { option ->
                val count = session.items.count { it.matches(option) }
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text("${stringResource(option.labelRes())} $count") },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = pluralStringResource(
                    CoreCommonR.plurals.local_curation_selected_count,
                    session.selectedCount,
                    session.selectedCount
                ) + " · " + localCurationSizeDeltaText(sizeDelta),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            MiuixSettingsTextButton(
                onClick = { onSelectAll(!allSelected) },
                enabled = selectableCount > 0
            ) {
                Text(
                    stringResource(
                        if (allSelected) CoreCommonR.string.action_deselect_all else CoreCommonR.string.action_select_all
                    )
                )
            }
        }
        if (filtered.isEmpty()) {
            Text(
                text = stringResource(CoreCommonR.string.local_curation_review_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                textAlign = TextAlign.Center
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = listMaxHeight),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = LocalCurationItem::key) { item ->
                    LocalCurationReviewRow(
                        item = item,
                        expanded = expandedKey == item.key,
                        onToggleExpanded = {
                            expandedKey = if (expandedKey == item.key) null else item.key
                        },
                        onLyricSelected = { onLyricSelected(item.key, it) },
                        onCoverSelected = { onCoverSelected(item.key, it) },
                        onChooseAlternative = { onChooseAlternative(item.key, it) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MiuixSettingsOutlinedButton(onClick = onDiscard, modifier = Modifier.weight(1f)) {
                Text(stringResource(CoreCommonR.string.action_cancel))
            }
            MiuixSettingsButton(
                onClick = { showConfirm = true },
                enabled = session.selectedCount > 0,
                modifier = Modifier.weight(1.4f)
            ) {
                Text(
                    pluralStringResource(
                        CoreCommonR.plurals.local_curation_apply_count,
                        session.selectedCount,
                        session.selectedCount
                    )
                )
            }
        }
    }

    if (showConfirm) {
        MiuixSettingsDialog(
            onDismissRequest = { showConfirm = false },
            title = {
                Text(
                    pluralStringResource(
                        CoreCommonR.plurals.local_curation_confirm_title,
                        session.selectedCount,
                        session.selectedCount
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        CoreCommonR.string.local_curation_confirm_message,
                        localCurationSizeDeltaText(sizeDelta)
                    )
                )
            },
            confirmButton = {
                MiuixSettingsTextButton(onClick = {
                    showConfirm = false
                    requestWriteAccess()
                }) {
                    Text(stringResource(CoreCommonR.string.local_curation_confirm_write))
                }
            },
            dismissButton = {
                MiuixSettingsTextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun LocalCurationReviewRow(
    item: LocalCurationItem,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onLyricSelected: (Boolean) -> Unit,
    onCoverSelected: (Boolean) -> Unit,
    onChooseAlternative: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val selectable = item.phase == LocalCurationItemPhase.READY || item.phase == LocalCurationItemPhase.FAILED
    val hasDetails = item.lyric != null || item.cover != null
    val containerColor by animateColorAsState(
        targetValue = if (item.hasSelection) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(220),
        label = "local-curation-row-container"
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "local-curation-row-chevron"
    )
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasDetails) {
                        context.performHapticFeedback()
                        onToggleExpanded()
                    }
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CurationCoverThumb(item)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = item.song.displayName(),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.song.displayArtist(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (hasDetails) {
                        CurationProposalChips(item)
                    } else {
                        Text(
                            text = localCurationItemStatus(item),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (hasDetails) {
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = chevronRotation }
                    )
                }
                TriStateCheckbox(
                    state = item.selectionState(),
                    enabled = selectable && hasDetails,
                    onClick = {
                        val select = item.selectionState() != ToggleableState.On
                        if (item.lyric != null) onLyricSelected(select)
                        if (item.cover != null) onCoverSelected(select)
                    }
                )
            }
            AnimatedVisibility(
                visible = expanded && hasDetails,
                enter = expandVertically(tween(280, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(220, easing = FastOutSlowInEasing)) + fadeOut(tween(120))
            ) {
                CurationProposalDetails(
                    item = item,
                    enabled = selectable,
                    onLyricSelected = onLyricSelected,
                    onCoverSelected = onCoverSelected,
                    onChooseAlternative = onChooseAlternative
                )
            }
        }
    }
}

@Composable
private fun CurationCoverThumb(item: LocalCurationItem) {
    val context = LocalContext.current
    val model = item.cover?.prepared?.reference ?: item.song.customCoverUrl ?: item.song.coverUrl
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(24.dp)
        )
        if (!model.isNullOrBlank()) {
            AsyncImage(
                model = offlineCachedImageRequest(context = context, data = model, sizePx = 160),
                contentDescription = if (item.cover != null) {
                    stringResource(CoreCommonR.string.local_curation_cd_cover_preview)
                } else {
                    null
                },
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurationProposalChips(item: LocalCurationItem) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item.lyric?.let { lyric ->
            CurationChip(
                text = stringResource(CoreCommonR.string.local_curation_section_lyrics) + " · " +
                    lyricOriginLabel(lyric),
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                content = MaterialTheme.colorScheme.onSurface
            )
            if (lyric.origin == LocalCurationLyricOrigin.ONLINE) ConfidenceChip(lyric.confidence)
            if (lyric.hasWordTiming) {
                CurationChip(
                    text = stringResource(CoreCommonR.string.lyrics_match_word_timed),
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
        item.cover?.let { cover ->
            CurationChip(
                text = stringResource(CoreCommonR.string.local_curation_section_cover) + " · " +
                    coverOriginLabel(cover),
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                content = MaterialTheme.colorScheme.onSurface
            )
        }
        if (item.phase == LocalCurationItemPhase.FAILED) {
            CurationChip(
                text = localCurationItemStatus(item),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun ConfidenceChip(confidence: EditableLyricMatchConfidence) {
    val colors = MaterialTheme.colorScheme
    val (label, container, content) = when (confidence) {
        EditableLyricMatchConfidence.HIGH -> Triple(
            CoreCommonR.string.lyrics_match_confidence_high,
            colors.primaryContainer,
            colors.onPrimaryContainer
        )
        EditableLyricMatchConfidence.MEDIUM -> Triple(
            CoreCommonR.string.lyrics_match_confidence_medium,
            colors.secondaryContainer,
            colors.onSecondaryContainer
        )
        EditableLyricMatchConfidence.LOW -> Triple(
            CoreCommonR.string.lyrics_match_confidence_low,
            colors.errorContainer,
            colors.onErrorContainer
        )
    }
    CurationChip(text = stringResource(label), container = container, content = content)
}

@Composable
private fun CurationChip(text: String, container: Color, content: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun CurationProposalDetails(
    item: LocalCurationItem,
    enabled: Boolean,
    onLyricSelected: (Boolean) -> Unit,
    onCoverSelected: (Boolean) -> Unit,
    onChooseAlternative: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item.lyric?.let { current ->
            AnimatedContent(
                targetState = current,
                transitionSpec = { fadeIn(tween(220)).togetherWith(fadeOut(tween(120))) },
                label = "local-curation-lyric-proposal"
            ) { lyric ->
                CurationLyricPreview(lyric)
            }
            if (item.lyricAlternatives.isNotEmpty() && enabled) {
                CurationAlternatives(item.lyricAlternatives, onChooseAlternative)
            }
            CurationSwitchRow(
                title = stringResource(CoreCommonR.string.local_curation_apply_lyric),
                description = null,
                checked = item.applyLyric,
                onCheckedChange = onLyricSelected,
                enabled = enabled
            )
        }
        item.cover?.let { cover ->
            CurationSwitchRow(
                title = stringResource(CoreCommonR.string.local_curation_apply_cover),
                description = stringResource(
                    CoreCommonR.string.local_curation_cover_detail,
                    cover.prepared.width,
                    cover.prepared.height,
                    formatFileSize(cover.prepared.byteSize.toLong())
                ),
                checked = item.applyCover,
                onCheckedChange = onCoverSelected,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun CurationAlternatives(
    alternatives: List<LocalCurationLyricProposal>,
    onChoose: (Int) -> Unit
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(CoreCommonR.string.local_curation_alternatives),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        alternatives.forEachIndexed { index, alternative ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                    .clickable {
                        context.performHapticFeedback()
                        onChoose(index)
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${alternative.matchedTitle} - ${alternative.matchedArtist}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = lyricOriginLabel(alternative),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ConfidenceChip(alternative.confidence)
                Icon(
                    imageVector = Icons.Outlined.SwapHoriz,
                    contentDescription = stringResource(CoreCommonR.string.local_curation_use_alternative),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun CurationLyricPreview(lyric: LocalCurationLyricProposal) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (lyric.origin == LocalCurationLyricOrigin.ONLINE) {
            Text(
                text = stringResource(
                    CoreCommonR.string.local_curation_matched_as,
                    lyric.matchedTitle,
                    lyric.matchedArtist
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (lyric.timelineFit == EditableLyricTimelineFit.FITS) {
            Text(
                text = stringResource(CoreCommonR.string.local_curation_timeline_fit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = localCurationLyricPreview(lyric.lyrics, LYRIC_PREVIEW_LINES).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

private fun LocalCurationItem.selectionState(): ToggleableState {
    val wanted = listOfNotNull(lyric?.let { applyLyric }, cover?.let { applyCover })
    return when {
        wanted.isEmpty() || wanted.none { it } -> ToggleableState.Off
        wanted.all { it } -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

private fun LocalCurationFilter.labelRes(): Int = when (this) {
    LocalCurationFilter.ALL -> CoreCommonR.string.local_curation_filter_all
    LocalCurationFilter.READY -> CoreCommonR.string.local_curation_filter_ready
    LocalCurationFilter.ATTENTION -> CoreCommonR.string.local_curation_filter_attention
    LocalCurationFilter.SKIPPED -> CoreCommonR.string.local_curation_filter_skipped
}
