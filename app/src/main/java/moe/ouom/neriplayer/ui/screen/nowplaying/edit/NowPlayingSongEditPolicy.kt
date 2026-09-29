package moe.ouom.neriplayer.ui.screen.nowplaying.edit

import android.content.Context
import android.net.Uri
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.model.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootUnavailableException
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.local.media.CustomSongCoverStorage
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.isSyncableRemoteSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayArtist
import moe.ouom.neriplayer.data.model.displayCoverUrl
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.core.lyrics.LyricsEditorSeed
import moe.ouom.neriplayer.core.lyrics.LyricsEditorSource
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel

internal fun shouldFetchOriginalSongInfo(song: SongItem): Boolean {
    if (song.isLocalSong()) return false
    return song.isBiliEditSource() || song.isNeteaseEditSource()
}

private fun SongItem.isBiliEditSource() =
    album.startsWith(PlayerManager.BILI_SOURCE_TAG, ignoreCase = true) ||
        channelId.equals("bilibili", ignoreCase = true)

private fun SongItem.isNeteaseEditSource() =
    album.startsWith(PlayerManager.NETEASE_SOURCE_TAG, ignoreCase = true) ||
        channelId.equals("netease", ignoreCase = true) ||
        mediaUri?.contains("music.163.com", ignoreCase = true) == true

internal fun isOriginalInfoRequestCurrent(
    requestId: Int,
    currentRequestId: Int,
    requestSongKey: String,
    currentSongKey: String
): Boolean {
    return requestId == currentRequestId && requestSongKey == currentSongKey
}

internal fun latestMatchingEditSong(current: SongItem?, original: SongItem): SongItem =
    if (current?.sameIdentityAs(original) == true) current else original


internal fun resolveEditSongInitialCoverUrl(
    song: SongItem,
    resolvedDisplayCoverUrl: String?
): String = selectEditSongInitialCover(
    directCover = song.displayCoverUrl(),
    downloadedCover = AudioDownloadManager.peekLocalCoverUri(song),
    originalCover = song.originalCoverUrl,
    resolvedDisplayCoverUrl = resolvedDisplayCoverUrl
)

internal fun selectEditSongInitialCover(
    directCover: String?,
    downloadedCover: String?,
    originalCover: String?,
    resolvedDisplayCoverUrl: String?
): String {
    val direct = usableEditCover(directCover)
    val downloaded = usableDownloadedEditCover(downloadedCover)
    return listOfNotNull(
        usableLocalEditCover(direct),
        downloaded,
        direct,
        usableEditCover(originalCover),
        resolvedDisplayCoverUrl?.takeIf(String::isNotBlank)
    ).firstOrNull().orEmpty()
}

private fun usableLocalEditCover(reference: String?): String? =
    usableEditCover(reference)?.takeUnless(CustomSongCoverStorage::isRemoteReference)

private fun usableDownloadedEditCover(reference: String?): String? = reference
    ?.takeIf(String::isNotBlank)
    ?.takeUnless(CustomSongCoverStorage::isRemoteReference)

internal fun refreshedEditSongBaselineCover(current: String, initial: String): String? {
    if (current.isNotBlank() && !CustomSongCoverStorage.isRemoteReference(current)) return null
    return initial.takeIf(String::isNotBlank)
}

internal fun shouldOfferFilledLyricsWriteBack(
    succeeded: Boolean,
    saving: Boolean,
    localSong: Boolean
): Boolean = succeeded && !saving && localSong

internal fun keepFilledLyricsWriteBackPrompt(
    alreadyVisible: Boolean,
    succeeded: Boolean,
    saving: Boolean,
    localSong: Boolean
): Boolean = alreadyVisible || shouldOfferFilledLyricsWriteBack(succeeded, saving, localSong)

internal fun restoredEditSongLyricsOrDraft(
    draft: String,
    original: String?,
    hasPendingDraft: Boolean,
    restoring: Boolean
): String? = if (!hasPendingDraft && restoring) original else draft

internal fun shouldPersistEditSongManualCover(manuallyChanged: Boolean, restoringBase: Boolean): Boolean =
    manuallyChanged && !restoringBase

internal fun editSongCoverForSave(coverUrl: String): String? = coverUrl.takeIf(String::isNotBlank)

internal data class EditSongMetadataSnapshot(val coverUrl: String?, val name: String, val artist: String)

internal fun editSongMetadataSnapshot(song: SongItem): EditSongMetadataSnapshot = EditSongMetadataSnapshot(
    coverUrl = song.customCoverUrl ?: song.coverUrl,
    name = song.customName ?: song.name,
    artist = song.customArtist ?: song.artist
)

