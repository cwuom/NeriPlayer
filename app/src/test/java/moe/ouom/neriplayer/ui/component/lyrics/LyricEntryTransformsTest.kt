package moe.ouom.neriplayer.ui.component.lyrics

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricEntryTransformsTest {

    @Test
    fun `resolveLyricsEditorSeed prefers prepared lyrics over stored model`() {
        val seed = resolveLyricsEditorSeed(
            song = demoSong(
                matchedLyric = "旧原文",
                matchedTranslatedLyric = "旧译文",
                originalLyric = "原始原文",
                originalTranslatedLyric = "原始译文"
            ),
            preparedLyrics = "准备好的原文",
            preparedTranslatedLyrics = "准备好的译文"
        )

        assertEquals("准备好的原文", seed.lyrics)
        assertEquals("准备好的译文", seed.translatedLyrics)
    }

    @Test
    fun `resolveLyricsEditorSeed keeps explicit cleared lyrics`() {
        val seed = resolveLyricsEditorSeed(
            song = demoSong(
                matchedLyric = "",
                matchedTranslatedLyric = "",
                originalLyric = "原始原文",
                originalTranslatedLyric = "原始译文"
            )
        )

        assertEquals("", seed.lyrics)
        assertEquals("", seed.translatedLyrics)
    }

    @Test
    fun `resolveLyricsEditorSeed falls back to original lyrics for legacy data`() {
        val seed = resolveLyricsEditorSeed(
            song = demoSong(
                matchedLyric = null,
                matchedTranslatedLyric = null,
                originalLyric = "原始原文",
                originalTranslatedLyric = "原始译文"
            )
        )

        assertEquals("原始原文", seed.lyrics)
        assertEquals("原始译文", seed.translatedLyrics)
    }

    @Test
    fun `resolveLyricsEditorInitialText keeps current matched lyric over preferred netease lyric`() {
        val resolved = resolveLyricsEditorInitialText(
            matchedLyric = "[00:00.00]用户改过的歌词",
            preferredNeteaseLyric = "[123,456](0,10,0)更丰富的 YRC",
            displayedLyricsText = "[123,456](0,10,0)更丰富的 YRC",
            displayedHasWordTimedEntries = true,
            fallbackLyricsText = "[00:00.00]fallback"
        )

        assertEquals("[00:00.00]用户改过的歌词", resolved)
    }

    @Test
    fun `resolveLyricsEditorInitialText uses preferred lyric only when current lyric is absent`() {
        val resolved = resolveLyricsEditorInitialText(
            matchedLyric = null,
            preferredNeteaseLyric = "[123,456](0,10,0)更丰富的 YRC",
            displayedLyricsText = "",
            displayedHasWordTimedEntries = false,
            fallbackLyricsText = "[00:00.00]fallback"
        )

        assertEquals("[123,456](0,10,0)更丰富的 YRC", resolved)
    }

    @Test
    fun `resolvePreferredLyricContent falls back to legacy lyric before remote preferred lyric`() {
        val resolved = resolvePreferredLyricContent(
            matchedLyric = null,
            preferredNeteaseLyric = "[123,456](0,10,0)更丰富的 YRC",
            legacyLyric = "[00:00.00]升级前保存的歌词"
        )

        assertEquals("[00:00.00]升级前保存的歌词", resolved)
    }

    @Test
    fun `resolveStoredLyricText normalizes legacy lrc timestamps`() {
        val resolved = resolveStoredLyricText(
            currentLyric = "[00:00:15]The sky blue archive!",
            legacyLyric = null
        )

        assertEquals("[00:00.15]The sky blue archive!", resolved)
    }

    @Test
    fun `local editor seed keeps sidecar original translation and romanized lyrics together`() {
        val seed = resolveLocalLyricsEditorSeed(
            song = demoSong(
                matchedLyric = "embedded original",
                matchedTranslatedLyric = "embedded translation",
                originalLyric = null,
                originalTranslatedLyric = null
            ),
            sidecarLyrics = "sidecar original",
            sidecarTranslatedLyrics = "sidecar translation",
            sidecarRomanizedLyrics = "sidecar romanized",
            embeddedLyrics = "embedded original",
            embeddedTranslatedLyrics = "embedded translation",
            embeddedRomanizedLyrics = "embedded romanized",
            hasOriginalSidecar = true,
            hasTranslatedSidecar = true,
            hasRomanizedSidecar = true
        )

        assertEquals("sidecar original", seed.lyrics)
        assertEquals("sidecar translation", seed.translatedLyrics)
        assertEquals("sidecar romanized", seed.romanizedLyrics)
        assertEquals(LyricsEditorSource.SIDECAR, seed.source)
        assertTrue(seed.hasSidecar)
    }

    @Test
    fun `local editor seed uses embedded lyrics when every sidecar was deleted`() {
        val seed = resolveLocalLyricsEditorSeed(
            song = demoSong(
                matchedLyric = "embedded original",
                matchedTranslatedLyric = "embedded translation",
                originalLyric = null,
                originalTranslatedLyric = null
            ),
            sidecarLyrics = null,
            sidecarTranslatedLyrics = null,
            sidecarRomanizedLyrics = null,
            embeddedLyrics = "embedded original",
            embeddedTranslatedLyrics = "embedded translation",
            embeddedRomanizedLyrics = "embedded romanized",
            hasOriginalSidecar = false,
            hasTranslatedSidecar = false,
            hasRomanizedSidecar = false
        )

        assertEquals("embedded original", seed.lyrics)
        assertEquals("embedded translation", seed.translatedLyrics)
        assertEquals("embedded romanized", seed.romanizedLyrics)
        assertEquals(LyricsEditorSource.EMBEDDED, seed.source)
        assertTrue(!seed.hasSidecar)
    }

    @Test
    fun `local editor seed keeps source choice when sidecar content arrives before flags`() {
        val seed = resolveLocalLyricsEditorSeed(
            song = demoSong(
                matchedLyric = "embedded original",
                matchedTranslatedLyric = "embedded translation",
                originalLyric = null,
                originalTranslatedLyric = null
            ),
            sidecarLyrics = null,
            sidecarTranslatedLyrics = "sidecar translation",
            sidecarRomanizedLyrics = null,
            embeddedLyrics = "embedded original",
            embeddedTranslatedLyrics = "embedded translation",
            embeddedRomanizedLyrics = "embedded romanized",
            hasOriginalSidecar = false,
            hasTranslatedSidecar = false,
            hasRomanizedSidecar = false
        )

        assertTrue(seed.hasSidecar)
        assertEquals(LyricsEditorSource.SIDECAR, seed.source)
        assertEquals("sidecar translation", seed.sidecarTranslatedLyrics)
    }

    private fun demoSong(
        matchedLyric: String?,
        matchedTranslatedLyric: String?,
        originalLyric: String?,
        originalTranslatedLyric: String?
    ): SongItem {
        return SongItem(
            id = 1L,
            name = "Demo",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            matchedLyric = matchedLyric,
            matchedTranslatedLyric = matchedTranslatedLyric,
            originalLyric = originalLyric,
            originalTranslatedLyric = originalTranslatedLyric
        )
    }
}
