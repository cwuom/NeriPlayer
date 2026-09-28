package moe.ouom.neriplayer.ui.screen.nowplaying.edit

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
 * File: moe.ouom.neriplayer.ui.screen.nowplaying.edit/NowPlayingSongEditSheet
 */

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.component.local.LocalSongSyncConfirmDialog
import moe.ouom.neriplayer.ui.component.lyrics.LyricEntry
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.component.lyrics.toEditableLyricsText
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.LyricsEditorSheet
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import moe.ouom.neriplayer.ui.util.rememberSongDisplayCoverUrl


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSongInfoSheet(
    viewModel: NowPlayingViewModel,
    originalSong: SongItem,
    displayedLyrics: List<LyricEntry>,
    displayedTranslatedLyrics: List<LyricEntry>,
    displayedRomanizedLyrics: List<LyricEntry> = emptyList(),
    onDismiss: () -> Unit,
    onSavingChanged: (Boolean) -> Unit = {},
    snackbarHostState: SnackbarHostState,
    offlineMode: Boolean = false
) {
    val context = LocalContext.current
    val composeResources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun clearEditSongInfoFocus() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    // 监听当前播放的歌曲, 以便在"获取歌曲信息"后更新UI
    val currentSong by PlayerManager.currentSongFlow.collectAsStateWithLifecycle()
    val actualSong = if (currentSong?.sameIdentityAs(originalSong) == true) {
        currentSong!!
    } else {
        originalSong
    }
    val actualSongKey = actualSong.stableKey()
    val canReplaceCoverFromLocalFile = shouldAllowLocalCoverReplacement(actualSong, context)
    val resolvedDisplayCoverUrl = rememberSongDisplayCoverUrl(actualSong)

    val onSavingChangedState = rememberUpdatedState(onSavingChanged)
    val owner = remember(actualSongKey) {
        NowPlayingSongEditOwner(
            initialSong = actualSong,
            initialCoverUrl = resolveEditSongInitialCoverUrl(actualSong, resolvedDisplayCoverUrl),
            initialBaseline = resolveEditSongBaselineFromSong(
                song = actualSong,
                resolvedDisplayCoverUrl = resolvedDisplayCoverUrl,
                displayedLyric = displayedLyrics.toEditableLyricsText(),
                displayedTranslatedLyric = displayedTranslatedLyrics.toEditableLyricsText(),
                displayedRomanizedLyric = displayedRomanizedLyrics.toEditableLyricsText()
            ),
            scope = coroutineScope,
            onSavingChanged = { onSavingChangedState.value(it) },
            playbackPort = PlayerManagerNowPlayingSongEditPlaybackPort
        )
    }
    var songName by owner.songNameState
    var artistName by owner.artistNameState
    var showSearchResults by owner.showSearchResultsState
    var selectedSongForFill by owner.selectedSongForFillState
    var lyricsEditorSeed by owner.lyricsEditorSeedState
    var pendingLyricsSourceSeed by owner.pendingLyricsSourceSeedState
    var isPendingEmbeddedLyricsLoading by owner.isPendingEmbeddedLyricsLoadingState
    var showLocalMetadataWriteBackConfirm by owner.showLocalMetadataWriteBackConfirmState
    var showFillLyricsMetadataWriteBackConfirm by owner.showFillLyricsMetadataWriteBackConfirmState
    var isOriginalInfoRestoring by owner.isOriginalInfoRestoringState
    var showLocalCoverSyncConfirm by owner.showLocalCoverSyncConfirmState
    var pendingCoverReplacementSong by owner.pendingCoverReplacementSongState
    var isCoverImporting by owner.isCoverImportingState
    var isSaving by owner.isSavingState

    DisposableEffect(owner) {
        onDispose(owner::dispose)
    }

    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { sourceUri ->
        val targetSong = pendingCoverReplacementSong
        pendingCoverReplacementSong = null
        withVerifiedEditCoverPickerTarget(
            sourceUri = sourceUri,
            pendingSong = targetSong,
            currentSong = currentSong,
            context = context,
            onVerified = { verifiedSong, verifiedUri ->
                owner.importCover(
                    context = context,
                    song = verifiedSong,
                    sourceUri = verifiedUri,
                    snackbarHostState = snackbarHostState,
                    resources = composeResources
                )
            }
        )
    }

    val scrollState = rememberScrollState()

    LaunchedEffect(actualSong.stableKey()) {
        owner.syncUneditedSong(actualSong, resolvedDisplayCoverUrl)
    }

    LaunchedEffect(actualSong.stableKey()) {
        owner.loadManagedBaseline(context, actualSong)
    }

    LaunchedEffect(actualSong.stableKey(), resolvedDisplayCoverUrl) {
        owner.applyResolvedCover(actualSong, resolvedDisplayCoverUrl)
    }

    LaunchedEffect(Unit) {
        viewModel.prepareForSearch(actualSong.displayName())
    }

    fun applyOriginalInfo(
        restoreCover: Boolean,
        restoreTitle: Boolean,
        restoreArtist: Boolean,
        restoreLyrics: Boolean
    ) {
        owner.restoreOriginalInfo(
            context = context,
            song = actualSong,
            viewModel = viewModel,
            selection = EditSongRestoreSelection(
                cover = restoreCover,
                title = restoreTitle,
                artist = restoreArtist,
                lyrics = restoreLyrics
            )
        )
    }

    fun saveEditedSongInfo(writeLocalMetadata: Boolean) {
        owner.saveEditedSongInfo(
            song = actualSong,
            viewModel = viewModel,
            writeLocalMetadata = writeLocalMetadata,
            snackbarHostState = snackbarHostState,
            resources = composeResources,
            onSaved = {
                clearEditSongInfoFocus()
                onDismiss()
            }
        )
    }

    fun writeFetchedLyricsToLocalMetadata() {
        owner.writeFetchedLyricsToLocalMetadata(
            song = actualSong,
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            resources = composeResources
        )
    }

    // 使用 AnimatedVisibility 控制内容显示, 避免重叠
    AnimatedVisibility(
        visible = lyricsEditorSeed == null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.music_edit_info),
                style = MaterialTheme.typography.titleMedium
            )

            HapticTextButton(
                onClick = {
                    clearEditSongInfoFocus()
                    onDismiss()
                },
                enabled = !isSaving
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .bottomSheetScrollGuard { scrollState.value == 0 }
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EditSongCoverField(
                owner = owner,
                offlineMode = offlineMode,
                canReplaceFromFile = canReplaceCoverFromLocalFile,
                onRestore = {
                    applyOriginalInfo(
                        restoreCover = true,
                        restoreTitle = false,
                        restoreArtist = false,
                        restoreLyrics = false
                    )
                },
                onSelectLocalCover = {
                    clearEditSongInfoFocus()
                    showLocalCoverSyncConfirm = true
                }
            )
            EditSongEditableTextField(
                value = songName,
                onValueChange = owner::updateTitle,
                label = stringResource(R.string.music_edit_title),
                restoreDescription = stringResource(R.string.music_restore_title),
                enabled = owner.canEditFields(),
                onRestore = {
                    applyOriginalInfo(
                        restoreCover = false,
                        restoreTitle = true,
                        restoreArtist = false,
                        restoreLyrics = false
                    )
                }
            )
            EditSongEditableTextField(
                value = artistName,
                onValueChange = owner::updateArtist,
                label = stringResource(R.string.music_edit_artist),
                restoreDescription = stringResource(R.string.music_restore_artist),
                enabled = owner.canEditFields(),
                onRestore = {
                    applyOriginalInfo(
                        restoreCover = false,
                        restoreTitle = false,
                        restoreArtist = true,
                        restoreLyrics = false
                    )
                }
            )
            EditSongLyricsButton(
                busy = owner.isLyricsButtonBusy(),
                enabled = owner.canOpenLyricsEditor(),
                onClick = {
                    clearEditSongInfoFocus()
                    owner.openLyricsEditor(
                        context = context,
                        actualSong = actualSong,
                        displayedLyrics = displayedLyrics,
                        displayedTranslatedLyrics = displayedTranslatedLyrics,
                        displayedRomanizedLyrics = displayedRomanizedLyrics,
                        snackbarHostState = snackbarHostState,
                        composeResources = composeResources
                    )
                }
            )
        }

        EditSongActionRow(
            isRestoring = isOriginalInfoRestoring,
            isSaving = isSaving,
            isCoverImporting = isCoverImporting,
            onSearch = {
                viewModel.prepareForSearch(songName)
                viewModel.performSearch()
                showSearchResults = true
                focusManager.clearFocus()
            },
            onRestoreAll = {
                applyOriginalInfo(
                    restoreCover = true,
                    restoreTitle = true,
                    restoreArtist = true,
                    restoreLyrics = true
                )
            },
            onSave = {
                owner.requestSave(
                    song = actualSong,
                    onConfirmationNeeded = ::clearEditSongInfoFocus,
                    saveInAppOnly = { saveEditedSongInfo(writeLocalMetadata = false) }
                )
            }
        )
    }
    } // 关闭 AnimatedVisibility

    if (showLocalCoverSyncConfirm) {
        LocalSongSyncConfirmDialog(
            actionLabel = composeResources.getString(R.string.music_edit_cover),
            onConfirm = {
                showLocalCoverSyncConfirm = false
                if (shouldAllowLocalCoverReplacement(actualSong, context)) {
                    pendingCoverReplacementSong = actualSong
                    clearEditSongInfoFocus()
                    coverPickerLauncher.launch("image/*")
                }
            },
            onDismiss = { showLocalCoverSyncConfirm = false }
        )
    }

    if (showLocalMetadataWriteBackConfirm) {
        EditSongLocalMetadataWriteBackConfirmDialog(
            isSaving = isSaving,
            onWriteToLocal = {
                showLocalMetadataWriteBackConfirm = false
                saveEditedSongInfo(writeLocalMetadata = true)
            },
            onSaveInAppOnly = {
                showLocalMetadataWriteBackConfirm = false
                saveEditedSongInfo(writeLocalMetadata = false)
            },
            onCancel = { showLocalMetadataWriteBackConfirm = false }
        )
    }

    if (showFillLyricsMetadataWriteBackConfirm) {
        EditSongFillLyricsWriteBackDialog(
            enabled = !isSaving,
            onWriteToLocal = {
                showFillLyricsMetadataWriteBackConfirm = false
                writeFetchedLyricsToLocalMetadata()
            },
            onSaveInAppOnly = { showFillLyricsMetadataWriteBackConfirm = false }
        )
    }

    // 填充选项对话框
    if (selectedSongForFill != null) {
        FillOptionsDialog(
            songResult = selectedSongForFill!!,
            enabled = !isSaving && !isOriginalInfoRestoring,
            onDismiss = { selectedSongForFill = null },
            onConfirm = { fillCover, fillTitle, fillArtist, fillLyrics ->
                owner.applySelectedFillOptions(
                    fillCover,
                    fillTitle,
                    fillArtist,
                    fillLyrics
                ) { selected ->
                    owner.fillSelectedLyrics(
                        context,
                        actualSong,
                        selected,
                        viewModel,
                        snackbarHostState
                    )
                }
            }
        )
    }

    // 歌词编辑器
    if (lyricsEditorSeed != null) {
        LyricsEditorSheet(
            originalSong = actualSong,
            initialLyrics = lyricsEditorSeed!!.lyrics,
            initialTranslatedLyrics = lyricsEditorSeed!!.translatedLyrics,
            initialRomanizedLyrics = lyricsEditorSeed!!.romanizedLyrics,
            editingSource = lyricsEditorSeed!!.source,
            hasExistingSidecar = lyricsEditorSeed!!.hasSidecar,
            onSaveLyrics = { draft -> owner.saveEditorLyrics(actualSong, draft) },
            onSaveFailed = {
                coroutineScope.launch {
                    snackbarHostState.showNeriSnackbar(
                        message = composeResources.getString(R.string.local_song_lyrics_write_failed),
                        withDismissAction = true,
                        duration = SnackbarDuration.Long
                    )
                }
            },
            onDismiss = {
                clearEditSongInfoFocus()
                lyricsEditorSeed = null
            }
        )
    }

    if (pendingLyricsSourceSeed?.hasEmbeddedLyrics == true || isPendingEmbeddedLyricsLoading) {
        EditSongLyricsSourceChoiceDialog(
            loading = isPendingEmbeddedLyricsLoading,
            enabled = !isSaving,
            onDismiss = owner::dismissLyricsSourceChoice,
            onChooseSidecar = owner::chooseSidecarLyrics,
            onChooseEmbedded = owner::chooseEmbeddedLyrics
        )
    }

    if (showSearchResults) {
        EditSongSearchResultsSheet(
            viewModel = viewModel,
            offlineMode = offlineMode,
            onDismiss = {
                clearEditSongInfoFocus()
                showSearchResults = false
            },
            onSelect = { result ->
                clearEditSongInfoFocus()
                selectedSongForFill = result
                showSearchResults = false
            }
        )
    }
}