internal data class EditSongSaveTiming(val elapsedMs: Long, val overBudget: Boolean, val message: String)

internal fun editSongSaveTiming(
    startedAtMs: Long,
    finishedAtMs: Long,
    writeLocalMetadata: Boolean,
    succeeded: Boolean
): EditSongSaveTiming {
    val elapsedMs = finishedAtMs - startedAtMs
    val budgetMs = if (writeLocalMetadata) 2_000L else 1_000L
    val overBudget = elapsedMs >= budgetMs
    return EditSongSaveTiming(
        elapsedMs,
        overBudget,
        "edit song save finished: localWrite=$writeLocalMetadata, " +
            "success=$succeeded, elapsedMs=$elapsedMs, overBudget=$overBudget"
    )
}

internal fun shouldOpenEditLyricsSeedImmediately(
    previewChanged: Boolean,
    localSong: Boolean,
    hasSidecar: Boolean
): Boolean = previewChanged || !localSong || !hasSidecar

internal fun shouldHandleEditLyricsPermissionLoss(localSong: Boolean, currentRequest: Boolean): Boolean =
    localSong && currentRequest

internal fun seedWithEmbeddedEditLyrics(seed: LyricsEditorSeed, embedded: LocalLyricsScanMetadata?): LyricsEditorSeed? {
    if (embedded?.hasEmbeddedLyricText() != true) return null
    return seed.copy(
        embeddedLyrics = embedded.embeddedLyric.orEmpty(),
        embeddedTranslatedLyrics = embedded.embeddedTranslatedLyric.orEmpty(),
        embeddedRomanizedLyrics = embedded.embeddedRomanizedLyric.orEmpty(),
        hasEmbeddedLyrics = true
    )
}

internal fun isEditSongLyricsPermissionFailure(error: Throwable): Boolean =
    error is ManagedDownloadRootUnavailableException || error is SecurityException

internal fun editSongLyricsLoadErrorMessage(error: Throwable): String = when (error) {
    is ManagedDownloadRootUnavailableException -> "歌词编辑器配置的下载目录授权已失效"
    is SecurityException -> "歌词编辑器读取本地文件权限失效"
    else -> "歌词编辑器初始化失败"
}

private fun usableEditCover(reference: String?): String? = reference
    ?.takeIf(String::isNotBlank)
    ?.takeUnless(CustomSongCoverStorage::isDirectoryReference)

internal fun resolveEditSongRestoredCoverUrl(
    sourceCoverUrl: String?,
    baselineCoverUrl: String
): String {
    val source = usableEditCover(sourceCoverUrl?.trim())
    val baseline = baselineCoverUrl.trim()
        .takeUnless(CustomSongCoverStorage::isDirectoryReference)
    return source ?: baseline.orEmpty()
}

internal fun shouldApplyResolvedEditSongCover(
    userHasEdited: Boolean,
    currentCoverUrl: String,
    resolvedDisplayCoverUrl: String?
): Boolean {
    if (userHasEdited || currentCoverUrl.isNotBlank()) return false
    return !resolvedDisplayCoverUrl.isNullOrBlank()
}

internal data class EditSongBaseline(
    val title: String,
    val artist: String,
    val coverUrl: String,
    val lyric: String?,
    val translatedLyric: String?,
    val romanizedLyric: String?
)

internal data class EditSongRestoreSelection(
    val cover: Boolean,
    val title: Boolean,
    val artist: Boolean,
    val lyrics: Boolean
) {
    val all: Boolean get() = listOf(cover, title, artist, lyrics).all { it }
}

internal fun resolveEditSongBaselineFromSong(
    song: SongItem,
    resolvedDisplayCoverUrl: String?,
    displayedLyric: String?,
    displayedTranslatedLyric: String?,
    displayedRomanizedLyric: String?
): EditSongBaseline {
    return EditSongBaseline(
        title = song.originalName ?: song.name,
        artist = song.originalArtist ?: song.artist,
        coverUrl = resolveEditSongBaselineCover(
            song.originalCoverUrl, song.coverUrl, resolvedDisplayCoverUrl
        ),
        lyric = song.originalLyric ?: displayedLyric,
        translatedLyric = song.originalTranslatedLyric ?: displayedTranslatedLyric,
        romanizedLyric = song.originalRomanizedLyric ?: displayedRomanizedLyric
    )
}

