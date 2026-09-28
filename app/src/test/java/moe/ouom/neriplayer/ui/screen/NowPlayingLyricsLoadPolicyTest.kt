package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.media.LocalLyricsScanMetadata
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.ManagedLyricVariant
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildNowPlayingFastLyricsState
import moe.ouom.neriplayer.ui.screen.nowplaying.lyrics.buildNowPlayingImmediateLyricsState
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
    fun `netease match id wins and direct source id is used only for tagged song`() {
        assertNull(resolvePreferredNeteaseLyricSongId(null))
        assertNull(resolvePreferredNeteaseLyricSongId(song))
        assertEquals(123L, resolvePreferredNeteaseLyricSongId(song.copy(matchedSongId = "123")))
        assertEquals(61L,
            resolvePreferredNeteaseLyricSongId(song.copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC))
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