@Composable
private fun EditSongActionRow(
    isRestoring: Boolean,
    isSaving: Boolean,
    isCoverImporting: Boolean,
    onSearch: () -> Unit,
    onRestoreAll: () -> Unit,
    onSave: () -> Unit
) {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val fontSize = editSongActionFontSize(width)
    val availability = editSongActionAvailability(isRestoring, isSaving, isCoverImporting)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        EditSongSearchAction(availability.edit, fontSize, Modifier.weight(1f), onSearch)
        EditSongRestoreAction(availability.edit, fontSize, Modifier.weight(1f), onRestoreAll)
        EditSongSaveAction(
            enabled = availability.save,
            isSaving = isSaving,
            fontSize = fontSize,
            modifier = Modifier.weight(1f),
            onClick = onSave
        )
    }
}

internal fun editSongActionFontSize(width: Dp): TextUnit =
    if (width < 420.dp) 11.sp else 13.sp

internal data class EditSongActionAvailability(val edit: Boolean, val save: Boolean)

internal fun editSongActionAvailability(
    isRestoring: Boolean, isSaving: Boolean, isCoverImporting: Boolean
): EditSongActionAvailability {
    val edit = !isRestoring && !isSaving
    return EditSongActionAvailability(edit = edit, save = edit && !isCoverImporting)
}

