package moe.ouom.neriplayer.ui.screen.nowplaying.lyrics

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.ui.screen.nowplaying.lyrics/NowPlayingLyricsEditor
 */

import moe.ouom.neriplayer.data.identity.stableKey
import android.content.ClipData
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchRequest
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchConfidence
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.lyrics.matching.RankedEditableLyricMatch
import moe.ouom.neriplayer.platform.lyrics.matching.defaultEditableLyricMatchSources
import moe.ouom.neriplayer.platform.lyrics.matching.editableLyricMatchResultComparator
import moe.ouom.neriplayer.platform.lyrics.matching.normalizeLyricMatchText
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.platform.youtube.media.isYouTubeMusicSong
import moe.ouom.neriplayer.data.model.lyrics.LyricsEditorSource
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongLyricsDraft
import moe.ouom.neriplayer.util.format.formatDuration

internal class NowPlayingLyricsEditorOwner(
    private val song: SongItem,
    initialLyrics: String,
    initialTranslatedLyrics: String,
    initialRomanizedLyrics: String,
    private val scope: CoroutineScope,
    private val matchLyrics: suspend (EditableLyricMatchRequest) -> List<RankedEditableLyricMatch>,
    private val matchDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val lyricsTextState = mutableStateOf(initialLyrics)
    val translatedLyricsTextState = mutableStateOf(initialTranslatedLyrics)
    val romanizedLyricsTextState = mutableStateOf(initialRomanizedLyrics)
    val isSavingState = mutableStateOf(false)
    val selectedTabState = mutableIntStateOf(0)
    val showMatchSheetState = mutableStateOf(false)
    val showLocalMetadataWriteBackConfirmState = mutableStateOf(false)
    val showEmbeddedLyricsOverwriteConfirmState = mutableStateOf(false)
    val queryState = mutableStateOf(defaultEditableLyricsMatchKeyword(song))
    val selectedSourcesState = mutableStateOf(
        defaultEditableLyricMatchSources(isYouTubeMusicTrack = isYouTubeMusicSong(song))
    )
    val resultsBySourceState = mutableStateOf<Map<EditableLyricMatchSource, List<RankedEditableLyricMatch>>>(emptyMap())
    val searchedSourcesState = mutableStateOf<Set<EditableLyricMatchSource>>(emptySet())
    val isMatchingState = mutableStateOf(false)
    val matchErrorState = mutableStateOf<String?>(null)
    val canEdit: Boolean get() = !isSavingState.value

    private var cachedQuery = ""
    private var matchGeneration = 0
    private var matchJob: Job? = null
    private var saveJob: Job? = null
    private var disposed = false

    fun changeQuery(query: String) {
        if (isSavingState.value) return
        if (query == queryState.value) return
        queryState.value = query
        invalidateMatch()
        resultsBySourceState.value = emptyMap()
        searchedSourcesState.value = emptySet()
        cachedQuery = ""
        matchErrorState.value = null
    }

    fun toggleSource(source: EditableLyricMatchSource) {
        if (isSavingState.value) return
        val selected = selectedSourcesState.value
        selectedSourcesState.value = if (source in selected) selected - source else selected + source
        matchErrorState.value = null
    }

    fun applyMatch(result: RankedEditableLyricMatch) {
        if (isSavingState.value) return
        lyricsTextState.value = result.candidate.lyrics
        result.candidate.translatedLyrics?.takeIf(String::isNotBlank)?.let {
            translatedLyricsTextState.value = it
        }
        selectedTabState.intValue = 0
        showMatchSheetState.value = false
    }

    fun clearSelectedText() {
        if (isSavingState.value) return
        selectedTextState()?.value = ""
    }

    fun isSelectedTab(index: Int): Boolean = selectedTabState.intValue == index

    fun pasteSelectedText(text: String) {
        if (isSavingState.value) return
        if (text.isNotEmpty()) replaceSelectedText(text)
    }

    fun replaceSelectedText(text: String) {
        if (isSavingState.value) return
        selectedTextState()?.value = text
    }

    private fun selectedTextState(): MutableState<String>? = when (
        selectedTabState.intValue
    ) {
        0 -> lyricsTextState
        1 -> translatedLyricsTextState
        2 -> romanizedLyricsTextState
        else -> null
    }

    fun handleBack(onDismiss: () -> Unit) {
        if (!closeTopOverlay()) onDismiss()
    }

    private fun closeTopOverlay(): Boolean {
        if (isSavingState.value) return true
        if (showEmbeddedLyricsOverwriteConfirmState.value) {
            showEmbeddedLyricsOverwriteConfirmState.value = false
            return true
        }
        if (showLocalMetadataWriteBackConfirmState.value) {
            showLocalMetadataWriteBackConfirmState.value = false
            return true
        }
        if (showMatchSheetState.value) {
            showMatchSheetState.value = false
            return true
        }
        return false
    }

    fun requestSave(editingSource: LyricsEditorSource, hasExistingSidecar: Boolean): Boolean {
        if (isSavingState.value) return false
        if (!song.isLocalSong()) return true
        if (editingSource == LyricsEditorSource.EMBEDDED && hasExistingSidecar) {
            showEmbeddedLyricsOverwriteConfirmState.value = true
        } else {
            showLocalMetadataWriteBackConfirmState.value = true
        }
        return false
    }

    fun save(
        writeLocalMetadata: Boolean,
        onSaveLyrics: suspend (EditSongLyricsDraft) -> Boolean,
        onSaveFailed: () -> Unit,
        onSaved: () -> Unit
    ) {
        if (disposed || isSavingState.value) return
        isSavingState.value = true
        val draft = EditSongLyricsDraft(
            lyric = lyricsTextState.value,
            translatedLyric = translatedLyricsTextState.value,
            romanizedLyric = romanizedLyricsTextState.value,
            writeLocalMetadata = writeLocalMetadata
        )
        saveJob = scope.launch {
            try {
                persistDraft(draft, onSaveLyrics, onSaveFailed, onSaved)
            } finally {
                isSavingState.value = false
            }
        }
    }

    private suspend fun persistDraft(
        draft: EditSongLyricsDraft,
        onSaveLyrics: suspend (EditSongLyricsDraft) -> Boolean,
        onSaveFailed: () -> Unit,
        onSaved: () -> Unit
    ) {
        val succeeded = try {
            onSaveLyrics(draft)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            NPLogger.e("NowPlayingScreen", "保存歌词编辑器内容失败", error)
            false
        }
        if (disposed) return
        if (succeeded) onSaved() else onSaveFailed()
    }

    fun runMatch(
        query: String,
        sources: Set<EditableLyricMatchSource>,
        preferWordTimed: Boolean,
        noSourceMessage: String,
        errorMessage: (Throwable) -> String
    ) {
        val trimmedQuery = query.trim()
        if (disposed || isSavingState.value || trimmedQuery.isBlank() || isMatchingState.value) return
        if (sources.isEmpty()) {
            matchErrorState.value = noSourceMessage
            return
        }
        val queryKey = normalizeLyricMatchText(trimmedQuery)
        val sameQuery = cachedQuery == queryKey
        if (!sameQuery) {
            resultsBySourceState.value = emptyMap()
            searchedSourcesState.value = emptySet()
        }
        cachedQuery = queryKey
        queryState.value = trimmedQuery
        isMatchingState.value = true
        matchErrorState.value = null
        val generation = ++matchGeneration
        matchJob = scope.launch {
            try {
                val matches = withContext(matchDispatcher) {
                    matchLyrics(
                        EditableLyricMatchRequest(
                            keyword = trimmedQuery,
                            trackName = song.customName ?: song.name,
                            artistName = song.customArtist ?: song.artist,
                            albumName = song.album,
                            durationMs = song.durationMs,
                            preferWordTimed = preferWordTimed,
                            sources = sources
                        )
                    )
                }
                if (isCurrentMatch(generation)) {
                    resultsBySourceState.value = mergeEditableLyricMatchResults(
                        previous = resultsBySourceState.value,
                        matches = matches,
                        sources = sources,
                        sameQuery = sameQuery
                    )
                    searchedSourcesState.value = searchedSourcesState.value + sources
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrentMatch(generation)) matchErrorState.value = errorMessage(error)
            } finally {
                if (isCurrentMatch(generation)) isMatchingState.value = false
            }
        }
    }

    fun dispose() {
        disposed = true
        invalidateMatch()
        saveJob?.cancel()
    }

    private fun invalidateMatch() {
        matchGeneration++
        matchJob?.cancel()
        isMatchingState.value = false
    }

    private fun isCurrentMatch(generation: Int): Boolean = !disposed && generation == matchGeneration
}

