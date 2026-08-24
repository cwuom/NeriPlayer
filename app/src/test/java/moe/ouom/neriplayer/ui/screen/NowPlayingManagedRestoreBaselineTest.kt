package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.core.download.storage.metadata.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.ui.component.lyrics.LyricsEditorSeed
import moe.ouom.neriplayer.ui.component.lyrics.LyricsEditorSource
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingManagedRestoreBaselineTest {
    @Test
    fun `managed restore baseline wins over current edited fields`() {
        val current = EditSongBaseline(
            title = "Edited title",
            artist = "Edited artist",
            coverUrl = "content://root/Covers/edited.jpg",
            lyric = "edited lyric",
            translatedLyric = "edited translation",
            romanizedLyric = "edited romanization"
        )
        val metadata = ManagedDownloadRestorableMetadata(
            sourceStableKey = "stable",
            baseline = ManagedDownloadRestorableMetadata.Baseline(
                title = "Original title",
                artist = "Original artist",
                coverReference = "content://root/Covers/base.jpg",
                originalLyric = "original lyric",
                translatedLyric = "original translation",
                romanizedLyric = "original romanization"
            ),
            overrides = ManagedDownloadRestorableMetadata.Overrides()
        )

        val restored = resolveManagedEditSongBaseline(
            current = current,
            metadata = metadata,
            coverReference = "content://root/Covers/base.jpg"
        )

        assertEquals("Original title", restored.title)
        assertEquals("Original artist", restored.artist)
        assertEquals("content://root/Covers/base.jpg", restored.coverUrl)
        assertEquals("original lyric", restored.lyric)
        assertEquals("original translation", restored.translatedLyric)
        assertEquals("original romanization", restored.romanizedLyric)
    }

    @Test
    fun `edit baseline prefers durable original fields over custom display fields`() {
        val song = SongItem(
            id = 1L,
            name = "Source title",
            artist = "Source artist",
            album = "Album",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = "content://root/Covers/base.jpg",
            customName = "Edited title",
            customArtist = "Edited artist",
            customCoverUrl = "content://root/Covers/edited.jpg",
            originalName = "Original title",
            originalArtist = "Original artist",
            originalCoverUrl = "content://root/Covers/original.jpg",
            originalLyric = "original lyric",
            originalTranslatedLyric = "original translation",
            originalRomanizedLyric = "original romanization"
        )

        val baseline = resolveEditSongBaselineFromSong(
            song = song,
            resolvedDisplayCoverUrl = song.coverUrl,
            displayedLyric = "edited lyric",
            displayedTranslatedLyric = "edited translation",
            displayedRomanizedLyric = "edited romanization"
        )

        assertEquals("Original title", baseline.title)
        assertEquals("Original artist", baseline.artist)
        assertEquals("content://root/Covers/original.jpg", baseline.coverUrl)
        assertEquals("original lyric", baseline.lyric)
        assertEquals("original translation", baseline.translatedLyric)
        assertEquals("original romanization", baseline.romanizedLyric)
    }

    @Test
    fun `managed baseline keeps displayed lyrics when persisted baseline is incomplete`() {
        val current = EditSongBaseline(
            title = "Edited title",
            artist = "Edited artist",
            coverUrl = "content://root/Covers/edited.jpg",
            lyric = "[00:00.00]original lyric",
            translatedLyric = "[00:00.00]original translation",
            romanizedLyric = "[00:00.00]original romanization"
        )
        val metadata = ManagedDownloadRestorableMetadata(
            sourceStableKey = "stable",
            baseline = ManagedDownloadRestorableMetadata.Baseline(
                title = "Original title",
                artist = "Original artist"
            ),
            overrides = ManagedDownloadRestorableMetadata.Overrides()
        )

        val restored = resolveManagedEditSongBaseline(
            current = current,
            metadata = metadata,
            coverReference = null
        )

        assertEquals(current.lyric, restored.lyric)
        assertEquals(current.translatedLyric, restored.translatedLyric)
        assertEquals(current.romanizedLyric, restored.romanizedLyric)
    }

    @Test
    fun `managed sidecar lyrics fill a missing restore baseline`() {
        val current = EditSongBaseline(
            title = "Edited title",
            artist = "Edited artist",
            coverUrl = "edited-cover",
            lyric = "edited lyric",
            translatedLyric = "edited translation",
            romanizedLyric = "edited romanization"
        )
        val restored = resolveManagedEditSongBaseline(
            current = current,
            metadata = null,
            coverReference = null,
            sidecarLyrics = ManagedDownloadStorage.DownloadedLyricsBundle(
                lyric = "sidecar lyric",
                translatedLyric = "sidecar translation",
                romanizedLyric = "sidecar romanization",
                hasOriginalSidecar = true,
                hasTranslatedSidecar = true,
                hasRomanizedSidecar = true
            )
        )

        assertEquals("sidecar lyric", restored.lyric)
        assertEquals("sidecar translation", restored.translatedLyric)
        assertEquals("sidecar romanization", restored.romanizedLyric)
    }

    @Test
    fun `restore preview replaces the editor seed without touching the song`() {
        val seed = LyricsEditorSeed(
            lyrics = "edited lyric",
            translatedLyrics = "edited translation",
            romanizedLyrics = "edited romanization",
            hasSidecar = true,
            source = LyricsEditorSource.SIDECAR
        )

        val preview = applyLyricsEditorRestorePreview(
            seed = seed,
            shouldClearLyrics = false,
            shouldRestoreLyrics = true,
            originalLyric = "original lyric",
            originalTranslatedLyric = "original translation",
            originalRomanizedLyric = "original romanization"
        )

        assertEquals("original lyric", preview.lyrics)
        assertEquals("original translation", preview.translatedLyrics)
        assertEquals("original romanization", preview.romanizedLyrics)
        assertFalse(preview.hasSidecar)
        assertEquals(LyricsEditorSource.SIDECAR, preview.source)
        assertEquals("edited lyric", seed.lyrics)
    }

    @Test
    fun `restore preview clears editor seed for an explicitly lyricless source`() {
        val preview = applyLyricsEditorRestorePreview(
            seed = LyricsEditorSeed(
                lyrics = "edited lyric",
                translatedLyrics = "edited translation",
                romanizedLyrics = "edited romanization"
            ),
            shouldClearLyrics = true,
            shouldRestoreLyrics = false,
            originalLyric = "ignored",
            originalTranslatedLyric = "ignored",
            originalRomanizedLyric = "ignored"
        )

        assertEquals("", preview.lyrics)
        assertEquals("", preview.translatedLyrics)
        assertEquals("", preview.romanizedLyrics)
    }

    @Test
    fun `restore plan uses fetched source lyrics when available`() {
        val plan = resolveEditSongLyricsRestorePlan(
            baseline = EditSongBaseline(
                title = "title",
                artist = "artist",
                coverUrl = "cover",
                lyric = "stale lyric",
                translatedLyric = "stale translation",
                romanizedLyric = "stale romanization"
            ),
            sourceInfo = NowPlayingViewModel.OriginalSongInfo(
                name = "title",
                artist = "artist",
                coverUrl = "cover",
                lyric = "source lyric",
                translatedLyric = "source translation",
                romanizedLyric = "source romanization"
            )
        )

        assertFalse(plan.shouldClearLyrics)
        assertTrue(plan.shouldRestoreLyrics)
        assertEquals("source lyric", plan.originalLyric)
        assertEquals("source translation", plan.originalTranslatedLyric)
        assertEquals("source romanization", plan.originalRomanizedLyric)
    }

    @Test
    fun `restore plan uses a fetched translation when the baseline is empty`() {
        val plan = resolveEditSongLyricsRestorePlan(
            baseline = EditSongBaseline(
                title = "title",
                artist = "artist",
                coverUrl = "cover",
                lyric = null,
                translatedLyric = null,
                romanizedLyric = null
            ),
            sourceInfo = NowPlayingViewModel.OriginalSongInfo(
                name = "title",
                artist = "artist",
                coverUrl = "cover",
                translatedLyric = "source translation"
            )
        )

        assertTrue(plan.shouldRestoreLyrics)
        assertEquals("source translation", plan.originalTranslatedLyric)
    }

    @Test
    fun `restore plan clears lyrics for a source that has no original lyrics`() {
        val plan = resolveEditSongLyricsRestorePlan(
            baseline = EditSongBaseline(
                title = "title",
                artist = "artist",
                coverUrl = "cover",
                lyric = "edited lyric",
                translatedLyric = "edited translation",
                romanizedLyric = "edited romanization"
            ),
            sourceInfo = NowPlayingViewModel.OriginalSongInfo(
                name = "title",
                artist = "artist",
                coverUrl = "cover",
                shouldClearLyrics = true
            )
        )

        assertTrue(plan.shouldClearLyrics)
        assertFalse(plan.shouldRestoreLyrics)
        assertEquals("", plan.originalLyric)
        assertEquals("", plan.originalTranslatedLyric)
        assertEquals("", plan.originalRomanizedLyric)
    }

    @Test
    fun `restore plan falls back to the baseline when fetched source has no lyrics`() {
        val plan = resolveEditSongLyricsRestorePlan(
            baseline = EditSongBaseline(
                title = "title",
                artist = "artist",
                coverUrl = "cover",
                lyric = "edited lyric",
                translatedLyric = "edited translation",
                romanizedLyric = "edited romanization"
            ),
            sourceInfo = NowPlayingViewModel.OriginalSongInfo(
                name = "title",
                artist = "artist",
                coverUrl = "cover"
            )
        )

        assertFalse(plan.shouldClearLyrics)
        assertTrue(plan.shouldRestoreLyrics)
        assertEquals("edited lyric", plan.originalLyric)
        assertEquals("edited translation", plan.originalTranslatedLyric)
        assertEquals("edited romanization", plan.originalRomanizedLyric)
    }

    @Test
    fun `restore plan skips lyrics when neither source nor baseline has lyrics`() {
        val plan = resolveEditSongLyricsRestorePlan(
            baseline = EditSongBaseline(
                title = "title",
                artist = "artist",
                coverUrl = "cover",
                lyric = null,
                translatedLyric = null,
                romanizedLyric = null
            ),
            sourceInfo = null
        )

        assertFalse(plan.shouldClearLyrics)
        assertFalse(plan.shouldRestoreLyrics)
    }

    @Test
    fun `lyrics editor save is kept as a draft until outer song info save`() {
        val draft = EditSongLyricsDraft(
            lyric = "draft lyric",
            translatedLyric = "draft translation",
            romanizedLyric = "draft romanization",
            writeLocalMetadata = true
        )

        val resolved = resolveEditSongLyricsForSave(
            draft = draft,
            shouldClearLyrics = false,
            shouldRestoreLyrics = true,
            originalLyric = "original lyric",
            originalTranslatedLyric = "original translation",
            originalRomanizedLyric = "original romanization"
        )

        assertEquals(draft, resolved)
        assertEquals(
            "draft lyric",
            applyEditSongLyricsDraftPreview(
                seed = LyricsEditorSeed(
                    lyrics = "old lyric",
                    translatedLyrics = "old translation"
                ),
                draft = draft
            ).lyrics
        )
    }

    @Test
    fun `original info fetch is limited to supported remote sources`() {
        val neteaseSong = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Netease",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null,
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC
        )
        val youtubeSong = neteaseSong.copy(
            album = "YouTube Music",
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC,
            matchedSongId = "netease:99",
            channelId = "youtubeMusic"
        )
        val biliSong = neteaseSong.copy(
            album = "bilibili|123",
            channelId = "bilibili"
        )
        val localSong = neteaseSong.copy(mediaUri = "content://media/song")

        assertTrue(shouldFetchOriginalSongInfo(neteaseSong))
        assertFalse(shouldFetchOriginalSongInfo(youtubeSong))
        assertTrue(shouldFetchOriginalSongInfo(biliSong))
        assertFalse(shouldFetchOriginalSongInfo(localSong))
    }

    @Test
    fun `stale original info requests are rejected after song changes`() {
        assertFalse(
            isOriginalInfoRequestCurrent(
                requestId = 3,
                currentRequestId = 3,
                requestSongKey = "old-song",
                currentSongKey = "new-song"
            )
        )
        assertFalse(
            isOriginalInfoRequestCurrent(
                requestId = 2,
                currentRequestId = 3,
                requestSongKey = "song",
                currentSongKey = "song"
            )
        )
        assertTrue(
            isOriginalInfoRequestCurrent(
                requestId = 3,
                currentRequestId = 3,
                requestSongKey = "song",
                currentSongKey = "song"
            )
        )
    }
}