private fun resolveEditSongBaselineCover(
    originalCover: String?, songCover: String?, resolvedCover: String?
): String = listOfNotNull(
    usableEditCover(originalCover), usableEditCover(songCover), resolvedCover
).firstOrNull().orEmpty()

internal fun resolveManagedEditSongBaseline(
    current: EditSongBaseline,
    metadata: ManagedDownloadRestorableMetadata?,
    coverReference: String?,
    sidecarLyrics: ManagedDownloadStorage.DownloadedLyricsBundle? = null
): EditSongBaseline {
    val baseline = metadata?.baseline
    return current.copy(
        title = managedEditSongTitle(baseline, current.title),
        artist = managedEditSongArtist(baseline, current.artist),
        coverUrl = managedEditSongCover(coverReference, baseline, current.coverUrl),
        lyric = managedOriginalLyric(baseline, sidecarLyrics, current.lyric),
        translatedLyric = managedTranslatedLyric(baseline, sidecarLyrics, current.translatedLyric),
        romanizedLyric = managedRomanizedLyric(baseline, sidecarLyrics, current.romanizedLyric)
    )
}

private fun managedEditSongTitle(
    baseline: ManagedDownloadRestorableMetadata.Baseline?,
    current: String
): String = baseline?.title ?: current

private fun managedEditSongArtist(
    baseline: ManagedDownloadRestorableMetadata.Baseline?,
    current: String
): String = baseline?.artist ?: current

private fun managedEditSongCover(
    resolved: String?,
    baseline: ManagedDownloadRestorableMetadata.Baseline?,
    current: String
): String = resolved ?: baseline?.coverReference ?: current

private fun managedOriginalLyric(
    baseline: ManagedDownloadRestorableMetadata.Baseline?,
    sidecar: ManagedDownloadStorage.DownloadedLyricsBundle?,
    current: String?
): String? = restoredManagedLyric(
    baseline?.originalLyric, current, sidecar?.lyric, sidecar?.hasOriginalSidecar == true
)

private fun managedTranslatedLyric(
    baseline: ManagedDownloadRestorableMetadata.Baseline?,
    sidecar: ManagedDownloadStorage.DownloadedLyricsBundle?,
    current: String?
): String? = restoredManagedLyric(
    baseline?.translatedLyric, current, sidecar?.translatedLyric,
    sidecar?.hasTranslatedSidecar == true
)

private fun managedRomanizedLyric(
    baseline: ManagedDownloadRestorableMetadata.Baseline?,
    sidecar: ManagedDownloadStorage.DownloadedLyricsBundle?,
    current: String?
): String? = restoredManagedLyric(
    baseline?.romanizedLyric, current, sidecar?.romanizedLyric,
    sidecar?.hasRomanizedSidecar == true
)

private fun restoredManagedLyric(
    baseline: String?, current: String?, sidecar: String?, sidecarPresent: Boolean
): String? = baseline ?: sidecar.takeIf { sidecarPresent } ?: current

internal data class EditSongLyricsRestorePlan(
    val shouldClearLyrics: Boolean,
    val shouldRestoreLyrics: Boolean,
    val originalLyric: String,
    val originalTranslatedLyric: String,
    val originalRomanizedLyric: String
)

data class EditSongLyricsDraft(
    val lyric: String,
    val translatedLyric: String,
    val romanizedLyric: String,
    val writeLocalMetadata: Boolean
)

internal data class EditSongLyricsSavePlan(
    val draft: EditSongLyricsDraft?,
    val writeLyricsWithSongMetadata: Boolean,
    val writeLyricsToLocalMetadata: Boolean,
    val persistLocalSidecars: Boolean
)

internal fun resolveEditSongLyricsSavePlan(
    pendingDraft: EditSongLyricsDraft?,
    shouldClearLyrics: Boolean,
    shouldRestoreLyrics: Boolean,
    originalLyric: String?,
    originalTranslatedLyric: String?,
    originalRomanizedLyric: String?,
    writeLocalMetadata: Boolean,
    isLocalSong: Boolean
): EditSongLyricsSavePlan {
    val draft = resolveEditSongLyricsForSave(
        draft = pendingDraft,
        shouldClearLyrics = shouldClearLyrics,
        shouldRestoreLyrics = shouldRestoreLyrics,
        originalLyric = originalLyric,
        originalTranslatedLyric = originalTranslatedLyric,
        originalRomanizedLyric = originalRomanizedLyric
    )
    val writeWithLyrics = shouldWriteDraftToLocalMetadata(pendingDraft, writeLocalMetadata)
    val persistSidecars = shouldPersistEditedSongLyricsLocally(
        isLocalSong = isLocalSong,
        writeLocalMetadata = if (pendingDraft != null) writeWithLyrics else writeLocalMetadata
    )
    return EditSongLyricsSavePlan(
        draft = draft,
        writeLyricsWithSongMetadata = shouldWriteRestoredLyricsWithMetadata(
            writeLocalMetadata, draft, pendingDraft
        ),
        writeLyricsToLocalMetadata = writeWithLyrics,
        persistLocalSidecars = persistSidecars
    )
}

