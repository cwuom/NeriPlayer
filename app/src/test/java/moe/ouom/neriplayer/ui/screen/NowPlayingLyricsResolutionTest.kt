package moe.ouom.neriplayer.ui.screen

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.WordTiming
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.ManagedLyricVariant
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingBackgroundRawLyrics
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingLyricsBackgroundInputs
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.NowPlayingNeteaseFallback
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildBackgroundLyricsState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildBackgroundRawLyrics
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.effectiveRawLyric
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.hasDisplayableContent
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.resolveBackgroundOriginal
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.resolveBackgroundPhonetic
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.resolveBackgroundTranslated
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.shouldDelayNowPlayingOnlineLyrics
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.shouldReadNeteaseOriginal
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.shouldReadNeteaseRomanized
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingLyricsResolutionTest {
    private val song = SongItem(6L, "Remote", "Artist", "Album", 1L, 60_000L, null)
    private val localSong = song.copy(album = "__local_files__", mediaUri = "content://media/audio/6")

    @Test
    fun `stored and downloaded embedded romanization precede online fallback and preserve independent clears`() = runTest {
        val raw = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
            <body><div><p begin="00:01.000" end="00:02.000"><span begin="00:01.000" end="00:02.000">Hello</span>
            <span ttm:role="x-roman">Halo</span></p></div></body></tt>"""
        val sources = object : FakeNowPlayingLyricsSources() {
            override suspend fun onlineRomanized(song: SongItem): List<LyricEntry> = error("must keep existing romanization")
        }
        val originalSources = listOf(
            inputs(song.copy(matchedLyric = raw)),
            inputs(localSong, downloaded = ManagedDownloadStorage.DownloadedLyricsBundle(raw, null, null,
                hasOriginalSidecar = true), managed = true)
        )
        for (input in originalSources) {
            assertFalse(shouldReadNeteaseRomanized(input))
            val netease = NowPlayingNeteaseFallback("", "[00:01.00]network romaji")
            assertEquals(listOf("Halo"), resolveBackgroundPhonetic(input, netease, sources).map { it.text })
            assertNull(buildBackgroundRawLyrics(input, netease).phonetic)
            val cleared = input.copy(song = input.song?.copy(lyricSyncEdited = true, matchedRomanizedLyric = ""))
            assertTrue(resolveBackgroundPhonetic(cleared, NowPlayingNeteaseFallback("", ""), sources).isEmpty())
        }
    }

    @Test
    fun `confirmed edited variants override local download and word timing refresh including explicit clears`() = runTest {
        val local = LocalLyricsScanMetadata("[00:01.00]local", "[00:01.00]local translation", "[00:01.00]local romanized")
        val downloaded = ManagedDownloadStorage.DownloadedLyricsBundle(
            "[00:01.00]download", "[00:01.00]download translation", "[00:01.00]download romanized",
            hasOriginalSidecar = true, hasTranslatedSidecar = true, hasRomanizedSidecar = true
        )
        val sources = object : FakeNowPlayingLyricsSources() {
            override suspend fun onlineOriginal(song: SongItem): List<LyricEntry> = error("must preserve edited original")
            override suspend fun onlineTranslated(song: SongItem): List<LyricEntry> = error("must preserve edited translation")
            override suspend fun onlineRomanized(song: SongItem): List<LyricEntry> = error("must preserve edited romanized")
        }
        for (base in listOf(song, localSong)) {
            for (managed in listOf(false, true)) {
                for (text in listOf("[00:01.00]user", "")) {
                    val edited = base.copy(lyricSyncEdited = true, lyricSyncRevision = 20,
                        matchedLyric = text, matchedTranslatedLyric = text, matchedRomanizedLyric = text)
                    val inputs = inputs(edited, local, downloaded, managed, preferWordTimed = true)
                    for (variant in ManagedLyricVariant.entries) assertEquals(text, effectiveRawLyric(inputs, variant))
                    val netease = NowPlayingNeteaseFallback("[00:01.00]network old", "[00:01.00]network romanized")
                    val raw = buildBackgroundRawLyrics(inputs, netease)
                    assertEquals(text, raw.original)
                    assertEquals(text, raw.translated)
                    assertEquals(text, raw.phonetic)
                    val expected = if (text.isEmpty()) emptyList() else listOf("user")
                    assertEquals(expected, resolveBackgroundOriginal(inputs, raw, sources).map { it.text })
                    assertEquals(expected, resolveBackgroundTranslated(inputs, raw, sources).map { it.text })
                    assertEquals(expected, resolveBackgroundPhonetic(inputs, netease, sources).map { it.text })
                }
            }
        }
    }

    @Test
    fun `managed sidecar wins over embedded and stored lyric for all variants`() {
        val local = LocalLyricsScanMetadata(
            lyric = "local", translatedLyric = "local translation", romanizedLyric = "local phonetic"
        )
        val downloaded = ManagedDownloadStorage.DownloadedLyricsBundle(
            lyric = "downloaded", translatedLyric = "downloaded translation",
            romanizedLyric = "downloaded phonetic", hasOriginalSidecar = true,
            hasTranslatedSidecar = true, hasRomanizedSidecar = true
        )
        val inputs = inputs(localSong.copy(matchedLyric = "stored"), local, downloaded, managed = true)
        assertEquals("downloaded", effectiveRawLyric(inputs, ManagedLyricVariant.ORIGINAL))
        assertEquals("downloaded translation",
            effectiveRawLyric(inputs, ManagedLyricVariant.TRANSLATED)
        )
        assertEquals("downloaded phonetic",
            effectiveRawLyric(inputs, ManagedLyricVariant.ROMANIZED)
        )
        assertFalse(shouldReadNeteaseOriginal(inputs))
        assertFalse(shouldReadNeteaseRomanized(inputs))
    }

    @Test
    fun `non managed local embedded lyric remains authoritative in background`() = runTest {
        val inputs = inputs(
            localSong.copy(matchedLyric = "[00:01.00]stored"),
            LocalLyricsScanMetadata(
                lyric = "[00:02.00]embedded",
                translatedLyric = "[00:02.00]translation",
                romanizedLyric = "[00:02.00]phonetic"
            )
        )
        val raw = buildBackgroundRawLyrics(inputs, NowPlayingNeteaseFallback("", ""))
        assertEquals("embedded", resolveBackgroundOriginal(
            inputs,
            raw,
            FakeNowPlayingLyricsSources()
        ).single().text)
        assertEquals("translation", resolveBackgroundTranslated(
            inputs,
            raw,
            FakeNowPlayingLyricsSources()
        ).single().text)
        assertEquals("phonetic", resolveBackgroundPhonetic(
            inputs,
            NowPlayingNeteaseFallback("", ""), FakeNowPlayingLyricsSources()
        ).single().text)
    }

    @Test
    fun `stored normal lyric skips online lookup when word timing is not preferred`() = runTest {
        var onlineCalls = 0
        val sources = object : FakeNowPlayingLyricsSources() {
            override suspend fun onlineOriginal(song: SongItem): List<LyricEntry> {
                onlineCalls++
                return listOf(LyricEntry("online", 1_000L, 2_000L))
            }
        }
        val inputs = inputs(song.copy(matchedLyric = "[00:01.00]stored"), preferWordTimed = false)
        val raw = buildBackgroundRawLyrics(inputs, NowPlayingNeteaseFallback("", ""))
        assertEquals("stored", resolveBackgroundOriginal(inputs, raw, sources).single().text)
        assertEquals(0, onlineCalls)
    }

    @Test
    fun `stored ordinary line accepts online word timing only when it is actually timed`() = runTest {
        val inputs = inputs(song.copy(matchedLyric = "[00:01.00]stored"), preferWordTimed = true)
        val raw = buildBackgroundRawLyrics(inputs, NowPlayingNeteaseFallback("", ""))
        val wordTimed = object : FakeNowPlayingLyricsSources() {
            override suspend fun onlineOriginal(song: SongItem) = listOf(
                LyricEntry("timed", 1_000L, 2_000L, words = listOf(WordTiming(1_000L, 2_000L)))
            )
        }
        assertEquals("timed", resolveBackgroundOriginal(inputs, raw, wordTimed).single().text)
        assertEquals("stored", resolveBackgroundOriginal(
            inputs,
            raw,
            FakeNowPlayingLyricsSources()
        ).single().text)
    }

    @Test
    fun `remote missing and collapsed lyrics use online source while local missing remains empty`() = runTest {
        var onlineCalls = 0
        val sources = object : FakeNowPlayingLyricsSources() {
            override suspend fun onlineOriginal(song: SongItem): List<LyricEntry> {
                onlineCalls++
                return listOf(LyricEntry("online", 1_000L, 2_000L))
            }
        }
        val missing = inputs(song)
        assertEquals("online", resolveBackgroundOriginal(
            missing,
            NowPlayingBackgroundRawLyrics(null, null, null),
            sources
        ).single().text)
        val collapsed = "[00:00.00]one\n[00:00.00]two\n[00:00.00]three"
        assertEquals("online", resolveBackgroundOriginal(
            missing,
            NowPlayingBackgroundRawLyrics(collapsed, null, null),
            sources
        ).single().text)
        assertTrue(
            resolveBackgroundOriginal(
                inputs(localSong),
                NowPlayingBackgroundRawLyrics(null, null, null),
                sources
            ).isEmpty())
        assertEquals(2, onlineCalls)
    }

    @Test
    fun `translation falls back online for missing and collapsed remote text`() = runTest {
        val sources = object : FakeNowPlayingLyricsSources() {
            override suspend fun onlineTranslated(song: SongItem) = listOf(LyricEntry("online translation", 1_000L, 2_000L))
        }
        val inputs = inputs(song)
        assertEquals("online translation", resolveBackgroundTranslated(
            inputs, NowPlayingBackgroundRawLyrics(null, null, null), sources
        ).single().text)
        val collapsed = "[00:00.00]one\n[00:00.00]two\n[00:00.00]three"
        assertEquals("online translation", resolveBackgroundTranslated(
            inputs, NowPlayingBackgroundRawLyrics(null, collapsed, null), sources
        ).single().text)
        assertEquals("ordinary", resolveBackgroundTranslated(
            inputs, NowPlayingBackgroundRawLyrics(null, "[00:01.00]ordinary", null), sources
        ).single().text)
    }

    @Test
    fun `missing remote phonetic uses online source and local without source stays empty`() = runTest {
        val sources = object : FakeNowPlayingLyricsSources() {
            override suspend fun onlineRomanized(song: SongItem) = listOf(LyricEntry("online phonetic", 1_000L, 2_000L))
        }
        assertEquals("online phonetic", resolveBackgroundPhonetic(
            inputs(song), NowPlayingNeteaseFallback("", ""), sources
        ).single().text)
        assertTrue(
            resolveBackgroundPhonetic(
                inputs(localSong), NowPlayingNeteaseFallback("", ""), sources
            ).isEmpty())
    }

    @Test
    fun `blank translated sidecar suppresses online translation and empty refresh`() = runTest {
        val inputs = inputs(localSong, downloaded = ManagedDownloadStorage.DownloadedLyricsBundle(
            lyric = "[00:01.00]original", translatedLyric = "", romanizedLyric = null,
            hasOriginalSidecar = true,
            hasTranslatedSidecar = true
        ), managed = true)
        val raw = buildBackgroundRawLyrics(inputs, NowPlayingNeteaseFallback("", ""))
        assertTrue(resolveBackgroundTranslated(inputs, raw, FakeNowPlayingLyricsSources()).isEmpty())
        val state = buildBackgroundLyricsState(raw, emptyList(), emptyList(), emptyList())
        assertTrue(state.hasDisplayableContent())
        assertEquals("", state.rawTranslatedLyrics)
    }

    @Test
    fun `collapsed stored timeline is not published as raw lyric`() {
        val collapsed = "[00:00.00]one\n[00:00.00]two\n[00:00.00]three"
        val state = buildBackgroundLyricsState(
            NowPlayingBackgroundRawLyrics(collapsed, collapsed, null),
            listOf(LyricEntry("recovered", 1_000L, 2_000L)), emptyList(), emptyList()
        )
        assertNull(state.rawLyrics)
        assertNull(state.rawTranslatedLyrics)
        assertEquals("recovered", state.lyrics.single().text)
    }

    @Test
    fun `youtube lyric request waits for playback URL when raw lyric is absent`() = runTest {
        val youtube = song.copy(mediaUri = "https://music.youtube.com/watch?v=abcdefghijk")
        val inputs = inputs(youtube, mediaUrl = null)
        assertTrue(shouldDelayNowPlayingOnlineLyrics(inputs))
        val raw = NowPlayingBackgroundRawLyrics(null, null, null)
        assertTrue(resolveBackgroundOriginal(inputs, raw, FakeNowPlayingLyricsSources()).isEmpty())
        assertFalse(shouldDelayNowPlayingOnlineLyrics(inputs.copy(currentMediaUrl = "https://audio.example/stream")))
    }

    private fun inputs(
        song: SongItem,
        local: LocalLyricsScanMetadata? = null,
        downloaded: ManagedDownloadStorage.DownloadedLyricsBundle? = null,
        managed: Boolean = false,
        preferWordTimed: Boolean = true,
        mediaUrl: String? = null
    ) = NowPlayingLyricsBackgroundInputs(
        song,
        local,
        downloaded,
        managed,
        mediaUrl,
        preferWordTimed
    )
}