internal fun mergeEditableLyricMatchResults(
    previous: Map<EditableLyricMatchSource, List<RankedEditableLyricMatch>>,
    matches: List<RankedEditableLyricMatch>,
    sources: Set<EditableLyricMatchSource>,
    sameQuery: Boolean
): Map<EditableLyricMatchSource, List<RankedEditableLyricMatch>> = buildMap {
    putAll(previous)
    sources.forEach { source ->
        val sourceMatches = matches.filter { it.candidate.source == source }
        if (sourceMatches.isNotEmpty() || !sameQuery || get(source).isNullOrEmpty()) {
            put(source, sourceMatches)
        }
    }
}


@Composable
fun LyricsEditorSheet(
    originalSong: SongItem,
    initialLyrics: String,
    initialTranslatedLyrics: String,
    initialRomanizedLyrics: String = "",
    editingSource: LyricsEditorSource = LyricsEditorSource.SIDECAR,
    hasExistingSidecar: Boolean = false,
    onSaveLyrics: suspend (EditSongLyricsDraft) -> Boolean,
    onSaveFailed: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun dismissLyricsEditor() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        onDismiss()
    }

    val owner = remember(
        originalSong.stableKey(), initialLyrics, initialTranslatedLyrics,
        initialRomanizedLyrics, editingSource
    ) {
        NowPlayingLyricsEditorOwner(
            song = originalSong,
            initialLyrics = initialLyrics,
            initialTranslatedLyrics = initialTranslatedLyrics,
            initialRomanizedLyrics = initialRomanizedLyrics,
            scope = coroutineScope,
            matchLyrics = AppContainer.editableLyricsMatcher::matchLyrics
        )
    }
    DisposableEffect(owner) { onDispose(owner::dispose) }
    var showLyricMatchSheet by owner.showMatchSheetState
    val lyricMatchQuery by owner.queryState
    val selectedLyricMatchSources by owner.selectedSourcesState
    val lyricMatchResultsBySource by owner.resultsBySourceState
    val searchedLyricMatchSources by owner.searchedSourcesState
    val isLyricMatching by owner.isMatchingState
    val lyricMatchError by owner.matchErrorState
    var showLocalMetadataWriteBackConfirm by owner.showLocalMetadataWriteBackConfirmState
    var showEmbeddedLyricsOverwriteConfirm by owner.showEmbeddedLyricsOverwriteConfirmState
    val preferWordTimedLyrics by AppContainer.settingsRepo.preferWordTimedLyricsFlow
        .collectAsState(initial = true)
    val visibleLyricMatchResults = remember(
        lyricMatchResultsBySource, selectedLyricMatchSources, preferWordTimedLyrics
    ) {
        filterCachedLyricMatchResults(
            resultsBySource = lyricMatchResultsBySource,
            selectedSources = selectedLyricMatchSources,
            preferWordTimedLyrics = preferWordTimedLyrics
        )
    }
    val hasSearchedSelectedLyricSources = selectedLyricMatchSources.any { source ->
        source in searchedLyricMatchSources
    }

    BackHandler { owner.handleBack(::dismissLyricsEditor) }

    fun saveLyrics(writeLocalMetadata: Boolean) {
        owner.save(
            writeLocalMetadata = writeLocalMetadata,
            onSaveLyrics = onSaveLyrics,
            onSaveFailed = onSaveFailed,
            onSaved = ::dismissLyricsEditor
        )
    }

    fun runLyricMatch(query: String, sources: Set<EditableLyricMatchSource>) {
        owner.runMatch(
            query = query,
            sources = sources,
            preferWordTimed = preferWordTimedLyrics,
            noSourceMessage = resources.getString(CoreCommonR.string.lyrics_match_no_source_selected),
            errorMessage = { error ->
                resources.getString(
                    CoreCommonR.string.lyrics_match_error,
                    editableLyricMatchFailureDescription(error)
                )
            }
        )
    }

    LyricsEditorContent(
        originalSong = originalSong,
        owner = owner,
        onDismiss = ::dismissLyricsEditor,
        onMatch = { showLyricMatchSheet = true },
        onPaste = {
            coroutineScope.launch {
                owner.pasteSelectedText(readLyricsClipboardText(clipboard, context).orEmpty())
            }
        },
        onSave = {
            if (owner.requestSave(editingSource, hasExistingSidecar)) {
                saveLyrics(writeLocalMetadata = false)
            }
        }
    )

    if (showLyricMatchSheet) {
        LyricMatchResultsSheet(
            query = lyricMatchQuery,
            onQueryChange = owner::changeQuery,
            results = visibleLyricMatchResults,
            isLoading = isLyricMatching,
            errorMessage = lyricMatchError,
            hasSearched = hasSearchedSelectedLyricSources,
            selectedSources = selectedLyricMatchSources,
            onSourceToggle = owner::toggleSource,
            onSearch = { query -> runLyricMatch(query, selectedLyricMatchSources) },
            onApply = owner::applyMatch,
            onDismiss = { showLyricMatchSheet = false }
        )
    }


    if (showLocalMetadataWriteBackConfirm) {
        AlertDialog(
            onDismissRequest = { showLocalMetadataWriteBackConfirm = false },
            title = { Text(stringResource(CoreCommonR.string.local_song_metadata_write_confirm_title)) },
            text = { Text(stringResource(CoreCommonR.string.local_song_metadata_write_confirm_message)) },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        showLocalMetadataWriteBackConfirm = false
                        saveLyrics(writeLocalMetadata = true)
                    }
                ) {
                    Text(stringResource(CoreCommonR.string.local_song_metadata_write_confirm_write))
                }
            },
            dismissButton = {
                HapticTextButton(
                    onClick = {
                        showLocalMetadataWriteBackConfirm = false
                        saveLyrics(writeLocalMetadata = false)
                    }
                ) {
                    Text(stringResource(CoreCommonR.string.local_song_metadata_write_confirm_app_only))
                }
            }
        )
    }

    if (showEmbeddedLyricsOverwriteConfirm) {
        AlertDialog(
            onDismissRequest = { showEmbeddedLyricsOverwriteConfirm = false },
            title = { Text(stringResource(CoreCommonR.string.local_lyrics_embedded_overwrite_title)) },
            text = { Text(stringResource(CoreCommonR.string.local_lyrics_embedded_overwrite_message)) },
            confirmButton = {
                HapticTextButton(
                    onClick = {
                        showEmbeddedLyricsOverwriteConfirm = false
                        saveLyrics(writeLocalMetadata = true)
                    }
                ) {
                    Text(stringResource(CoreCommonR.string.local_lyrics_embedded_overwrite_confirm))
                }
            },
            dismissButton = {
                HapticTextButton(
                    onClick = { showEmbeddedLyricsOverwriteConfirm = false }
                ) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }
}

private val LyricsEditorMinTextHeight = 128.dp
private val LyricsEditorCompactHeight = 480.dp
private val LyricsEditorContentMaxWidth = 720.dp

internal enum class LyricsEditorSection { HEADER, SONG_INFO, TABS, INPUT, ACTIONS }

internal data class LyricsEditorLayoutSpec(
    val compactHeader: Boolean,
    val scrollWholeContent: Boolean,
    val spacing: Dp,
    val horizontalPadding: Dp,
    val sections: List<LyricsEditorSection>
)

internal fun resolveLyricsEditorLayoutSpec(
    availableHeight: Dp,
    fontScale: Float
): LyricsEditorLayoutSpec {
    val compactHeader = availableHeight < LyricsEditorCompactHeight
    val baseChromeHeight = if (compactHeader) 184.dp else 288.dp
    val scalableTextHeight = if (compactHeader) 88.dp else 128.dp
    val chromeHeight = baseChromeHeight + scalableTextHeight * (fontScale.coerceAtLeast(1f) - 1f)
    return LyricsEditorLayoutSpec(
        compactHeader = compactHeader,
        // 键盘或大字体压缩视窗时让整页滚动，避免固定工具栏把输入区挤到零高度
        scrollWholeContent = availableHeight < chromeHeight + LyricsEditorMinTextHeight,
        spacing = if (compactHeader) 8.dp else 12.dp,
        horizontalPadding = if (compactHeader) 16.dp else 24.dp,
        sections = buildList {
            add(LyricsEditorSection.HEADER)
            if (!compactHeader) add(LyricsEditorSection.SONG_INFO)
            add(LyricsEditorSection.TABS)
            add(LyricsEditorSection.INPUT)
            add(LyricsEditorSection.ACTIONS)
        }
    )
}

@Composable
internal fun LyricsEditorContent(
    originalSong: SongItem,
    owner: NowPlayingLyricsEditorOwner,
    onDismiss: () -> Unit,
    onMatch: () -> Unit,
    onPaste: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val fontScale = LocalDensity.current.fontScale
    val isSaving by owner.isSavingState
    val actions = LyricsEditorChromeActions(owner::clearSelectedText, onPaste, onSave, onDismiss, onMatch)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag("lyrics-editor-layout")
            .bottomSheetScrollGuard(allowDownwardToParent = { false })
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
    ) {
        val layout = resolveLyricsEditorLayoutSpec(maxHeight, fontScale)
        val slots = LyricsEditorContentSlots(originalSong, owner, layout, isSaving, actions)
        val contentModifier = Modifier.align(Alignment.TopCenter)
            .widthIn(max = LyricsEditorContentMaxWidth)
            .fillMaxWidth()
            .fillMaxHeight()
            .testTag("lyrics-editor-content")
            .lyricsEditorScrollModifier(layout, scrollState)
            .padding(horizontal = layout.horizontalPadding, vertical = layout.spacing)
        Column(contentModifier, verticalArrangement = Arrangement.spacedBy(layout.spacing)) {
            layout.sections.forEach { section ->
                key(section) { slots.content.getValue(section)(this) }
            }
        }
    }
}

