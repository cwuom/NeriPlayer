package moe.ouom.neriplayer.lyrics.output

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import org.junit.Assert.assertEquals
import org.junit.Test

class LyriconPreferencesTest {
    @Test
    fun `resolved source selects its own default before the song user offset`() {
        val preferences = LyriconPreferences(true, LyricSourcePreference.Automatic, 100, 200, 300, 400, 500)
        val song = song(20)
        val offsets = mapOf(
            LyricSourcePreference.CloudMusic to 120L,
            LyricSourcePreference.QqMusic to 220L,
            LyricSourcePreference.Kugou to 320L,
            LyricSourcePreference.LrcLib to 420L,
            LyricSourcePreference.AmllTtml to 520L,
            LyricSourcePreference.Automatic to 120L,
        )
        offsets.forEach { (source, expected) -> assertEquals(expected, preferences.offsetFor(song, source)) }
        assertEquals(220L, preferences.offsetFor(song.copy(matchedLyricSource = MusicPlatform.QQ_MUSIC), null))
        assertEquals(120L, preferences.offsetFor(song, null))
    }

    @Test
    fun `effective offsets saturate instead of wrapping at long boundaries`() {
        val positive = LyriconPreferences(true, cloudMusicOffsetMs = Long.MAX_VALUE)
        val negative = LyriconPreferences(true, cloudMusicOffsetMs = Long.MIN_VALUE)
        assertEquals(Long.MAX_VALUE, positive.offsetFor(song(1), null))
        assertEquals(Long.MIN_VALUE, negative.offsetFor(song(-1), null))
        assertEquals(-1L, negative.offsetFor(song(Long.MAX_VALUE), null))
    }

    @Test
    fun `bili cc offset applies only when the caller marks the source`() {
        val preferences = LyriconPreferences(
            enabled = true,
            cloudMusicOffsetMs = 1_000L,
            biliCcSubtitleOffsetMs = 250L
        )
        val biliSong = SongItem(
            id = 1, name = "song", artist = "artist", album = "Bilibili|1|BV1",
            albumId = 0, durationMs = 10_000, coverUrl = null
        )

        assertEquals(1_000L, preferences.offsetFor(biliSong, null))
        assertEquals(
            250L,
            preferences.offsetFor(biliSong, null, isBiliCcSubtitleLyrics = true)
        )
    }

    private fun song(offset: Long) = SongItem(
        id = 1, name = "song", artist = "artist", album = "album", albumId = 0,
        durationMs = 10_000, coverUrl = null, userLyricOffsetMs = offset,
    )
}