private fun shouldWriteDraftToLocalMetadata(
    draft: EditSongLyricsDraft?, writeLocalMetadata: Boolean
): Boolean = draft != null && (writeLocalMetadata || draft.writeLocalMetadata)

private fun shouldWriteRestoredLyricsWithMetadata(
    writeLocalMetadata: Boolean,
    draft: EditSongLyricsDraft?,
    pendingDraft: EditSongLyricsDraft?
): Boolean = writeLocalMetadata && draft != null && pendingDraft == null

internal fun resolveEditSongLyricsForSave(
    draft: EditSongLyricsDraft?,
    shouldClearLyrics: Boolean,
    shouldRestoreLyrics: Boolean,
    originalLyric: String?,
    originalTranslatedLyric: String?,
    originalRomanizedLyric: String?
): EditSongLyricsDraft? {
    draft?.let { return it }
    if (shouldClearLyrics) {
        return EditSongLyricsDraft(
            lyric = "",
            translatedLyric = "",
            romanizedLyric = "",
            writeLocalMetadata = false
        )
    }
    if (!shouldRestoreLyrics) return null
    return EditSongLyricsDraft(
        lyric = originalLyric.orEmpty(),
        translatedLyric = originalTranslatedLyric.orEmpty(),
        romanizedLyric = originalRomanizedLyric.orEmpty(),
        writeLocalMetadata = false
    )
}

internal fun applyLyricsEditorRestorePreview(
    seed: LyricsEditorSeed,
    shouldClearLyrics: Boolean,
    shouldRestoreLyrics: Boolean,
    originalLyric: String?,
    originalTranslatedLyric: String?,
    originalRomanizedLyric: String?
): LyricsEditorSeed {
    if (!shouldClearLyrics && !shouldRestoreLyrics) {
        return seed
    }
    val lyric = restoredPreviewText(shouldClearLyrics, originalLyric)
    val translatedLyric = restoredPreviewText(shouldClearLyrics, originalTranslatedLyric)
    val romanizedLyric = restoredPreviewText(shouldClearLyrics, originalRomanizedLyric)
    return seed.copy(
        lyrics = lyric,
        translatedLyrics = translatedLyric,
        romanizedLyrics = romanizedLyric,
        sidecarLyrics = lyric,
        sidecarTranslatedLyrics = translatedLyric,
        sidecarRomanizedLyrics = romanizedLyric,
        embeddedLyrics = lyric,
        embeddedTranslatedLyrics = translatedLyric,
        embeddedRomanizedLyrics = romanizedLyric,
        hasSidecar = false,
        hasEmbeddedLyrics = false,
        source = LyricsEditorSource.SIDECAR
    )
}

private fun restoredPreviewText(clear: Boolean, original: String?): String =
    if (clear) "" else original.orEmpty()

internal fun applyEditSongLyricsDraftPreview(
    seed: LyricsEditorSeed,
    draft: EditSongLyricsDraft?
): LyricsEditorSeed {
    if (draft == null) return seed
    return seed.copy(
        lyrics = draft.lyric,
        translatedLyrics = draft.translatedLyric,
        romanizedLyrics = draft.romanizedLyric,
        sidecarLyrics = draft.lyric,
        sidecarTranslatedLyrics = draft.translatedLyric,
        sidecarRomanizedLyrics = draft.romanizedLyric,
        embeddedLyrics = draft.lyric,
        embeddedTranslatedLyrics = draft.translatedLyric,
        embeddedRomanizedLyrics = draft.romanizedLyric,
        hasSidecar = false,
        hasEmbeddedLyrics = false,
        source = LyricsEditorSource.SIDECAR
    )
}

internal fun resolveEditSongLyricsRestorePlan(
    baseline: EditSongBaseline,
    sourceInfo: NowPlayingViewModel.OriginalSongInfo?
): EditSongLyricsRestorePlan {
    if (sourceInfo?.shouldClearLyrics == true) {
        return emptyEditSongRestorePlan(clear = true)
    }
    if (shouldLeaveEditSongLyricsUnchanged(baseline, sourceInfo)) {
        return emptyEditSongRestorePlan(clear = false)
    }
    return restoredEditSongLyricsPlan(baseline, sourceInfo)
}

