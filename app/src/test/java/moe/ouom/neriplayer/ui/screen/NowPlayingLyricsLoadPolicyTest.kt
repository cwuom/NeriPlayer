package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.ManagedLyricVariant
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildNowPlayingFastLyricsState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildNowPlayingImmediateLyricsState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildNowPlayingInitialLyricsState
import moe.ouom.neriplayer.core.player.metadata.PreferredLyricSourceResult
import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.resolveManagedDownloadFastLyricText
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.resolvePreferredNeteaseLyricSongId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingLyricsLoadPolicyTest {
    private val song = SongItem(61L, "Song", "Artist", "Album", 1L, 60_000L, null)

    @Test
    fun `netease prefetch rejects ids from other platforms and unclassified matches`() {
        assertNull(resolvePreferredNeteaseLyricSongId(song.copy(
            matchedLyricSource = MusicPlatform.QQ_MUSIC, matchedSongId = "123")))
        assertNull(resolvePreferredNeteaseLyricSongId(song.copy(matchedSongId = "123")))
        assertNull(resolvePreferredNeteaseLyricSongId(song.copy(
            album = "Bilibili", matchedLyricSource = MusicPlatform.CLOUD_MUSIC)))
    }

    @Test
    fun `confirmed original preserves user collapsed content while absent variants retain preferred fallback`() {
        val collapsed = "[00:00.00]one\n[00:00.00]two\n[00:00.00]three"
        val preferred = PreferredLyricSourceResult(listOf(LyricEntry("cache", 1000, 2000)),
            listOf(LyricEntry("translation cache", 1000, 2000)), source = LyricSourcePreference.Kugou)
        val edited = song.copy(matchedLyric = collapsed, lyricSyncEdited = true, lyricSyncRevision = 20)
        val state = buildNowPlayingInitialLyricsState(edited, preferred)
        assertEquals(collapsed, state.rawLyrics)
        assertEquals(listOf("one", "two", "three"), state.lyrics.map { it.text })
        assertEquals("translation cache", state.translatedLyrics.single().text)
        for (marker in listOf(null, false)) {
            assertEquals("cache", buildNowPlayingInitialLyricsState(edited.copy(lyricSyncEdited = marker), preferred).lyrics.single().text)
        }
    }

    @Test
    fun `confirmed independent translation clear removes inline fallback from ordinary lyric rows`() {
        val preferred = PreferredLyricSourceResult(listOf(LyricEntry("original", 1000, 2000, translation = "old embedded")),
            source = LyricSourcePreference.Kugou)
        val edited = song.copy(lyricSyncEdited = true, lyricSyncRevision = 20, matchedTranslatedLyric = "")
        val cleared = buildNowPlayingInitialLyricsState(edited, preferred)
        assertEquals("original", cleared.lyrics.single().text)
        assertNull(cleared.lyrics.single().translation)
        assertTrue(cleared.plainLyrics.all { it.translation == null })
        assertEquals("", cleared.rawTranslatedLyrics)
        assertTrue(cleared.translatedLyrics.isEmpty())
        assertEquals("old embedded", buildNowPlayingInitialLyricsState(edited.copy(matchedTranslatedLyric = null), preferred).lyrics.single().translation)
    }

    @Test
    fun `netease match id wins and direct source id is used only for tagged song`() {
        assertNull(resolvePreferredNeteaseLyricSongId(null))
        assertNull(resolvePreferredNeteaseLyricSongId(song))
        assertEquals(123L, resolvePreferredNeteaseLyricSongId(song.copy(
            matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "123")))
        assertEquals(61L,
            resolvePreferredNeteaseLyricSongId(song.copy(album = "Netease", matchedLyricSource = MusicPlatform.CLOUD_MUSIC))
        )
        assertEquals(61L,
            resolvePreferredNeteaseLyricSongId(song.copy(mediaUri = "https://music.163.com/song"))
        )
        assertNull(resolvePreferredNeteaseLyricSongId(song.copy(id = 0L, matchedSongId = "-1")))
    }

    @Test
    fun `managed sidecar clear is authoritative and missing index falls back locally`() {
        val local = LocalLyricsScanMetadata(
            lyric = "local", translatedLyric = "local translation", romanizedLyric = "local phonetic",
            hasOriginalSidecar = true, hasTranslatedSidecar = true
        )
        val indexed = ManagedDownloadStorage.DownloadedLyricsBundle(
            lyric = "", translatedLyric = null, romanizedLyric = null,
            hasOriginalSidecar = true
        )
        assertEquals("", resolveManagedDownloadFastLyricText(
            local, indexed, "stored", ManagedLyricVariant.ORIGINAL
        )
        )
        assertEquals("local translation", resolveManagedDownloadFastLyricText(
            local, indexed, "stored translation", ManagedLyricVariant.TRANSLATED
        )
        )
        assertEquals("local phonetic", resolveManagedDownloadFastLyricText(
            local, indexed, "stored phonetic", ManagedLyricVariant.ROMANIZED
        )
        )
        assertEquals("stored", resolveManagedDownloadFastLyricText(
            null, null, "stored", ManagedLyricVariant.ORIGINAL
        )
        )
        assertNull(
            resolveManagedDownloadFastLyricText(
                null, null, null, ManagedLyricVariant.ORIGINAL
            )
        )
    }

    @Test
    fun `collapsed first frame is withheld while independent phonetic text remains`() {
        val collapsed = "[00:00.00]one\n[00:00.00]two\n[00:00.00]three"
        val state = buildNowPlayingFastLyricsState(
            collapsed, "[00:01.00]translation", "[00:01.00]phonetic"
        )
        assertNull(state.rawLyrics)
        assertTrue(state.lyrics.isEmpty())
        assertEquals("translation", state.translatedLyrics.single().text)
        assertEquals("phonetic", state.phoneticLyrics.single().text)
        assertFalse(state.plainTranslatedLyrics.isEmpty())
    }

    @Test
    fun `immediate state follows stored current then legacy lyrics`() {
        val state = buildNowPlayingImmediateLyricsState(
            song.copy(originalLyric = "[00:01.00]legacy", matchedLyric = "[00:02.00]matched")
        )
        assertEquals("matched", state.lyrics.single().text)
        assertTrue(buildNowPlayingImmediateLyricsState(null).lyrics.isEmpty())
    }
}
