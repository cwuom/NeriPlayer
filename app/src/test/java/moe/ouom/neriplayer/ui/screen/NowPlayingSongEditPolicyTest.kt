package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootUnavailableException
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.model.lyrics.LyricsEditorSeed
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.EditSongMetadataSnapshot
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.canChooseEmbeddedLyricsSource
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.editSongCoverForSave
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.editSongLyricsLoadErrorMessage
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.editSongMetadataSnapshot
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.editSongSaveTiming
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.isEditSongLyricsPermissionFailure
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.keepFilledLyricsWriteBackPrompt
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.refreshedEditSongBaselineCover
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.resolveEditSongBaselineFromSong
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.resolvePendingLocalCoverReplacementTarget
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.restoredEditSongLyricsOrDraft
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.seedWithEmbeddedEditLyrics
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.selectEditSongInitialCover
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.shouldApplyResolvedEditSongCover
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.shouldFetchOriginalSongInfo
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.shouldHandleEditLyricsPermissionLoss
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.shouldOfferFilledLyricsWriteBack
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.shouldOpenEditLyricsSeedImmediately
import moe.ouom.neriplayer.ui.screen.nowplaying.edit.shouldPersistEditSongManualCover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingSongEditPolicyTest {
    @Test
    fun `baseline cover only adopts a nonblank initial reference for missing or remote covers`() {
        assertNull(refreshedEditSongBaselineCover("content://cover/local", "content://cover/new"))
        assertNull(refreshedEditSongBaselineCover("", ""))
        assertEquals("content://cover/new",
            refreshedEditSongBaselineCover("", "content://cover/new")
        )
        assertEquals(
            "content://cover/new",
            refreshedEditSongBaselineCover("https://cover/remote", "content://cover/new")
        )
    }

    @Test
    fun `filled lyrics offers local writeback only after successful editable fill`() {
        assertTrue(shouldOfferFilledLyricsWriteBack(true, false, true))
        assertFalse(shouldOfferFilledLyricsWriteBack(false, false, true))
        assertFalse(shouldOfferFilledLyricsWriteBack(true, true, true))
        assertFalse(shouldOfferFilledLyricsWriteBack(true, false, false))
        assertTrue(keepFilledLyricsWriteBackPrompt(true, false, false, true))
        assertTrue(keepFilledLyricsWriteBackPrompt(false, true, false, true))
        assertFalse(keepFilledLyricsWriteBackPrompt(false, false, false, true))
    }

    @Test
    fun `restored lyrics keep nullable original unless an editor draft takes precedence`() {
        assertNull(restoredEditSongLyricsOrDraft("", null, false, true))
        assertEquals("original", restoredEditSongLyricsOrDraft("draft", "original", false, true))
        assertEquals("draft", restoredEditSongLyricsOrDraft("draft", "original", true, true))
        assertEquals("draft", restoredEditSongLyricsOrDraft("draft", "original", false, false))
    }

    @Test
    fun `manual cover only persists when base restoration is not requested`() {
        assertTrue(shouldPersistEditSongManualCover(true, false))
        assertFalse(shouldPersistEditSongManualCover(false, false))
        assertFalse(shouldPersistEditSongManualCover(true, true))
    }

    @Test
    fun `metadata writeback keeps refreshed overrides and falls back to source fields`() {
        val song = SongItem(8L, "Source", "Artist", "Album", 2L, 60_000L, "https://source-cover")
        assertEquals("https://source-cover", editSongMetadataSnapshot(song).coverUrl)
        assertEquals("Source", editSongMetadataSnapshot(song).name)
        assertEquals("Artist", editSongMetadataSnapshot(song).artist)
        val edited = song.copy(customName = "Edited", customArtist = "Edited artist", customCoverUrl = "local-cover")
        assertEquals(
            EditSongMetadataSnapshot("local-cover", "Edited", "Edited artist"),
            editSongMetadataSnapshot(edited)
        )
        assertNull(editSongCoverForSave(""))
        assertEquals("local-cover", editSongCoverForSave("local-cover"))
    }

    @Test
    fun `save timing uses separate app and provider budgets`() {
        val app = editSongSaveTiming(100L, 1_099L, false, true)
        assertFalse(app.overBudget)
        assertEquals(999L, app.elapsedMs)
        assertTrue(app.message.contains("success=true"))
        assertTrue(editSongSaveTiming(100L, 1_100L, false, false).overBudget)
        assertFalse(editSongSaveTiming(100L, 2_099L, true, true).overBudget)
        assertTrue(editSongSaveTiming(100L, 2_100L, true, false).overBudget)
    }

    @Test
    fun `editor opens directly unless a local sidecar has an untouched source choice`() {
        assertFalse(shouldOpenEditLyricsSeedImmediately(false, true, true))
        assertTrue(shouldOpenEditLyricsSeedImmediately(true, true, true))
        assertTrue(shouldOpenEditLyricsSeedImmediately(false, false, true))
        assertTrue(shouldOpenEditLyricsSeedImmediately(false, true, false))
        assertFalse(shouldHandleEditLyricsPermissionLoss(false, true))
        assertFalse(shouldHandleEditLyricsPermissionLoss(true, false))
        assertTrue(shouldHandleEditLyricsPermissionLoss(true, true))
        assertTrue(canChooseEmbeddedLyricsSource(false, true))
        assertFalse(canChooseEmbeddedLyricsSource(true, true))
        assertFalse(canChooseEmbeddedLyricsSource(false, false))
    }

    @Test
    fun `embedded source choice only appears for actual tag lyric text`() {
        val sidecar = LyricsEditorSeed(lyrics = "sidecar", translatedLyrics = "", hasSidecar = true)
        assertNull(seedWithEmbeddedEditLyrics(sidecar, null))
        assertNull(
            seedWithEmbeddedEditLyrics(
                sidecar,
                LocalLyricsScanMetadata(null, null, null)
            )
        )
        val choice = seedWithEmbeddedEditLyrics(
            sidecar,
            LocalLyricsScanMetadata(
                null,
                null,
                null,
                embeddedLyric = "tag",
                embeddedTranslatedLyric = "translated",
                embeddedRomanizedLyric = "romanized"
            )
        )
        assertEquals("tag", choice?.embeddedLyrics)
        assertEquals("translated", choice?.embeddedTranslatedLyrics)
        assertEquals("romanized", choice?.embeddedRomanizedLyrics)
        assertTrue(choice?.hasEmbeddedLyrics == true)
        val partial = seedWithEmbeddedEditLyrics(
            sidecar,
            LocalLyricsScanMetadata(null, null, null, embeddedLyric = "only original")
        )
        assertEquals("", partial?.embeddedTranslatedLyrics)
        assertEquals("", partial?.embeddedRomanizedLyrics)
        val translatedOnly = seedWithEmbeddedEditLyrics(
            sidecar,
            LocalLyricsScanMetadata(null, null, null, embeddedTranslatedLyric = "only translation")
        )
        assertEquals("only translation", translatedOnly?.embeddedTranslatedLyrics)
    }

    @Test
    fun `lyrics load distinguishes lost directory or file access from ordinary read failure`() {
        val directory = ManagedDownloadRootUnavailableException("content://missing")
        val file = SecurityException("denied")
        val ordinary = IllegalStateException("unreadable")
        assertTrue(isEditSongLyricsPermissionFailure(directory))
        assertTrue(isEditSongLyricsPermissionFailure(file))
        assertFalse(isEditSongLyricsPermissionFailure(ordinary))
        assertEquals("歌词编辑器配置的下载目录授权已失效",
            editSongLyricsLoadErrorMessage(directory)
        )
        assertEquals("歌词编辑器读取本地文件权限失效", editSongLyricsLoadErrorMessage(file))
        assertEquals("歌词编辑器初始化失败", editSongLyricsLoadErrorMessage(ordinary))
    }

    @Test
    fun `initial cover prefers local direct then downloaded before remote direct`() {
        assertEquals(
            "content://cover/direct",
            selectEditSongInitialCover(
                "content://cover/direct", "content://cover/downloaded",
                "content://cover/original", "content://cover/resolved"
            )
        )
        assertEquals(
            "content://cover/downloaded",
            selectEditSongInitialCover(
                "https://cover/remote", "content://cover/downloaded",
                "content://cover/original", "content://cover/resolved"
            )
        )
    }

    @Test
    fun `initial cover retains remote direct before original and rejects remote download`() {
        assertEquals(
            "https://cover/direct",
            selectEditSongInitialCover(
                "https://cover/direct", "https://cover/downloaded",
                "content://cover/original", "content://cover/resolved"
            )
        )
        assertEquals(
            "content://cover/original",
            selectEditSongInitialCover(
                null, "https://cover/downloaded",
                "content://cover/original", "content://cover/resolved"
            )
        )
    }

    @Test
    fun `initial cover falls through empty inputs to resolved reference`() {
        assertEquals(
            "content://cover/resolved",
            selectEditSongInitialCover("", "", null, "content://cover/resolved")
        )
        assertEquals("", selectEditSongInitialCover(null, null, null, null))
    }

    @Test
    fun `baseline falls back to song identity and displayed lyrics without original fields`() {
        val song = SongItem(8L, "Title", "Artist", "Album", 2L, 60_000L, null)
        val baseline = resolveEditSongBaselineFromSong(
            song, "content://cover/resolved", "displayed", "translation", "romanized"
        )
        assertEquals("Title", baseline.title)
        assertEquals("Artist", baseline.artist)
        assertEquals("content://cover/resolved", baseline.coverUrl)
        assertEquals("displayed", baseline.lyric)
        assertEquals("translation", baseline.translatedLyric)
        assertEquals("romanized", baseline.romanizedLyric)
    }

    @Test
    fun `original info lookup accepts each supported remote source identity`() {
        val song = SongItem(8L, "Title", "Artist", "Other", 2L, 60_000L, null)
        assertFalse(shouldFetchOriginalSongInfo(song))
        assertTrue(shouldFetchOriginalSongInfo(song.copy(channelId = "netease")))
        assertTrue(shouldFetchOriginalSongInfo(song.copy(mediaUri = "https://music.163.com/song/8")))
        assertTrue(shouldFetchOriginalSongInfo(song.copy(channelId = "bilibili")))
        assertTrue(shouldFetchOriginalSongInfo(song.copy(album = "bilibili|8")))
        assertFalse(shouldFetchOriginalSongInfo(song.copy(mediaUri = "content://media/song/8")))
    }

    @Test
    fun `resolved cover only fills an unedited empty field with a nonblank reference`() {
        assertFalse(shouldApplyResolvedEditSongCover(true, "", "content://cover/new"))
        assertFalse(shouldApplyResolvedEditSongCover(false, "manual", "content://cover/new"))
        assertFalse(shouldApplyResolvedEditSongCover(false, "", null))
        assertFalse(shouldApplyResolvedEditSongCover(false, "", ""))
        assertTrue(shouldApplyResolvedEditSongCover(false, "", "content://cover/new"))
    }

    @Test
    fun `cover picker target requires both pending and current local song`() {
        val local = SongItem(
            8L, "Title", "Artist", "Album", 2L, 60_000L, null,
            mediaUri = "content://media/external/audio/media/8"
        )
        assertNull(resolvePendingLocalCoverReplacementTarget(null, local))
        assertNull(resolvePendingLocalCoverReplacementTarget(local, null))
        assertNull(
            resolvePendingLocalCoverReplacementTarget(
                local, local.copy(id = 99L, mediaUri = "content://media/external/audio/media/99")
            )
        )
        assertEquals(local, resolvePendingLocalCoverReplacementTarget(local, local))
    }
}