private class LyricsEditorChromeActions(
    val onClear: () -> Unit,
    val onPaste: () -> Unit,
    val onSave: () -> Unit,
    val onDismiss: () -> Unit,
    val onMatch: () -> Unit
)

private class LyricsEditorContentSlots(
    song: SongItem,
    owner: NowPlayingLyricsEditorOwner,
    layout: LyricsEditorLayoutSpec,
    isSaving: Boolean,
    actions: LyricsEditorChromeActions
) {
    private val header = resolveLyricsEditorHeaderPresentation(song.takeIf { layout.compactHeader }, isSaving)
    private val identity = resolveLyricsEditorSongIdentityPresentation(song)
    private val actionPresentation = resolveLyricsEditorActionPresentation(isSaving)
    val content: Map<LyricsEditorSection, @Composable ColumnScope.() -> Unit> = mapOf(
        LyricsEditorSection.HEADER to { LyricsEditorHeader(header, actions) },
        LyricsEditorSection.SONG_INFO to { LyricsEditorSongInfo(identity, isSaving, actions.onMatch) },
        LyricsEditorSection.TABS to { LyricsEditorTabs(owner) },
        LyricsEditorSection.INPUT to { LyricsEditorTextInput(owner, Modifier.lyricsEditorInputModifier(layout, this)) },
        LyricsEditorSection.ACTIONS to { LyricsEditorActions(actionPresentation, actions) }
    )
}

