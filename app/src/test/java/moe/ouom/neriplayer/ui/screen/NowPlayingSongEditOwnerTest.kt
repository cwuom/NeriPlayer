package moe.ouom.neriplayer.ui.screen

import android.content.res.Resources
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.component.lyrics.LyricsEditorSeed
import moe.ouom.neriplayer.ui.component.lyrics.LyricsEditorSource
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongBaseline
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongEmbeddedReadResult
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongLyricsDraft
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongRestoreSelection
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.NowPlayingSongEditOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.NowPlayingSongEditPlaybackPort
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.SongEditLyricsWrite
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.latestMatchingEditSong
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.resolveEditSongLyricsForSave
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.resolveEditSongLyricsSavePlan
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.withVerifiedEditCoverPickerTarget
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class NowPlayingSongEditOwnerTest {
    private val song = SongItem(8L, "Current", "Current artist", "Album", 2L, 60_000L, null)
    private val baseline =
        EditSongBaseline("Baseline", "Baseline artist", "base-cover", "base lyric", null, null)

    @Test
    fun `successful metadata writeback has no failure snackbar`() = runTest {
        owner().publishFetchedLyricsWriteBackSuccess(
            saved = true,
            snackbarHostState = SnackbarHostState(),
            resources = Mockito.mock(Resources::class.java)
        )
    }

    @Test
    fun `editor save publishes draft only on success and only while session is active`() {
        val owner = owner()
        val draft = EditSongLyricsDraft("edited", "translated", "romanized", false)
        owner.pendingLyricsDraftState.value = draft
        assertFalse(owner.publishEditorLyricsSaveOutcome(Result.success(false), draft))
        assertEquals(draft, owner.pendingLyricsDraftState.value)
        assertTrue(owner.publishEditorLyricsSaveOutcome(Result.success(true), draft))
        assertNull(owner.pendingLyricsDraftState.value)
        assertTrue(owner.userHasEditedState.value)

        val disposed = owner()
        disposed.pendingLyricsDraftState.value = draft
        disposed.dispose()
        assertTrue(disposed.publishEditorLyricsSaveOutcome(Result.success(true), draft))
        assertEquals(draft, disposed.pendingLyricsDraftState.value)
    }

    @Test
    fun `editor save writes through playback port with matching current song`() = runTest {
        val port = RecordingPlaybackPort()
        val refreshed = song.copy(customName = "Updated title")
        port.current = refreshed
        val owner = owner(port)
        val draft = EditSongLyricsDraft("edited", "translated", "romanized", false)

        assertTrue(owner.saveEditorLyrics(song, draft))
        val write = port.writes.single()
        assertEquals(refreshed, write.song)
        assertEquals("edited", write.lyric)
        assertEquals("translated", write.translatedLyric)
        assertEquals("romanized", write.romanizedLyric)
        assertFalse(write.writeLocalMetadata)

        port.current = song.copy(id = 999L)
        assertTrue(owner.saveEditorLyrics(song, draft))
        assertEquals(song, port.writes.last().song)
    }

    @Test
    fun `fill choice ignores missing selection and save lock then applies fields and lyric callback`() {
        val owner = owner()
        val candidate = SongSearchInfo(
            id = "candidate", songName = "Filled", singer = "Artist", duration = "1:00",
            source = MusicPlatform.QQ_MUSIC, albumName = null, coverUrl = null
        )
        var lyricSelections = 0
        val onLyrics: (SongSearchInfo) -> Unit = {
            assertEquals(candidate, it)
            lyricSelections++
        }
        assertFalse(owner.applySelectedFillOptions(false, true, false, true, onLyrics))
        owner.selectedSongForFillState.value = candidate
        owner.setSaving(true)
        assertFalse(owner.applySelectedFillOptions(false, true, false, true, onLyrics))
        owner.setSaving(false)
        assertTrue(owner.applySelectedFillOptions(false, true, false, false, onLyrics))
        assertEquals("Filled", owner.songNameState.value)
        assertEquals(0, lyricSelections)
        owner.selectedSongForFillState.value = candidate
        assertTrue(owner.applySelectedFillOptions(false, false, false, true, onLyrics))
        assertEquals(1, lyricSelections)
    }

    @Test
    fun `restore publishes only its current request and keeps unselected fields`() = runTest {
        val owner = owner()
        owner.songNameState.value = "Manual title"
        owner.artistNameState.value = "Manual artist"
        val staleRequest = owner.nextOriginalInfoRequest()
        val currentRequest = owner.nextOriginalInfoRequest()
        val choice =
            EditSongRestoreSelection(cover = false, title = true, artist = false, lyrics = false)
        val source = NowPlayingViewModel.OriginalSongInfo("Fetched title", "Fetched artist", "fetched-cover")

        assertFalse(owner.applyRestoredOriginalInfo(staleRequest, choice, baseline, source))
        assertEquals("Manual title", owner.songNameState.value)
        assertTrue(owner.applyRestoredOriginalInfo(currentRequest, choice, baseline, source))
        assertEquals("Fetched title", owner.songNameState.value)
        assertEquals("Manual artist", owner.artistNameState.value)
        assertFalse(owner.shouldRestoreCoverBaseState.value)
        assertTrue(owner.shouldRestoreTitleBaseState.value)
        assertFalse(owner.shouldRestoreArtistBaseState.value)
        assertFalse(owner.shouldClearMatchedMetadataState.value)
        assertTrue(owner.userHasEditedState.value)
    }

    @Test
    fun `restore all replaces pending lyric draft and dispose rejects late callback`() = runTest {
        val owner = owner()
        owner.pendingLyricsDraftState.value = EditSongLyricsDraft("draft", "", "", false)
        val request = owner.nextOriginalInfoRequest()
        val choice =
            EditSongRestoreSelection(cover = true, title = true, artist = true, lyrics = true)
        val source = NowPlayingViewModel.OriginalSongInfo(
            name = "Original", artist = "Original artist", coverUrl = "original-cover",
            lyric = "original lyric"
        )

        assertTrue(owner.applyRestoredOriginalInfo(request, choice, baseline, source))
        assertEquals("original-cover", owner.coverUrlState.value)
        assertEquals("Original", owner.songNameState.value)
        assertNull(owner.pendingLyricsDraftState.value)
        assertTrue(owner.shouldRestoreLyricsState.value)
        assertEquals("original lyric", owner.originalLyricState.value)
        assertTrue(owner.shouldClearMatchedMetadataState.value)

        owner.dispose()
        assertFalse(owner.applyRestoredOriginalInfo(request, choice, baseline, null))
        assertEquals("Original", owner.songNameState.value)
    }

    @Test
    fun `restore title uses baseline when source is unavailable and leaves unselected artist`() {
        val owner = owner()
        owner.updateTitle("Manual title")
        owner.updateArtist("Manual artist")
        val request = owner.nextOriginalInfoRequest()
        assertTrue(
            owner.applyRestoredOriginalInfo(
                request,
                EditSongRestoreSelection(
                    cover = false,
                    title = true,
                    artist = false,
                    lyrics = false
                ),
                baseline,
                null
            )
        )
        assertEquals("Baseline", owner.songNameState.value)
        assertEquals("Manual artist", owner.artistNameState.value)
    }

    @Test
    fun `save plan keeps editor draft writeback separate from metadata restore`() {
        val draft = EditSongLyricsDraft("draft", "translation", "romanized", true)
        val editor = plan(pendingDraft = draft, writeLocalMetadata = false)
        assertEquals(draft, editor.draft)
        assertTrue(editor.writeLyricsToLocalMetadata)
        assertFalse(editor.writeLyricsWithSongMetadata)
        assertFalse(editor.persistLocalSidecars)

        val editorWinsRestore = plan(
            pendingDraft = draft,
            shouldClearLyrics = true,
            shouldRestoreLyrics = true
        )
        assertEquals(draft, editorWinsRestore.draft)

        val restore = plan(shouldRestoreLyrics = true, writeLocalMetadata = true)
        assertEquals("base lyric", restore.draft?.lyric)
        assertFalse(restore.writeLyricsToLocalMetadata)
        assertTrue(restore.writeLyricsWithSongMetadata)
        assertFalse(restore.persistLocalSidecars)

        val clear = plan(shouldClearLyrics = true, writeLocalMetadata = false)
        assertEquals("", clear.draft?.lyric)
        assertTrue(clear.persistLocalSidecars)
        assertFalse(clear.writeLyricsWithSongMetadata)

        val unchanged = plan()
        assertNull(unchanged.draft)
        assertFalse(unchanged.writeLyricsWithSongMetadata)

        val missingOriginal = resolveEditSongLyricsForSave(
            draft = null,
            shouldClearLyrics = false,
            shouldRestoreLyrics = true,
            originalLyric = null,
            originalTranslatedLyric = null,
            originalRomanizedLyric = null
        )
        assertEquals("", missingOriginal?.lyric)
        assertEquals("", missingOriginal?.translatedLyric)
        assertEquals("", missingOriginal?.romanizedLyric)
    }

    @Test
    fun `saving locks manual fields and automatic fill until save completes`() {
        val owner = owner()
        val candidate = SongSearchInfo(
            id = "candidate", songName = "Filled title", singer = "Filled artist",
            duration = "1:00", source = MusicPlatform.QQ_MUSIC,
            albumName = null, coverUrl = "http://cover"
        )
        owner.setSaving(true)
        owner.onCoverUrlChange("edited-cover")
        owner.updateTitle("Edited title")
        owner.updateArtist("Edited artist")
        owner.applyFillOptions(candidate, cover = true, title = true, artist = true)
        assertEquals("current-cover", owner.coverUrlState.value)
        assertEquals("Current", owner.songNameState.value)
        assertEquals("Current artist", owner.artistNameState.value)
        assertFalse(owner.userHasEditedState.value)
        assertFalse(owner.canEditFields())
        assertFalse(owner.canOpenLyricsEditor())

        owner.setSaving(false)
        owner.applyFillOptions(candidate, cover = true, title = true, artist = true)
        assertEquals("https://cover", owner.coverUrlState.value)
        assertEquals("Filled title", owner.songNameState.value)
        assertEquals("Filled artist", owner.artistNameState.value)
        assertTrue(owner.userHasEditedState.value)
        assertTrue(owner.canEditFields())
        assertTrue(owner.canOpenLyricsEditor())
    }

    @Test
    fun `lyrics loading and original info restore keep edit controls locked`() {
        val owner = owner()
        owner.isLyricsEditorOpeningState.value = true
        assertTrue(owner.isLyricsButtonBusy())
        assertFalse(owner.canOpenLyricsEditor())
        owner.isLyricsEditorOpeningState.value = false
        owner.isOriginalInfoRestoringState.value = true
        assertTrue(owner.isLyricsButtonBusy())
        assertFalse(owner.canEditFields())
        assertFalse(owner.canOpenLyricsEditor())
    }

    @Test
    fun `fill cover respects selection and clears a selected missing cover`() {
        val owner = owner()
        val candidate = SongSearchInfo(
            id = "candidate", songName = "Found", singer = "Artist", duration = "1:00",
            source = MusicPlatform.QQ_MUSIC, albumName = null, coverUrl = null
        )
        owner.applyFillOptions(candidate, cover = false, title = false, artist = false)
        assertEquals("current-cover", owner.coverUrlState.value)
        owner.applyFillOptions(candidate, cover = true, title = false, artist = false)
        assertEquals("", owner.coverUrlState.value)
    }

    @Test
    fun `local edit save asks for metadata destination while remote save continues in app`() {
        val owner = owner()
        owner.updateTitle("Changed")
        var confirmations = 0
        var appOnlySaves = 0
        owner.requestSave(
            song.copy(mediaUri = "content://media/external/audio/media/8"),
            onConfirmationNeeded = { confirmations++ },
            saveInAppOnly = { appOnlySaves++ }
        )
        assertEquals(1, confirmations)
        assertEquals(0, appOnlySaves)
        assertTrue(owner.showLocalMetadataWriteBackConfirmState.value)

        owner.showLocalMetadataWriteBackConfirmState.value = false
        owner.requestSave(song, { confirmations++ }, { appOnlySaves++ })
        assertEquals(1, confirmations)
        assertEquals(1, appOnlySaves)

        owner.setSaving(true)
        owner.requestSave(song, { confirmations++ }, { appOnlySaves++ })
        assertEquals(1, appOnlySaves)
    }

    @Test
    fun `unedited song sync follows new metadata while manual edits remain stable`() {
        val owner = owner()
        val updated = song.copy(customName = "Updated title", customArtist = "Updated artist")
        owner.syncUneditedSong(updated, "content://cover/updated")
        assertEquals("Updated title", owner.songNameState.value)
        assertEquals("Updated artist", owner.artistNameState.value)
        owner.updateTitle("Manual title")
        owner.syncUneditedSong(song, "content://cover/later")
        assertEquals("Manual title", owner.songNameState.value)
        assertEquals("Updated artist", owner.artistNameState.value)
    }

    @Test
    fun `managed baseline and resolved cover stop replacing manual edits`() {
        val owner = NowPlayingSongEditOwner(
            initialSong = song,
            initialCoverUrl = "",
            initialBaseline = baseline.copy(coverUrl = ""),
            scope = CoroutineScope(Dispatchers.Unconfined),
            onSavingChanged = {},
            playbackPort = RecordingPlaybackPort()
        )
        owner.applyResolvedCover(song, "content://cover/resolved")
        assertEquals("content://cover/resolved", owner.coverUrlState.value)
        assertEquals("content://cover/resolved", owner.editBaselineState.value.coverUrl)

        val metadata = ManagedDownloadRestorableMetadata(
            sourceStableKey = "stable",
            baseline = ManagedDownloadRestorableMetadata.Baseline(title = "Managed title"),
            overrides = ManagedDownloadRestorableMetadata.Overrides()
        )
        owner.applyManagedBaseline(metadata, null, null)
        assertEquals("Managed title", owner.editBaselineState.value.title)

        owner.updateCoverUrl("manual-cover")
        owner.applyManagedBaseline(metadata.copy(
            baseline = ManagedDownloadRestorableMetadata.Baseline(title = "Later title")
        ), null, null)
        owner.applyResolvedCover(song, "content://cover/later")
        assertEquals("Managed title", owner.editBaselineState.value.title)
        assertEquals("manual-cover", owner.coverUrlState.value)
    }

    @Test
    fun `latest song lookup accepts only the same identity`() {
        val refreshed = song.copy(customName = "Refreshed")
        assertEquals(refreshed, latestMatchingEditSong(refreshed, song))
        assertEquals(song, latestMatchingEditSong(song.copy(id = 99L), song))
        assertEquals(song, latestMatchingEditSong(null, song))
    }

    @Test
    fun `embedded followup publishes only while source chooser still awaits this request`() = runTest {
        val owner = owner()
        val seed = LyricsEditorSeed(lyrics = "sidecar", translatedLyrics = "", hasSidecar = true)
        val request = owner.nextLyricsEditorRequest()
        owner.pendingLyricsSourceSeedState.value = seed
        owner.isPendingEmbeddedLyricsLoadingState.value = true
        val result = EditSongEmbeddedReadResult.Loaded(
            LocalLyricsScanMetadata(
                lyric = null, translatedLyric = null, romanizedLyric = null,
                embeddedLyric = "tag lyric",
                embeddedTranslatedLyric = "tag translation",
                embeddedRomanizedLyric = "tag romanization"
            )
        )
        owner.acceptEmbeddedLyricsResult(
            result, seed, request, SnackbarHostState(), Mockito.mock(Resources::class.java)
        )
        assertEquals("tag lyric", owner.pendingLyricsSourceSeedState.value?.embeddedLyrics)
        assertEquals("tag translation", owner.pendingLyricsSourceSeedState.value?.embeddedTranslatedLyrics)
        assertEquals("tag romanization", owner.pendingLyricsSourceSeedState.value?.embeddedRomanizedLyrics)
        assertTrue(owner.pendingLyricsSourceSeedState.value?.hasEmbeddedLyrics == true)
        assertFalse(owner.isPendingEmbeddedLyricsLoadingState.value)

        owner.nextLyricsEditorRequest()
        owner.acceptEmbeddedLyricsResult(
            EditSongEmbeddedReadResult.Loaded(null), seed, request,
            SnackbarHostState(), Mockito.mock(Resources::class.java)
        )
        assertNull(owner.lyricsEditorSeedState.value)
        assertEquals("tag lyric", owner.pendingLyricsSourceSeedState.value?.embeddedLyrics)
    }

    @Test
    fun `embedded completion is ignored when source chooser was dismissed or editor selected`() = runTest {
        val owner = owner()
        val seed = LyricsEditorSeed(lyrics = "sidecar", translatedLyrics = "", hasSidecar = true)
        val request = owner.nextLyricsEditorRequest()
        owner.acceptEmbeddedLyricsResult(
            EditSongEmbeddedReadResult.Loaded(null), seed, request,
            SnackbarHostState(), Mockito.mock(Resources::class.java)
        )
        assertNull(owner.lyricsEditorSeedState.value)
        owner.pendingLyricsSourceSeedState.value = seed
        owner.lyricsEditorSeedState.value = seed
        owner.acceptEmbeddedLyricsResult(
            EditSongEmbeddedReadResult.Loaded(null), seed, request,
            SnackbarHostState(), Mockito.mock(Resources::class.java)
        )
        assertEquals(seed, owner.pendingLyricsSourceSeedState.value)
    }

    @Test
    fun `cover picker dispatches only for a still current local song`() {
        val local = song.copy(mediaUri = "content://media/external/audio/media/8")
        val pickedUri = Mockito.mock(Uri::class.java)
        var accepted: SongItem? = null
        val onAccepted: (SongItem, Uri) -> Unit = { selected, uri ->
            assertEquals(pickedUri, uri)
            accepted = selected
        }
        withVerifiedEditCoverPickerTarget(null, local, local, null, onAccepted)
        withVerifiedEditCoverPickerTarget(
            pickedUri, local,
            local.copy(id = 99L, mediaUri = "content://media/external/audio/media/99"),
            null, onAccepted
        )
        assertNull(accepted)

        withVerifiedEditCoverPickerTarget(pickedUri, local, local.copy(id = 99L), null, onAccepted)
        assertEquals(local, accepted)
        accepted = null

        withVerifiedEditCoverPickerTarget(pickedUri, local, local, null, onAccepted)
        assertEquals(local, accepted)
    }

    @Test
    fun `empty embedded read opens sidecar editor without offering an empty source`() = runTest {
        val owner = owner()
        val seed = LyricsEditorSeed(lyrics = "sidecar", translatedLyrics = "", hasSidecar = true)
        val request = owner.nextLyricsEditorRequest()
        owner.pendingLyricsSourceSeedState.value = seed
        owner.isPendingEmbeddedLyricsLoadingState.value = true
        owner.acceptEmbeddedLyricsResult(
            EditSongEmbeddedReadResult.Loaded(null), seed, request,
            SnackbarHostState(), Mockito.mock(Resources::class.java)
        )
        assertEquals(seed, owner.lyricsEditorSeedState.value)
        assertNull(owner.pendingLyricsSourceSeedState.value)
        assertFalse(owner.isPendingEmbeddedLyricsLoadingState.value)
    }

    @Test
    fun `source choice owns selection and ignores embedded taps while read is pending`() {
        val owner = owner()
        val seed = LyricsEditorSeed(
            lyrics = "sidecar", translatedLyrics = "",
            embeddedLyrics = "embedded", hasSidecar = true, hasEmbeddedLyrics = true
        )
        owner.pendingLyricsSourceSeedState.value = seed
        owner.isPendingEmbeddedLyricsLoadingState.value = true
        owner.chooseEmbeddedLyrics()
        assertNull(owner.lyricsEditorSeedState.value)

        owner.setSaving(true)
        owner.chooseSidecarLyrics()
        owner.chooseEmbeddedLyrics()
        assertNull(owner.lyricsEditorSeedState.value)
        owner.setSaving(false)

        owner.chooseSidecarLyrics()
        assertEquals(LyricsEditorSource.SIDECAR, owner.lyricsEditorSeedState.value?.source)
        assertEquals("sidecar", owner.lyricsEditorSeedState.value?.lyrics)
        assertNull(owner.pendingLyricsSourceSeedState.value)

        owner.lyricsEditorSeedState.value = null
        owner.pendingLyricsSourceSeedState.value = seed
        owner.isPendingEmbeddedLyricsLoadingState.value = false
        owner.chooseEmbeddedLyrics()
        assertEquals(LyricsEditorSource.EMBEDDED, owner.lyricsEditorSeedState.value?.source)
        assertEquals("embedded", owner.lyricsEditorSeedState.value?.lyrics)
        assertNull(owner.pendingLyricsSourceSeedState.value)
        owner.chooseEmbeddedLyrics()
        assertEquals("embedded", owner.lyricsEditorSeedState.value?.lyrics)

        owner.pendingLyricsSourceSeedState.value = seed
        owner.dismissLyricsSourceChoice()
        assertNull(owner.pendingLyricsSourceSeedState.value)
    }

    @Test
    fun `disposing edit session cancels its restore load import and save jobs`() {
        val owner = owner()
        val cancelled = mutableSetOf<String>()
        fun waitingJob(name: String): suspend kotlinx.coroutines.CoroutineScope.() -> Unit = {
            try {
                awaitCancellation()
            } finally {
                cancelled += name
            }
        }
        owner.launchRestore(waitingJob("restore"))
        owner.launchLyricsLoad(waitingJob("lyrics"))
        owner.launchCoverImport(waitingJob("cover"))
        owner.launchSave(waitingJob("save"))

        owner.dispose()

        assertEquals(setOf("restore", "lyrics", "cover", "save"), cancelled)
    }

    private fun owner(playbackPort: RecordingPlaybackPort = RecordingPlaybackPort()) =
        NowPlayingSongEditOwner(
            initialSong = song,
            initialCoverUrl = "current-cover",
            initialBaseline = baseline,
            scope = CoroutineScope(Dispatchers.Unconfined),
            onSavingChanged = {},
            playbackPort = playbackPort
        )

    private class RecordingPlaybackPort : NowPlayingSongEditPlaybackPort {
        var current: SongItem? = null
        val writes = mutableListOf<SongEditLyricsWrite>()

        override fun currentSong(): SongItem? = current

        override suspend fun saveLyrics(write: SongEditLyricsWrite): Boolean {
            writes += write
            return true
        }
    }

    private fun plan(
        pendingDraft: EditSongLyricsDraft? = null,
        shouldClearLyrics: Boolean = false,
        shouldRestoreLyrics: Boolean = false,
        writeLocalMetadata: Boolean = false
    ) = resolveEditSongLyricsSavePlan(
        pendingDraft = pendingDraft,
        shouldClearLyrics = shouldClearLyrics,
        shouldRestoreLyrics = shouldRestoreLyrics,
        originalLyric = baseline.lyric,
        originalTranslatedLyric = baseline.translatedLyric,
        originalRomanizedLyric = baseline.romanizedLyric,
        writeLocalMetadata = writeLocalMetadata,
        isLocalSong = true
    )
}