@Composable
private fun EditSongSearchAction(
    enabled: Boolean, fontSize: TextUnit, modifier: Modifier, onClick: () -> Unit
) {
    HapticTextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.music_auto_fill), maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis, fontSize = fontSize
        )
    }
}

@Composable
private fun EditSongRestoreAction(
    enabled: Boolean, fontSize: TextUnit, modifier: Modifier, onClick: () -> Unit
) {
    HapticTextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.music_restore_original), maxLines = 1,
            softWrap = false, overflow = TextOverflow.Ellipsis, fontSize = fontSize
        )
    }
}

@Composable
private fun EditSongSaveAction(
    enabled: Boolean,
    isSaving: Boolean,
    fontSize: TextUnit,
    modifier: Modifier,
    onClick: () -> Unit
) {
    HapticTextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        if (isSaving) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.music_save_changes), maxLines = 1,
            softWrap = false, overflow = TextOverflow.Ellipsis, fontSize = fontSize
        )
    }
}

@Composable
private fun EditSongEditableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    restoreDescription: String,
    enabled: Boolean,
    onRestore: () -> Unit,
    placeholder: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        trailingIcon = {
            HapticIconButton(enabled = enabled, onClick = onRestore) {
                Icon(Icons.Outlined.Refresh, contentDescription = restoreDescription)
            }
        }
    )
}