private fun emptyEditSongRestorePlan(clear: Boolean) = EditSongLyricsRestorePlan(
    shouldClearLyrics = clear,
    shouldRestoreLyrics = false,
    originalLyric = "",
    originalTranslatedLyric = "",
    originalRomanizedLyric = ""
)

private fun shouldLeaveEditSongLyricsUnchanged(
    baseline: EditSongBaseline,
    sourceInfo: NowPlayingViewModel.OriginalSongInfo?
): Boolean = sourceInfo != null && !sourceInfo.hasLyrics() && !baseline.hasLyrics()

private fun restoredEditSongLyricsPlan(
    baseline: EditSongBaseline,
    sourceInfo: NowPlayingViewModel.OriginalSongInfo?
): EditSongLyricsRestorePlan {
    return EditSongLyricsRestorePlan(
        shouldClearLyrics = false,
        shouldRestoreLyrics = sourceInfo?.hasLyrics() == true || baseline.hasLyrics(),
        originalLyric = preferredRestoreLyric(sourceInfo?.lyric, baseline.lyric),
        originalTranslatedLyric = preferredRestoreLyric(
            sourceInfo?.translatedLyric, baseline.translatedLyric
        ),
        originalRomanizedLyric = preferredRestoreLyric(
            sourceInfo?.romanizedLyric, baseline.romanizedLyric
        )
    )
}

private fun EditSongBaseline.hasLyrics(): Boolean =
    listOf(lyric, translatedLyric, romanizedLyric).any { it != null }

private fun NowPlayingViewModel.OriginalSongInfo.hasLyrics(): Boolean =
    listOf(lyric, translatedLyric, romanizedLyric).any { !it.isNullOrBlank() }

private fun preferredRestoreLyric(source: String?, baseline: String?): String =
    source?.takeIf(String::isNotBlank) ?: baseline.orEmpty()

internal fun shouldConfirmLocalMetadataWriteBack(
    song: SongItem,
    title: String,
    artist: String,
    coverUrl: String,
    hasPendingLyricsChange: Boolean = false
): Boolean {
    if (!song.isLocalSong()) return false
    if (hasPendingLyricsChange) return true
    return editSongTitleChanged(song, title) ||
        editSongArtistChanged(song, artist) ||
        editSongCoverChanged(song, coverUrl)
}

private fun editSongTitleChanged(song: SongItem, title: String): Boolean =
    song.displayName().trim() != title.trim().ifBlank { song.name }

private fun editSongArtistChanged(song: SongItem, artist: String): Boolean =
    song.displayArtist().trim() != artist.trim().ifBlank { song.artist }

private fun editSongCoverChanged(song: SongItem, coverUrl: String): Boolean =
    (song.customCoverUrl ?: song.coverUrl)?.trim() != coverUrl.trim().ifBlank { null }

internal fun shouldAllowLocalCoverReplacement(
    song: SongItem,
    context: Context? = null
): Boolean {
    return !song.isSyncableRemoteSong(context)
}

internal fun shouldPublishPendingEmbeddedLyricsResult(
    requestIsCurrent: Boolean,
    pendingSourceHasSidecar: Boolean,
    editorAlreadySelected: Boolean
): Boolean {
    return requestIsCurrent && pendingSourceHasSidecar && !editorAlreadySelected
}

internal fun shouldPersistEditedSongLyricsLocally(
    isLocalSong: Boolean,
    writeLocalMetadata: Boolean
): Boolean {
    return isLocalSong && !writeLocalMetadata
}

internal fun resolvePendingLocalCoverReplacementTarget(
    pendingSong: SongItem?,
    currentSong: SongItem?,
    context: Context? = null
): SongItem? {
    if (pendingSong == null || currentSong == null) return null
    if (!pendingSong.sameIdentityAs(currentSong)) return null
    if (!shouldAllowLocalCoverReplacement(pendingSong, context)) return null
    if (!shouldAllowLocalCoverReplacement(currentSong, context)) return null
    return pendingSong
}

internal fun withVerifiedEditCoverPickerTarget(
    sourceUri: Uri?,
    pendingSong: SongItem?,
    currentSong: SongItem?,
    context: Context?,
    onVerified: (SongItem, Uri) -> Unit
) {
    val uri = sourceUri ?: return
    val target = resolvePendingLocalCoverReplacementTarget(pendingSong, currentSong, context)
        ?: return
    onVerified(target, uri)
}
