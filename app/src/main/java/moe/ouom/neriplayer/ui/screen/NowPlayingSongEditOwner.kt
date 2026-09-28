package moe.ouom.neriplayer.ui.screen

import android.os.SystemClock
import android.net.Uri
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.media.CustomSongCoverStorage
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.component.lyrics.LyricsEditorSeed
import moe.ouom.neriplayer.ui.component.lyrics.resolveLocalLyricsEditorSeed
import moe.ouom.neriplayer.ui.component.lyrics.resolveLyricsEditorSeed
import moe.ouom.neriplayer.ui.component.lyrics.toEditableLyricsText
import moe.ouom.neriplayer.ui.feedback.showNeriSnackbar
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel

internal class NowPlayingSongEditOwner(
    initialSong: SongItem,
    initialCoverUrl: String,
    initialBaseline: EditSongBaseline,
    private val scope: CoroutineScope,
    private val onSavingChanged: (Boolean) -> Unit,
    private val playbackPort: NowPlayingSongEditPlaybackPort
) {
    val coverUrlState = mutableStateOf(initialCoverUrl)
    val coverWasManuallyChangedState = mutableStateOf(false)
    val songNameState = mutableStateOf(initialSong.customName ?: initialSong.name)
    val artistNameState = mutableStateOf(initialSong.customArtist ?: initialSong.artist)
    val editBaselineState = mutableStateOf(initialBaseline)
    val showSearchResultsState = mutableStateOf(false)
    val selectedSongForFillState = mutableStateOf<SongSearchInfo?>(null)
    val lyricsEditorSeedState = mutableStateOf<LyricsEditorSeed?>(null)
    val pendingLyricsSourceSeedState = mutableStateOf<LyricsEditorSeed?>(null)
    val isLyricsEditorOpeningState = mutableStateOf(false)
    val isPendingEmbeddedLyricsLoadingState = mutableStateOf(false)
    val shouldClearLyricsState = mutableStateOf(false)
    val shouldRestoreLyricsState = mutableStateOf(false)
    val originalLyricState = mutableStateOf<String?>(null)
    val originalTranslatedLyricState = mutableStateOf<String?>(null)
    val originalRomanizedLyricState = mutableStateOf<String?>(null)
    val pendingLyricsDraftState = mutableStateOf<EditSongLyricsDraft?>(null)
    val shouldRestoreCoverBaseState = mutableStateOf(false)
    val shouldRestoreTitleBaseState = mutableStateOf(false)
    val shouldRestoreArtistBaseState = mutableStateOf(false)
    val shouldClearMatchedMetadataState = mutableStateOf(false)
    val showLocalMetadataWriteBackConfirmState = mutableStateOf(false)
    val showFillLyricsMetadataWriteBackConfirmState = mutableStateOf(false)
    val lyricsEditorRequestIdState = mutableIntStateOf(0)
    val originalInfoRequestIdState = mutableIntStateOf(0)
    val isOriginalInfoRestoringState = mutableStateOf(false)
    val showLocalCoverSyncConfirmState = mutableStateOf(false)
    val pendingCoverReplacementSongState = mutableStateOf<SongItem?>(null)
    val userHasEditedState = mutableStateOf(false)
    val isCoverImportingState = mutableStateOf(false)
    val isSavingState = mutableStateOf(false)

    private var lyricsEditorSeed by lyricsEditorSeedState
    private var pendingLyricsSourceSeed by pendingLyricsSourceSeedState
    private var isLyricsEditorOpening by isLyricsEditorOpeningState
    private var isPendingEmbeddedLyricsLoading by isPendingEmbeddedLyricsLoadingState
    private var shouldClearLyrics by shouldClearLyricsState
    private var shouldRestoreLyrics by shouldRestoreLyricsState
    private var originalLyric by originalLyricState
    private var originalTranslatedLyric by originalTranslatedLyricState
    private var originalRomanizedLyric by originalRomanizedLyricState
    private var pendingLyricsDraft by pendingLyricsDraftState
    private var lyricsEditorRequestId by lyricsEditorRequestIdState
    private var editBaseline by editBaselineState
    private var coverUrl by coverUrlState
    private var coverWasManuallyChanged by coverWasManuallyChangedState
    private var songName by songNameState
    private var artistName by artistNameState
    private var shouldRestoreCoverBase by shouldRestoreCoverBaseState
    private var shouldRestoreTitleBase by shouldRestoreTitleBaseState
    private var shouldRestoreArtistBase by shouldRestoreArtistBaseState
    private var shouldClearMatchedMetadata by shouldClearMatchedMetadataState
    private var userHasEdited by userHasEditedState
    private var isOriginalInfoRestoring by isOriginalInfoRestoringState
    private var originalInfoRequestId by originalInfoRequestIdState
    private var savingInProgress by isSavingState
    private var isCoverImporting by isCoverImportingState

    private var disposed = false
    private var restoreJob: Job? = null
    private var lyricsLoadJob: Job? = null
    private var coverImportJob: Job? = null
    private var saveJob: Job? = null

    val onCoverUrlChange: (String) -> Unit = ::updateCoverUrl

    fun setSaving(saving: Boolean) {
        isSavingState.value = saving
        onSavingChanged(saving)
    }

    fun canEditFields(): Boolean = !savingInProgress && !isOriginalInfoRestoring

    fun isLyricsButtonBusy(): Boolean = isLyricsEditorOpening || isOriginalInfoRestoring

    fun canOpenLyricsEditor(): Boolean = canEditFields() && !isLyricsEditorOpening

    private fun canStartEditAction(): Boolean = canEditFields() && !disposed

    fun updateCoverUrl(value: String) {
        if (savingInProgress || isOriginalInfoRestoring) return
        coverUrl = value
        coverWasManuallyChanged = true
        userHasEdited = true
        shouldRestoreCoverBase = false
    }

    fun updateTitle(value: String) {
        if (savingInProgress || isOriginalInfoRestoring) return
        songName = value
        userHasEdited = true
        shouldRestoreTitleBase = false
    }

    fun updateArtist(value: String) {
        if (savingInProgress || isOriginalInfoRestoring) return
        artistName = value
        userHasEdited = true
        shouldRestoreArtistBase = false
    }

    fun applyFillOptions(
        result: SongSearchInfo,
        cover: Boolean,
        title: Boolean,
        artist: Boolean
    ) {
        if (savingInProgress || isOriginalInfoRestoring) return
        userHasEdited = true
        applyFillCover(result, cover)
        applyFillTitle(result, title)
        applyFillArtist(result, artist)
        showSearchResultsState.value = false
        selectedSongForFillState.value = null
    }

    private fun applyFillCover(result: SongSearchInfo, selected: Boolean) {
        if (selected) updateCoverUrl(result.coverUrl?.replaceFirst("http://", "https://").orEmpty())
    }

    private fun applyFillTitle(result: SongSearchInfo, selected: Boolean) {
        if (selected) updateTitle(result.songName)
    }

    private fun applyFillArtist(result: SongSearchInfo, selected: Boolean) {
        if (selected) updateArtist(result.singer)
    }

    fun fillSelectedLyrics(
        context: android.content.Context,
        song: SongItem,
        result: SongSearchInfo,
        viewModel: NowPlayingViewModel,
        snackbarHostState: androidx.compose.material3.SnackbarHostState
    ) {
        if (!canStartEditAction()) return
        viewModel.fillLyrics(context, song, result) { success, message ->
            publishFilledLyricsResult(success, message, song, snackbarHostState)
        }
    }

    private fun publishFilledLyricsResult(
        success: Boolean,
        message: String,
        song: SongItem,
        snackbarHostState: androidx.compose.material3.SnackbarHostState
    ) {
        if (disposed) return
        showFillLyricsMetadataWriteBackConfirmState.value =
            keepFilledLyricsWriteBackPrompt(
                showFillLyricsMetadataWriteBackConfirmState.value,
                success,
                savingInProgress,
                song.isLocalSong()
            )
        scope.launch {
            snackbarHostState.showNeriSnackbar(
                message = message,
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
        }
    }

    internal fun applySelectedFillOptions(
        cover: Boolean,
        title: Boolean,
        artist: Boolean,
        lyrics: Boolean,
        onLyricsSelected: (SongSearchInfo) -> Unit
    ): Boolean {
        if (!canStartEditAction()) return false
        val selected = selectedSongForFillState.value ?: return false
        applyFillOptions(selected, cover, title, artist)
        if (lyrics) onLyricsSelected(selected)
        return true
    }

    fun syncUneditedSong(song: SongItem, resolvedCoverUrl: String?) {
        isOriginalInfoRestoring = false
        if (userHasEdited) return
        coverUrl = resolveEditSongInitialCoverUrl(song, resolvedCoverUrl)
        coverWasManuallyChanged = false
        songName = song.customName ?: song.name
        artistName = song.customArtist ?: song.artist
        shouldRestoreCoverBase = false
        shouldRestoreTitleBase = false
        shouldRestoreArtistBase = false
        shouldClearMatchedMetadata = false
    }

    fun applyManagedBaseline(
        metadata: moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata?,
        coverReference: String?,
        sidecarLyrics: moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedLyricsBundle?
    ) {
        if (userHasEdited || disposed) return
        editBaseline = resolveManagedEditSongBaseline(
            current = editBaseline,
            metadata = metadata,
            coverReference = coverReference,
            sidecarLyrics = sidecarLyrics
        )
    }

    fun applyResolvedCover(song: SongItem, resolvedCoverUrl: String?) {
        if (!userHasEdited) refreshBaselineCover(song, resolvedCoverUrl)
        if (shouldApplyResolvedEditSongCover(userHasEdited, coverUrl, resolvedCoverUrl)) {
            coverUrl = resolvedCoverUrl.orEmpty()
        }
    }

    private fun refreshBaselineCover(song: SongItem, resolvedCoverUrl: String?) {
        val refreshed = refreshedEditSongBaselineCover(
            editBaseline.coverUrl,
            resolveEditSongInitialCoverUrl(song, resolvedCoverUrl)
        ) ?: return
        editBaseline = editBaseline.copy(coverUrl = refreshed)
        coverUrl = refreshed
    }

    suspend fun loadManagedBaseline(context: android.content.Context, song: SongItem) {
        if (userHasEdited) return
        applyManagedBaselineSnapshot(context, song)
    }

    private suspend fun applyManagedBaselineSnapshot(context: android.content.Context, song: SongItem) {
        val snapshot = readManagedEditSongBaselineSnapshot(context, song) ?: return
        applyManagedBaseline(snapshot.metadata, snapshot.coverReference, snapshot.sidecarLyrics)
    }

    fun nextOriginalInfoRequest(): Int {
        restoreJob?.cancel()
        return ++originalInfoRequestIdState.intValue
    }

    fun nextLyricsEditorRequest(): Int {
        lyricsLoadJob?.cancel()
        return ++lyricsEditorRequestIdState.intValue
    }

    fun launchRestore(block: suspend CoroutineScope.() -> Unit) {
        restoreJob?.cancel()
        restoreJob = scope.launch(block = block)
    }

    fun launchLyricsLoad(block: suspend CoroutineScope.() -> Unit) {
        lyricsLoadJob?.cancel()
        lyricsLoadJob = scope.launch(block = block)
    }

    fun launchCoverImport(block: suspend CoroutineScope.() -> Unit) {
        coverImportJob?.cancel()
        coverImportJob = scope.launch(block = block)
    }

    fun launchSave(block: suspend CoroutineScope.() -> Unit) {
        saveJob = scope.launch(block = block)
    }

    fun restoreOriginalInfo(
        context: android.content.Context,
        song: SongItem,
        viewModel: NowPlayingViewModel,
        selection: EditSongRestoreSelection
    ) {
        val requestId = nextOriginalInfoRequest()
        isOriginalInfoRestoring = true
        launchRestore {
            var baseline = editBaseline
            val outcome = captureEditSongOperation(onCancelled = { clearCancelledRestore(requestId) }) {
                baseline = resolveRestoreBaseline(context, song, selection, baseline)
                dispatchOriginalInfoRestore(context, song, viewModel, requestId, selection, baseline)
            }
            outcome.exceptionOrNull()?.let { error ->
                NPLogger.e("NowPlayingSongEdit", "恢复歌曲原始信息失败", error)
                applyRestoredOriginalInfo(requestId, selection, baseline, null)
            }
        }
    }

    private fun clearCancelledRestore(requestId: Int) {
        if (isRestoreRequestCurrent(requestId)) isOriginalInfoRestoring = false
    }

    private suspend fun resolveRestoreBaseline(
        context: android.content.Context,
        song: SongItem,
        selection: EditSongRestoreSelection,
        baseline: EditSongBaseline
    ): EditSongBaseline {
        if (!selection.lyrics) return baseline
        return withContext(Dispatchers.IO) {
            resolveManagedEditSongBaselineAtRestore(context, song, baseline)
        }
    }

    private fun dispatchOriginalInfoRestore(
        context: android.content.Context,
        song: SongItem,
        viewModel: NowPlayingViewModel,
        requestId: Int,
        selection: EditSongRestoreSelection,
        baseline: EditSongBaseline
    ) {
        if (!shouldFetchOriginalSongInfo(song)) {
            applyRestoredOriginalInfo(requestId, selection, baseline, null)
            return
        }
        viewModel.fetchOriginalInfo(context, song) { success, info, _ ->
            applyRestoredOriginalInfo(requestId, selection, baseline, info.takeIf { success })
        }
    }

    internal fun applyRestoredOriginalInfo(
        requestId: Int,
        selection: EditSongRestoreSelection,
        baseline: EditSongBaseline,
        sourceInfo: NowPlayingViewModel.OriginalSongInfo?
    ): Boolean {
        if (!isRestoreRequestCurrent(requestId)) return false
        restoreTitle(selection.title, baseline, sourceInfo)
        restoreArtist(selection.artist, baseline, sourceInfo)
        restoreCover(selection.cover, baseline, sourceInfo)
        if (selection.lyrics) applyRestoredLyrics(baseline, sourceInfo)
        if (selection.all) shouldClearMatchedMetadata = true
        userHasEdited = true
        isOriginalInfoRestoring = false
        return true
    }

    private fun restoreTitle(
        selected: Boolean, baseline: EditSongBaseline, source: NowPlayingViewModel.OriginalSongInfo?
    ) {
        if (!selected) return
        songName = source?.name ?: baseline.title
        shouldRestoreTitleBase = true
    }

    private fun restoreArtist(
        selected: Boolean, baseline: EditSongBaseline, source: NowPlayingViewModel.OriginalSongInfo?
    ) {
        if (!selected) return
        artistName = source?.artist ?: baseline.artist
        shouldRestoreArtistBase = true
    }

    private fun restoreCover(
        selected: Boolean, baseline: EditSongBaseline, source: NowPlayingViewModel.OriginalSongInfo?
    ) {
        if (!selected) return
        coverUrl = resolveEditSongRestoredCoverUrl(source?.coverUrl, baseline.coverUrl)
        coverWasManuallyChanged = false
        shouldRestoreCoverBase = true
    }

    private fun applyRestoredLyrics(
        baseline: EditSongBaseline,
        sourceInfo: NowPlayingViewModel.OriginalSongInfo?
    ) {
        pendingLyricsDraft = null
        val plan = resolveEditSongLyricsRestorePlan(baseline, sourceInfo)
        shouldClearLyrics = plan.shouldClearLyrics
        shouldRestoreLyrics = plan.shouldRestoreLyrics
        originalLyric = plan.originalLyric
        originalTranslatedLyric = plan.originalTranslatedLyric
        originalRomanizedLyric = plan.originalRomanizedLyric
    }

    private fun isRestoreRequestCurrent(requestId: Int): Boolean =
        !disposed && originalInfoRequestId == requestId

    fun requestSave(
        song: SongItem,
        onConfirmationNeeded: () -> Unit,
        saveInAppOnly: () -> Unit
    ) {
        if (!canStartEditAction()) return
        dispatchSaveDestination(song, onConfirmationNeeded, saveInAppOnly)
    }

    private fun dispatchSaveDestination(
        song: SongItem,
        onConfirmationNeeded: () -> Unit,
        saveInAppOnly: () -> Unit
    ) {
        if (shouldConfirmLocalMetadataWriteBack(
                song = song,
                title = songName,
                artist = artistName,
                coverUrl = coverUrl,
                hasPendingLyricsChange = hasPendingLyricsChange()
            )
        ) {
            onConfirmationNeeded()
            showLocalMetadataWriteBackConfirmState.value = true
        } else {
            saveInAppOnly()
        }
    }

    private fun hasPendingLyricsChange(): Boolean = shouldClearLyrics || shouldRestoreLyrics

    fun saveEditedSongInfo(
        song: SongItem,
        viewModel: NowPlayingViewModel,
        writeLocalMetadata: Boolean,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        resources: android.content.res.Resources,
        onSaved: () -> Unit
    ) {
        if (!canStartEditAction()) return
        val startedAtMs = SystemClock.elapsedRealtime()
        setSaving(true)
        nextOriginalInfoRequest()
        launchSave {
            var dismissed = false
            try {
                val outcome = captureEditSongOperation {
                    performEditedSongSave(song, viewModel, writeLocalMetadata)
                }
                dismissed = publishEditedSongSaveOutcome(
                    outcome, snackbarHostState, resources, onSaved
                )
            } finally {
                finishSaveAttempt(startedAtMs, writeLocalMetadata, dismissed)
            }
        }
    }

    private suspend fun performEditedSongSave(
        song: SongItem,
        viewModel: NowPlayingViewModel,
        writeLocalMetadata: Boolean
    ): EditSongSaveResult {
        val lyricsPlan = resolveLyricsSavePlan(song, writeLocalMetadata)
        return executeEditSongSave(song, saveSteps(viewModel, lyricsPlan, writeLocalMetadata))
    }

    private suspend fun publishEditedSongSaveOutcome(
        outcome: Result<EditSongSaveResult>,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        resources: android.content.res.Resources,
        onSaved: () -> Unit
    ): Boolean {
        val failure = outcome.exceptionOrNull()
        if (failure != null) {
            reportEditSongSaveException(failure, snackbarHostState, resources)
            return false
        }
        return publishEditedSongSaveResult(outcome.getOrThrow(), snackbarHostState, resources, onSaved)
    }

    private suspend fun publishEditedSongSaveResult(
        result: EditSongSaveResult,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        resources: android.content.res.Resources,
        onSaved: () -> Unit
    ): Boolean {
        if (result != EditSongSaveResult.SAVED) {
            showSaveFailure(snackbarHostState, resources, result.failureMessage())
            return false
        }
        return completeSuccessfulEditSongSave(onSaved)
    }

    private fun completeSuccessfulEditSongSave(onSaved: () -> Unit): Boolean {
        if (disposed) return false
        resetAfterSave()
        setSaving(false)
        onSaved()
        return true
    }

    private suspend fun reportEditSongSaveException(
        error: Throwable,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        resources: android.content.res.Resources
    ) {
        NPLogger.e("NowPlayingSongEdit", "保存歌曲信息失败", error)
        snackbarHostState.showNeriSnackbar(
            message = resources.getString(R.string.toast_save_failed, error.message.orEmpty()),
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
    }

    private fun finishSaveAttempt(startedAtMs: Long, writeLocalMetadata: Boolean, dismissed: Boolean) {
        logSaveDuration(startedAtMs, writeLocalMetadata, dismissed)
        if (!dismissed) setSaving(false)
    }

    private fun saveSteps(
        viewModel: NowPlayingViewModel,
        plan: EditSongLyricsSavePlan,
        writeLocalMetadata: Boolean
    ): EditSongSaveSteps = object : EditSongSaveSteps {
        override fun latestSong(original: SongItem): SongItem = this@NowPlayingSongEditOwner.latestSong(original)

        override suspend fun writeLyrics(song: SongItem): Boolean = writeLyricsForSave(song, plan)

        override suspend fun writeMetadata(song: SongItem): Boolean =
            writeSongInfo(viewModel, song, plan, writeLocalMetadata)
    }

    private fun resolveLyricsSavePlan(song: SongItem, writeLocalMetadata: Boolean) =
        resolveEditSongLyricsSavePlan(
            pendingDraft = pendingLyricsDraft,
            shouldClearLyrics = shouldClearLyrics,
            shouldRestoreLyrics = shouldRestoreLyrics,
            originalLyric = originalLyric,
            originalTranslatedLyric = originalTranslatedLyric,
            originalRomanizedLyric = originalRomanizedLyric,
            writeLocalMetadata = writeLocalMetadata,
            isLocalSong = song.isLocalSong()
        )

    private suspend fun writeLyricsForSave(song: SongItem, plan: EditSongLyricsSavePlan): Boolean {
        val draft = plan.draft ?: return true
        return playbackPort.saveLyrics(SongEditLyricsWrite(
            song = song,
            lyric = restoredLyricsOrDraft(draft.lyric, originalLyric),
            translatedLyric = restoredLyricsOrDraft(draft.translatedLyric, originalTranslatedLyric),
            romanizedLyric = restoredLyricsOrDraft(draft.romanizedLyric, originalRomanizedLyric),
            writeLocalMetadata = plan.writeLyricsToLocalMetadata,
            persistLocalSidecars = plan.persistLocalSidecars
        ))
    }

    private fun restoredLyricsOrDraft(draft: String, original: String?): String? =
        restoredEditSongLyricsOrDraft(draft, original, pendingLyricsDraft != null, shouldRestoreLyrics)

    private suspend fun writeSongInfo(
        viewModel: NowPlayingViewModel,
        song: SongItem,
        plan: EditSongLyricsSavePlan,
        writeLocalMetadata: Boolean
    ): Boolean = viewModel.updateSongInfo(
        originalSong = song,
        newCoverUrl = editSongCoverForSave(coverUrl),
        newName = songName,
        newArtist = artistName,
        restoreBaseCover = shouldRestoreCoverBase,
        restoreBaseName = shouldRestoreTitleBase,
        restoreBaseArtist = shouldRestoreArtistBase,
        clearMatchedMetadata = shouldClearMatchedMetadata,
        writeLocalMetadata = writeLocalMetadata,
        writeLyrics = plan.writeLyricsWithSongMetadata,
        persistManualRemoteCover = shouldPersistManualCover(),
        restoreBaseLyrics = shouldRestoreLyrics
    )

    private fun shouldPersistManualCover(): Boolean =
        shouldPersistEditSongManualCover(coverWasManuallyChanged, shouldRestoreCoverBase)

    private fun resetAfterSave() {
        userHasEdited = false
        shouldRestoreCoverBase = false
        shouldRestoreTitleBase = false
        shouldRestoreArtistBase = false
        shouldClearMatchedMetadata = false
        shouldClearLyrics = false
        shouldRestoreLyrics = false
        originalLyric = null
        originalTranslatedLyric = null
        originalRomanizedLyric = null
        pendingLyricsDraft = null
    }

    private fun logSaveDuration(startedAtMs: Long, writeLocalMetadata: Boolean, succeeded: Boolean) {
        val timing = editSongSaveTiming(
            startedAtMs, SystemClock.elapsedRealtime(), writeLocalMetadata, succeeded
        )
        if (timing.overBudget) NPLogger.w("NowPlayingSongEdit", timing.message)
        else NPLogger.d("NowPlayingSongEdit", timing.message)
    }

    fun writeFetchedLyricsToLocalMetadata(
        song: SongItem,
        viewModel: NowPlayingViewModel,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        resources: android.content.res.Resources
    ) {
        if (!canStartEditAction()) return
        setSaving(true)
        launchSave {
            try {
                val outcome = captureEditSongOperation { persistFetchedLyricsMetadata(song, viewModel) }
                publishFetchedLyricsWriteBack(outcome, snackbarHostState, resources)
            } finally {
                setSaving(false)
            }
        }
    }

    private suspend fun persistFetchedLyricsMetadata(song: SongItem, viewModel: NowPlayingViewModel): Boolean {
        val latest = latestSong(song)
        val metadata = editSongMetadataSnapshot(latest)
        return viewModel.updateSongInfo(
            originalSong = latest,
            newCoverUrl = metadata.coverUrl,
            newName = metadata.name,
            newArtist = metadata.artist,
            writeLocalMetadata = true,
            writeLyrics = true,
            persistManualRemoteCover = shouldPersistManualCover()
        )
    }

    private suspend fun publishFetchedLyricsWriteBack(
        outcome: Result<Boolean>,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        resources: android.content.res.Resources
    ) {
        outcome.fold(
            onSuccess = { saved -> publishFetchedLyricsWriteBackSuccess(saved, snackbarHostState, resources) },
            onFailure = { error -> reportFetchedLyricsWriteBackFailure(error, snackbarHostState, resources) }
        )
    }

    internal suspend fun publishFetchedLyricsWriteBackSuccess(
        saved: Boolean,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        resources: android.content.res.Resources
    ) {
        if (!saved) showSaveFailure(
            snackbarHostState, resources, R.string.local_song_metadata_write_failed
        )
    }

    private suspend fun reportFetchedLyricsWriteBackFailure(
        error: Throwable,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        resources: android.content.res.Resources
    ) {
        NPLogger.e("NowPlayingSongEdit", "回写填充歌词失败", error)
        snackbarHostState.showNeriSnackbar(
            message = resources.getString(R.string.toast_save_failed, error.message.orEmpty()),
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
    }

    private suspend fun showSaveFailure(
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        resources: android.content.res.Resources,
        message: Int
    ) {
        snackbarHostState.showNeriSnackbar(
            message = resources.getString(message),
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
    }

    private fun latestSong(song: SongItem): SongItem =
        latestMatchingEditSong(playbackPort.currentSong(), song)

    fun importCover(
        context: android.content.Context,
        song: SongItem,
        sourceUri: Uri,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        resources: android.content.res.Resources
    ) {
        if (!canStartEditAction()) return
        nextOriginalInfoRequest()
        isOriginalInfoRestoring = false
        isCoverImporting = true
        launchCoverImport {
            try {
                val outcome = captureEditSongOperation {
                    CustomSongCoverStorage.importFromUri(context, song, sourceUri)
                }
                publishImportedCover(outcome, snackbarHostState, resources)
            } finally {
                isCoverImporting = false
            }
        }
    }

    private suspend fun publishImportedCover(
        outcome: Result<Uri?>,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        resources: android.content.res.Resources
    ) {
        outcome.fold(
            onSuccess = { imported -> publishImportedCoverUri(imported, snackbarHostState, resources) },
            onFailure = { error ->
                NPLogger.e("NowPlayingSongEdit", "导入本地封面失败", error)
                showSaveFailure(snackbarHostState, resources, R.string.music_cover_import_failed)
            }
        )
    }

    private suspend fun publishImportedCoverUri(
        imported: Uri?,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        resources: android.content.res.Resources
    ) {
        if (imported == null) {
            showSaveFailure(snackbarHostState, resources, R.string.music_cover_import_failed)
            return
        }
        applyImportedCoverUri(imported)
    }

    private fun applyImportedCoverUri(imported: Uri) {
        if (disposed) return
        coverUrl = imported.toString()
        coverWasManuallyChanged = true
        userHasEdited = true
        shouldRestoreCoverBase = false
    }

    suspend fun saveEditorLyrics(song: SongItem, draft: EditSongLyricsDraft): Boolean {
        val outcome = captureEditSongOperation { persistEditedLyrics(song, draft) }
        return publishEditorLyricsSaveOutcome(outcome, draft)
    }

    private suspend fun persistEditedLyrics(song: SongItem, draft: EditSongLyricsDraft): Boolean =
        playbackPort.saveLyrics(SongEditLyricsWrite(
            song = latestSong(song),
            lyric = draft.lyric,
            translatedLyric = draft.translatedLyric,
            romanizedLyric = draft.romanizedLyric,
            writeLocalMetadata = draft.writeLocalMetadata,
            persistLocalSidecars = shouldPersistEditedSongLyricsLocally(
                isLocalSong = song.isLocalSong(),
                writeLocalMetadata = draft.writeLocalMetadata
            )
        ))

    internal fun publishEditorLyricsSaveOutcome(outcome: Result<Boolean>, draft: EditSongLyricsDraft): Boolean {
        val saved = outcome.getOrElse { error ->
            NPLogger.e("NowPlayingSongEdit", "保存编辑歌词失败", error)
            false
        }
        if (saved) applySavedEditorLyricsIfActive(draft)
        return saved
    }

    private fun applySavedEditorLyricsIfActive(draft: EditSongLyricsDraft) {
        if (disposed) return
        applySavedEditorLyrics(draft)
    }

    private fun applySavedEditorLyrics(draft: EditSongLyricsDraft) {
        pendingLyricsDraft = null
        shouldClearLyrics = false
        shouldRestoreLyrics = false
        originalLyric = null
        originalTranslatedLyric = null
        originalRomanizedLyric = null
        userHasEdited = true
        lyricsEditorSeed = lyricsEditorSeed?.let { applyEditSongLyricsDraftPreview(it, draft) }
    }

    fun openLyricsEditor(
        context: android.content.Context,
        actualSong: SongItem,
        displayedLyrics: List<moe.ouom.neriplayer.ui.component.lyrics.LyricEntry>,
        displayedTranslatedLyrics: List<moe.ouom.neriplayer.ui.component.lyrics.LyricEntry>,
        displayedRomanizedLyrics: List<moe.ouom.neriplayer.ui.component.lyrics.LyricEntry>,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        composeResources: android.content.res.Resources
    ) {
        val displayed = DisplayedEditLyrics(
            original = displayedLyrics.toEditableLyricsText(),
            translated = displayedTranslatedLyrics.toEditableLyricsText(),
            romanized = displayedRomanizedLyrics.toEditableLyricsText()
        )
        val requestId = nextLyricsEditorRequest()
        lyricsEditorSeed = null
        pendingLyricsSourceSeed = null
        isLyricsEditorOpening = true
        isPendingEmbeddedLyricsLoading = false
        launchLyricsLoad {
            val outcome = captureEditSongOperation { loadLyricsEditorSeed(context, actualSong, displayed) }
            outcome.fold(
                onSuccess = { seed -> publishLyricsEditorSeed(
                    loadScope = this,
                    context = context,
                    song = actualSong,
                    seed = seed,
                    requestId = requestId,
                    snackbarHostState = snackbarHostState,
                    composeResources = composeResources
                ) },
                onFailure = { error ->
                    handleLyricsEditorLoadFailure(
                        error, actualSong, displayed, requestId, snackbarHostState, composeResources
                    )
                }
            )
        }
    }

    private suspend fun loadLyricsEditorSeed(
        context: android.content.Context,
        song: SongItem,
        displayed: DisplayedEditLyrics
    ): LyricsEditorSeed {
        if (!song.isLocalSong()) {
            return resolveLyricsEditorSeed(
                song = song,
                preparedLyrics = displayed.original,
                preparedTranslatedLyrics = displayed.translated
            )
        }
        val sources = readEditSongLyricsSources(context, song)
        return buildLocalEditSongLyricsEditorSeed(
            song = song,
            sources = sources,
            displayedLyrics = displayed.original,
            displayedTranslatedLyrics = displayed.translated,
            displayedRomanizedLyrics = displayed.romanized
        )
    }

    private suspend fun handleLyricsEditorLoadFailure(
        error: Throwable,
        song: SongItem,
        displayed: DisplayedEditLyrics,
        requestId: Int,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        composeResources: android.content.res.Resources
    ) {
        NPLogger.e("NowPlayingLyrics", editSongLyricsLoadErrorMessage(error), error)
        if (isEditSongLyricsPermissionFailure(error)) {
            handleLyricsPermissionLost(song, requestId, snackbarHostState, composeResources)
            return
        }
        publishFallbackLyricsEditorSeed(song, displayed, requestId)
    }

    private fun publishFallbackLyricsEditorSeed(song: SongItem, displayed: DisplayedEditLyrics, requestId: Int) {
        if (!isLyricsRequestCurrent(requestId)) return
        lyricsEditorSeed = previewLyricsSeed(fallbackLyricsEditorSeed(song, displayed))
        isLyricsEditorOpening = false
    }

    private fun previewLyricsSeed(seed: LyricsEditorSeed): LyricsEditorSeed =
        applyEditSongLyricsDraftPreview(
            seed = applyLyricsEditorRestorePreview(
                seed = seed,
                shouldClearLyrics = shouldClearLyrics,
                shouldRestoreLyrics = shouldRestoreLyrics,
                originalLyric = originalLyric,
                originalTranslatedLyric = originalTranslatedLyric,
                originalRomanizedLyric = originalRomanizedLyric
            ),
            draft = pendingLyricsDraft
        )

    private fun fallbackLyricsEditorSeed(song: SongItem, displayed: DisplayedEditLyrics): LyricsEditorSeed =
        if (song.isLocalSong()) {
            resolveLocalLyricsEditorSeed(
                song = song,
                sidecarLyrics = null,
                sidecarTranslatedLyrics = null,
                sidecarRomanizedLyrics = null,
                embeddedLyrics = displayed.original,
                embeddedTranslatedLyrics = displayed.translated,
                embeddedRomanizedLyrics = null,
                hasOriginalSidecar = false,
                hasTranslatedSidecar = false,
                hasRomanizedSidecar = false
            )
        } else {
            resolveLyricsEditorSeed(song = song)
        }

    private fun publishLyricsEditorSeed(
        loadScope: CoroutineScope,
        context: android.content.Context,
        song: SongItem,
        seed: LyricsEditorSeed,
        requestId: Int,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        composeResources: android.content.res.Resources
    ) {
        if (!isLyricsRequestCurrent(requestId)) return
        publishCurrentLyricsEditorSeed(loadScope, context, song, seed, requestId, snackbarHostState, composeResources)
    }

    private fun publishCurrentLyricsEditorSeed(
        loadScope: CoroutineScope,
        context: android.content.Context,
        song: SongItem,
        seed: LyricsEditorSeed,
        requestId: Int,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        composeResources: android.content.res.Resources
    ) {
        val preview = previewLyricsSeed(seed)
        if (shouldOpenEditLyricsSeedImmediately(previewsDiffer(preview, seed), song.isLocalSong(), seed.hasSidecar)) {
            lyricsEditorSeed = preview
            pendingLyricsSourceSeed = null
            isLyricsEditorOpening = false
            isPendingEmbeddedLyricsLoading = false
            return
        }
        pendingLyricsSourceSeed = seed
        isLyricsEditorOpening = false
        isPendingEmbeddedLyricsLoading = true
        loadScope.launch {
            completeEmbeddedLyricsSource(context, song, seed, requestId, snackbarHostState, composeResources)
        }
    }

    private fun previewsDiffer(preview: LyricsEditorSeed, seed: LyricsEditorSeed): Boolean = preview !== seed

    private suspend fun completeEmbeddedLyricsSource(
        context: android.content.Context,
        song: SongItem,
        seed: LyricsEditorSeed,
        requestId: Int,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        composeResources: android.content.res.Resources
    ) {
        val result = readEditSongEmbeddedLyrics(context, song)
        acceptEmbeddedLyricsResult(result, seed, requestId, snackbarHostState, composeResources)
    }

    internal suspend fun acceptEmbeddedLyricsResult(
        result: EditSongEmbeddedReadResult,
        seed: LyricsEditorSeed,
        requestId: Int,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        composeResources: android.content.res.Resources
    ) {
        if (!canPublishEmbeddedLyrics(requestId)) return
        when (result) {
            EditSongEmbeddedReadResult.PermissionLost -> {
                pendingLyricsSourceSeed = null
                isPendingEmbeddedLyricsLoading = false
                showLyricsPermissionLost(snackbarHostState, composeResources)
            }
            is EditSongEmbeddedReadResult.Loaded -> publishEmbeddedLyrics(seed, result.lyrics)
        }
    }

    private fun publishEmbeddedLyrics(
        seed: LyricsEditorSeed,
        embedded: moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata?
    ) {
        val embeddedSeed = seedWithEmbeddedEditLyrics(seed, embedded)
        if (embeddedSeed != null) publishEmbeddedSourceChoice(embeddedSeed)
        else publishSidecarWithoutEmbeddedLyrics(seed)
        isPendingEmbeddedLyricsLoading = false
    }

    private fun publishEmbeddedSourceChoice(seed: LyricsEditorSeed) {
        pendingLyricsSourceSeed = seed
    }

    private fun publishSidecarWithoutEmbeddedLyrics(seed: LyricsEditorSeed) {
        lyricsEditorSeed = seed
        pendingLyricsSourceSeed = null
    }

    fun dismissLyricsSourceChoice() {
        pendingLyricsSourceSeed = null
        isPendingEmbeddedLyricsLoading = false
    }

    fun chooseSidecarLyrics() {
        if (savingInProgress || disposed) return
        val seed = pendingLyricsSourceSeed ?: return
        lyricsEditorSeed = seed.copy(
            lyrics = seed.sidecarLyrics,
            translatedLyrics = seed.sidecarTranslatedLyrics,
            romanizedLyrics = seed.sidecarRomanizedLyrics,
            source = moe.ouom.neriplayer.ui.component.lyrics.LyricsEditorSource.SIDECAR
        )
        dismissLyricsSourceChoice()
    }

    fun chooseEmbeddedLyrics() {
        if (savingInProgress || disposed || isPendingEmbeddedLyricsLoading) return
        val seed = pendingLyricsSourceSeed ?: return
        lyricsEditorSeed = seed.copy(
            lyrics = seed.embeddedLyrics,
            translatedLyrics = seed.embeddedTranslatedLyrics,
            romanizedLyrics = seed.embeddedRomanizedLyrics,
            source = moe.ouom.neriplayer.ui.component.lyrics.LyricsEditorSource.EMBEDDED
        )
        dismissLyricsSourceChoice()
    }

    private fun canPublishEmbeddedLyrics(requestId: Int): Boolean =
        shouldPublishPendingEmbeddedLyricsResult(
            requestIsCurrent = isLyricsRequestCurrent(requestId),
            pendingSourceHasSidecar = pendingLyricsSourceSeed?.hasSidecar == true,
            editorAlreadySelected = lyricsEditorSeed != null
        )

    private fun isLyricsRequestCurrent(requestId: Int): Boolean =
        !disposed && lyricsEditorRequestId == requestId

    private suspend fun handleLyricsPermissionLost(
        song: SongItem,
        requestId: Int,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        composeResources: android.content.res.Resources
    ) {
        if (!shouldHandleEditLyricsPermissionLoss(song.isLocalSong(), isLyricsRequestCurrent(requestId))) return
        lyricsEditorSeed = null
        pendingLyricsSourceSeed = null
        isPendingEmbeddedLyricsLoading = false
        isLyricsEditorOpening = false
        showLyricsPermissionLost(snackbarHostState, composeResources)
    }

    private suspend fun showLyricsPermissionLost(
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        composeResources: android.content.res.Resources
    ) {
        snackbarHostState.showNeriSnackbar(
            message = composeResources.getString(R.string.settings_download_directory_permission_lost),
            withDismissAction = true,
            duration = SnackbarDuration.Long
        )
    }

    private data class DisplayedEditLyrics(
        val original: String,
        val translated: String,
        val romanized: String
    )

    fun dispose() {
        disposed = true
        originalInfoRequestIdState.intValue++
        lyricsEditorRequestIdState.intValue++
        restoreJob?.cancel()
        lyricsLoadJob?.cancel()
        coverImportJob?.cancel()
        saveJob?.cancel()
        onSavingChanged(false)
    }
}