private fun Modifier.lyricsEditorScrollModifier(layout: LyricsEditorLayoutSpec, scrollState: androidx.compose.foundation.ScrollState): Modifier =
    if (layout.scrollWholeContent) verticalScroll(scrollState) else this

private fun Modifier.lyricsEditorInputModifier(layout: LyricsEditorLayoutSpec, columnScope: ColumnScope): Modifier = with(columnScope) {
    this@lyricsEditorInputModifier.fillMaxWidth().then(
        if (layout.scrollWholeContent) Modifier.height(LyricsEditorMinTextHeight)
        else Modifier.weight(1f).heightIn(min = LyricsEditorMinTextHeight)
    )
}

@Composable
private fun LyricsEditorHeader(presentation: LyricsEditorHeaderPresentation, actions: LyricsEditorChromeActions) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("lyrics-editor-header"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val identity = presentation.songIdentity
        if (identity != null) {
            LyricsEditorCompactHeaderIdentity(identity, presentation.saving, actions.onMatch)
        } else {
            Column(Modifier.weight(1f)) { LyricsEditorHeaderTitle() }
        }
        HapticTextButton(
            onClick = actions.onDismiss,
            enabled = presentation.actionsEnabled,
            modifier = Modifier.testTag("lyrics-editor-cancel")
        ) { Text(stringResource(CoreCommonR.string.action_cancel), maxLines = 1) }
    }
}