@Composable
private fun EditSongCoverField(
    owner: NowPlayingSongEditOwner,
    offlineMode: Boolean,
    canReplaceFromFile: Boolean,
    onRestore: () -> Unit,
    onSelectLocalCover: () -> Unit
) {
    val enabled = owner.canEditFields()
    EditSongCoverUrlInput(owner, onRestore)
    EditSongCoverPreview(
        state = EditSongCoverPreviewState(
            coverUrl = owner.coverUrlState.value,
            offlineMode = offlineMode,
            canReplaceFromFile = canReplaceFromFile,
            enabled = enabled
        ),
        onClick = onSelectLocalCover
    )
}

@Composable
private fun EditSongCoverUrlInput(
    owner: NowPlayingSongEditOwner,
    onRestore: () -> Unit
) {
    val resources = LocalResources.current
    EditSongEditableTextField(
        value = owner.coverUrlState.value,
        onValueChange = owner.onCoverUrlChange,
        label = resources.getString(R.string.music_cover_url),
        restoreDescription = resources.getString(R.string.music_restore_cover),
        enabled = owner.canEditFields(),
        onRestore = onRestore,
        placeholder = { Text(resources.getString(R.string.music_cover_url_hint)) }
    )
}

internal data class EditSongCoverPreviewState(
    val coverUrl: String,
    val offlineMode: Boolean,
    val canReplaceFromFile: Boolean,
    val enabled: Boolean
) {
    val clickable: Boolean get() = canReplaceFromFile && enabled
}

@Composable
private fun EditSongCoverPreview(
    state: EditSongCoverPreviewState,
    onClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = state.clickable, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            EditSongCoverPreviewContent(state)
        }
    }
}

@Composable
private fun EditSongCoverPreviewContent(state: EditSongCoverPreviewState) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        editSongCoverRenderer(state)(state)
    }
}

private val coverImageRenderer: @Composable (EditSongCoverPreviewState) -> Unit = { state ->
    EditSongCoverImage(state)
}

private val coverPlaceholderRenderer: @Composable (EditSongCoverPreviewState) -> Unit = { state ->
    EditSongCoverPlaceholder(state.canReplaceFromFile)
}

internal fun editSongCoverRenderer(state: EditSongCoverPreviewState): @Composable (EditSongCoverPreviewState) -> Unit =
    if (state.coverUrl.isNotBlank()) coverImageRenderer else coverPlaceholderRenderer

@Composable
private fun EditSongCoverImage(state: EditSongCoverPreviewState) {
    AsyncImage(
        model = offlineCachedImageRequest(
            context = LocalContext.current,
            data = state.coverUrl,
            sizePx = 384,
            allowHardware = false,
            offlineMode = state.offlineMode
        ),
        contentDescription = editSongCoverDescription(state.canReplaceFromFile),
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun EditSongCoverPlaceholder(canReplaceFromFile: Boolean) {
    Icon(Icons.Outlined.Edit, contentDescription = editSongCoverDescription(canReplaceFromFile))
}

@Composable
private fun editSongCoverDescription(canReplaceFromFile: Boolean): String? =
    stringResource(R.string.music_edit_cover).takeIf { canReplaceFromFile }

@Composable
private fun EditSongLyricsButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    HapticTextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.music_edit_lyrics))
    }
}