@Composable
private fun RowScope.LyricsEditorCompactHeaderIdentity(
    identity: LyricsEditorSongIdentityPresentation,
    isSaving: Boolean,
    onMatch: () -> Unit
) {
    Column(Modifier.weight(1f)) {
        LyricsEditorHeaderTitle()
        Text(
            text = identity.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    LyricsEditorMatchButton(isSaving, onMatch)
}

@Composable
private fun LyricsEditorHeaderTitle() {
    Text(
        text = stringResource(CoreCommonR.string.music_edit_lyrics),
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun LyricsEditorSongInfo(identity: LyricsEditorSongIdentityPresentation, isSaving: Boolean, onMatch: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
            .testTag("lyrics-editor-song"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = identity.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = identity.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        LyricsEditorMatchButton(isSaving, onMatch)
    }
}

@Composable
private fun LyricsEditorMatchButton(isSaving: Boolean, onMatch: () -> Unit) {
    HapticTextButton(
        onClick = onMatch,
        enabled = !isSaving,
        modifier = Modifier.testTag("lyrics-editor-match"),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(CoreCommonR.string.lyrics_match_action), maxLines = 1)
    }
}

@Composable
private fun LyricsEditorActions(presentation: LyricsEditorActionPresentation, actions: LyricsEditorChromeActions) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("lyrics-editor-actions"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HapticTextButton(
            onClick = actions.onClear,
            enabled = presentation.enabled,
            modifier = Modifier.weight(1f).testTag("lyrics-editor-clear")
        ) { Text(stringResource(CoreCommonR.string.action_clear), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        HapticTextButton(
            onClick = actions.onPaste,
            enabled = presentation.enabled,
            modifier = Modifier.weight(1f).testTag("lyrics-editor-paste")
        ) { Text(stringResource(CoreCommonR.string.action_paste), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        HapticTextButton(
            onClick = actions.onSave,
            enabled = presentation.enabled,
            modifier = Modifier.weight(1f).testTag("lyrics-editor-save")
        ) { LyricsEditorSaveLabel(presentation.saving) }
    }
}

@Composable
private fun LyricsEditorSaveLabel(saving: Boolean) {
    if (saving) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
    else Text(stringResource(CoreCommonR.string.music_save_changes), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

internal fun editableLyricMatchFailureDescription(error: Throwable): String =
    error.message.orEmpty().ifBlank { error.javaClass.simpleName }

private suspend fun readLyricsClipboardText(clipboard: Clipboard, context: Context): String? {
    val entry = clipboard.getClipEntry() ?: return null
    return firstLyricsClipboardItemText(entry.clipData, context)
}

private fun firstLyricsClipboardItemText(data: ClipData, context: Context): String =
    if (data.itemCount == 0) "" else data.getItemAt(0).coerceToText(context).toString()

@Composable
private fun LyricsEditorTabs(owner: NowPlayingLyricsEditorOwner) {
    val selectedTab by owner.selectedTabState
    val labels = listOf(
        CoreCommonR.string.lyrics_original,
        CoreCommonR.string.lyrics_translation,
        CoreCommonR.string.lyrics_romanized
    )
    PrimaryTabRow(
        selectedTabIndex = selectedTab,
        modifier = Modifier.testTag("lyrics-editor-tabs"),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        labels.forEachIndexed { index, label ->
            LyricsEditorTab(owner, index, label)
        }
    }
}

@Composable
private fun LyricsEditorTab(owner: NowPlayingLyricsEditorOwner, index: Int, label: Int) {
    Tab(
        selected = owner.isSelectedTab(index),
        onClick = { owner.selectedTabState.intValue = index },
        enabled = owner.canEdit,
        modifier = Modifier.testTag("lyrics-editor-tab-$index"),
        text = { LyricsEditorTabLabel(label) }
    )
}

@Composable
private fun LyricsEditorTabLabel(label: Int) {
    Text(stringResource(label))
}

@Composable
private fun LyricsEditorTextInput(owner: NowPlayingLyricsEditorOwner, modifier: Modifier) {
    val selectedTab by owner.selectedTabState
    val textState = listOf(
        owner.lyricsTextState,
        owner.translatedLyricsTextState,
        owner.romanizedLyricsTextState
    )[selectedTab.coerceIn(0, 2)]
    val hint = listOf(
        CoreCommonR.string.lyrics_editor_hint_original,
        CoreCommonR.string.lyrics_editor_hint_translation,
        CoreCommonR.string.lyrics_editor_hint_romanized
    )[selectedTab.coerceIn(0, 2)]
    OutlinedTextField(
        value = textState.value,
        onValueChange = owner::replaceSelectedText,
        enabled = !owner.isSavingState.value,
        modifier = modifier.testTag("lyrics-editor-input"),
        placeholder = {
            Text(
                text = stringResource(hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        maxLines = Int.MAX_VALUE
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricMatchResultsSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<RankedEditableLyricMatch>,
    isLoading: Boolean,
    errorMessage: String?,
    hasSearched: Boolean,
    selectedSources: Set<EditableLyricMatchSource>,
    onSourceToggle: (EditableLyricMatchSource) -> Unit,
    onSearch: (String) -> Unit,
    onApply: (RankedEditableLyricMatch) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false
    ) {
        LyricMatchResultsContent(
            query, onQueryChange, results, isLoading, errorMessage, hasSearched,
            selectedSources, onSourceToggle, onSearch, onApply, onDismiss
        )
    }
}

private val LyricMatchPinnedControlsMinHeight = 560.dp

internal fun shouldScrollWholeLyricMatchContent(availableHeight: Dp, fontScale: Float): Boolean =
    availableHeight < LyricMatchPinnedControlsMinHeight * fontScale.coerceAtLeast(1f)

@Composable
internal fun LyricMatchResultsContent(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<RankedEditableLyricMatch>,
    isLoading: Boolean,
    errorMessage: String?,
    hasSearched: Boolean,
    selectedSources: Set<EditableLyricMatchSource>,
    onSourceToggle: (EditableLyricMatchSource) -> Unit,
    onSearch: (String) -> Unit,
    onApply: (RankedEditableLyricMatch) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val compactListState = rememberLazyListState()
    val resultListState = rememberLazyListState()
    val fontScale = LocalDensity.current.fontScale
    val queryPresentation = resolveLyricMatchQueryPresentation(query, isLoading, selectedSources)
    val queryActions = LyricMatchQueryActions(onQueryChange, onSearch, query)
    val feedback = resolveLyricMatchFeedbackPresentation(isLoading, errorMessage, hasSearched, results.isEmpty())
    val hasFeedback = feedback.items.isNotEmpty()
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().testTag("lyrics-match-layout")
            .bottomSheetScrollGuard(allowDownwardToParent = { false })
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
    ) {
        val contentModifier = Modifier.align(Alignment.TopCenter)
            .widthIn(max = LyricsEditorContentMaxWidth).fillMaxSize()
        if (shouldScrollWholeLyricMatchContent(maxHeight, fontScale) || errorMessage != null) {
            // 短视窗共用一个列表，让来源和查询区也能滚动，长错误信息不会挤掉候选结果
            LazyColumn(
                modifier = contentModifier.testTag("lyrics-match-scroll"),
                state = compactListState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "header") { LyricMatchHeader(onDismiss) }
                item(key = "sources") { LyricMatchSources(selectedSources, isLoading, onSourceToggle) }
                item(key = "query") { LyricMatchQuery(queryPresentation, queryActions) }
                if (hasFeedback) item(key = "feedback") { LyricMatchFeedback(feedback) }
                items(results, key = ::lyricMatchResultKey) { result ->
                    LyricMatchResultCard(result, onClick = { onApply(result) })
                }
            }
        } else {
            Column(
                modifier = contentModifier.padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LyricMatchHeader(onDismiss)
                LyricMatchSources(selectedSources, isLoading, onSourceToggle)
                LyricMatchQuery(queryPresentation, queryActions)
                if (hasFeedback) LyricMatchFeedback(feedback)
                LazyColumn(
                    modifier = Modifier.weight(1f).testTag("lyrics-match-results"),
                    state = resultListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results, key = ::lyricMatchResultKey) { result ->
                        LyricMatchResultCard(result, onClick = { onApply(result) })
                    }
                }
            }
        }
    }
}

private fun lyricMatchResultKey(result: RankedEditableLyricMatch): String =
    "${result.candidate.source}:${result.candidate.id}:${result.candidate.lyrics.hashCode()}"

@Composable
private fun LyricMatchHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("lyrics-match-header"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(CoreCommonR.string.lyrics_match_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        HapticTextButton(onClick = onDismiss, modifier = Modifier.testTag("lyrics-match-cancel")) {
            Text(stringResource(CoreCommonR.string.action_cancel), maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricMatchSources(
    selectedSources: Set<EditableLyricMatchSource>,
    isLoading: Boolean,
    onSourceToggle: (EditableLyricMatchSource) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(CoreCommonR.string.lyrics_match_sources),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            lyricMatchSelectableSources.forEach { source ->
                FilterChip(
                    selected = source in selectedSources,
                    onClick = { onSourceToggle(source) },
                    enabled = !isLoading,
                    modifier = Modifier.testTag("lyrics-match-source-${source.name}"),
                    label = { Text(stringResource(source.stringResId()), maxLines = 1) }
                )
            }
        }
    }
}

private class LyricMatchQueryActions(
    val onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    query: String
) {
    val onSubmit: () -> Unit = { onSearch(query) }
    // IME 提交由匹配 owner 校验，不能套用按钮禁用规则
    val onImeSubmit: KeyboardActionScope.() -> Unit = { onSubmit() }
}

@Composable
private fun LyricMatchQuery(presentation: LyricMatchQueryPresentation, actions: LyricMatchQueryActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = presentation.query,
            onValueChange = actions.onQueryChange,
            modifier = Modifier.weight(1f).testTag("lyrics-match-query"),
            label = { Text(stringResource(CoreCommonR.string.lyrics_match_keyword)) },
            placeholder = { Text(stringResource(CoreCommonR.string.lyrics_match_keyword_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = actions.onImeSubmit)
        )
        HapticTextButton(
            onClick = actions.onSubmit,
            modifier = Modifier.testTag("lyrics-match-search"),
            enabled = presentation.searchEnabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(CoreCommonR.string.lyrics_match_search), maxLines = 1)
        }
    }
}

@Composable
private fun LyricMatchFeedback(presentation: LyricMatchFeedbackPresentation) {
    val slots = LyricMatchFeedbackSlots(presentation.errorMessage)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        presentation.items.forEach { slots.content.getValue(it)() }
    }
}

private class LyricMatchFeedbackSlots(errorMessage: String?) {
    val content: Map<LyricMatchFeedbackKind, @Composable () -> Unit> = mapOf(
        LyricMatchFeedbackKind.LOADING to {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(CoreCommonR.string.lyrics_match_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("lyrics-match-loading")
            )
        },
        LyricMatchFeedbackKind.ERROR to {
            Text(checkNotNull(errorMessage), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("lyrics-match-error"))
        },
        LyricMatchFeedbackKind.EMPTY to {
            Text(stringResource(CoreCommonR.string.lyrics_match_empty), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("lyrics-match-empty"))
        }
    )
}

@Composable
private fun LyricMatchResultCard(
    result: RankedEditableLyricMatch,
    onClick: () -> Unit
) {
    val candidate = result.candidate
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("lyrics-match-result-${candidate.id}")
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = candidate.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = candidate.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildLyricMatchMetaText(result),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun buildLyricMatchMetaText(result: RankedEditableLyricMatch): String {
    return listOfNotNull(
        stringResource(result.candidate.source.stringResId()),
        lyricMatchWordTimingLabel(result),
        lyricMatchDurationLabel(result.candidate.durationMs),
        lyricMatchDurationDeltaLabel(result.durationDeltaMs),
        stringResource(CoreCommonR.string.lyrics_match_score, result.score),
        stringResource(result.confidence.stringResId())
    ).joinToString(" · ")
}

@Composable
private fun lyricMatchWordTimingLabel(result: RankedEditableLyricMatch): String? =
    if (result.hasWordTiming) stringResource(CoreCommonR.string.lyrics_match_word_timed) else null

private fun lyricMatchDurationLabel(durationMs: Long): String? =
    if (durationMs > 0L) formatDuration(durationMs) else null

@Composable
private fun lyricMatchDurationDeltaLabel(deltaMs: Long?): String? =
    deltaMs?.let { stringResource(CoreCommonR.string.lyrics_match_duration_delta, formatDuration(it)) }

internal fun EditableLyricMatchConfidence.stringResId(): Int = when (this) {
    EditableLyricMatchConfidence.HIGH -> CoreCommonR.string.lyrics_match_confidence_high
    EditableLyricMatchConfidence.MEDIUM -> CoreCommonR.string.lyrics_match_confidence_medium
    EditableLyricMatchConfidence.LOW -> CoreCommonR.string.lyrics_match_confidence_low
}

internal fun defaultEditableLyricsMatchKeyword(song: SongItem): String {
    return listOf(
        song.customName ?: song.name,
        song.customArtist ?: song.artist
    ).filter { it.isNotBlank() }
        .joinToString(" ")
}

private fun filterCachedLyricMatchResults(
    resultsBySource: Map<EditableLyricMatchSource, List<RankedEditableLyricMatch>>,
    selectedSources: Set<EditableLyricMatchSource>,
    preferWordTimedLyrics: Boolean = true
): List<RankedEditableLyricMatch> {
    if (selectedSources.isEmpty()) {
        return emptyList()
    }
    return lyricMatchSelectableSources.asSequence()
        .filter { it in selectedSources }
        .flatMap { source -> resultsBySource[source].orEmpty().asSequence() }
        .sortedWith(
            editableLyricMatchResultComparator(
                sourceRank = { source ->
                    val index = lyricMatchSelectableSources.indexOf(source)
                    if (index >= 0) lyricMatchSelectableSources.size - index else 0
                },
                sourceFallbackRank = lyricMatchSelectableSources::indexOf,
                preferWordTimed = preferWordTimedLyrics
            )
        )
        .toList()
}

private val lyricMatchSelectableSources = listOf(
    EditableLyricMatchSource.KUGOU,
    EditableLyricMatchSource.CLOUD_MUSIC,
    EditableLyricMatchSource.QQ_MUSIC,
    EditableLyricMatchSource.LRCLIB,
    EditableLyricMatchSource.AMLL_TTML,
    EditableLyricMatchSource.YOUTUBE_MUSIC
)

internal fun EditableLyricMatchSource.stringResId(): Int {
    return when (this) {
        EditableLyricMatchSource.KUGOU -> CoreCommonR.string.lyrics_match_source_kugou
        EditableLyricMatchSource.CLOUD_MUSIC -> CoreCommonR.string.lyrics_match_source_cloud_music
        EditableLyricMatchSource.QQ_MUSIC -> CoreCommonR.string.lyrics_match_source_qq_music
        EditableLyricMatchSource.AMLL_TTML -> CoreCommonR.string.lyrics_match_source_amll_ttml
        EditableLyricMatchSource.LRCLIB -> CoreCommonR.string.lyrics_match_source_lrclib
        EditableLyricMatchSource.YOUTUBE_MUSIC -> CoreCommonR.string.lyrics_match_source_youtube_music
    }
}
