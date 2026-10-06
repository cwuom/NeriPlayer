package moe.ouom.neriplayer.data.model.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SongDetailsTest {
    private val details = SongDetails(
        id = "1",
        songName = "name",
        singer = "singer",
        album = "album",
        coverUrl = "https://p1.music.126.net/cover.jpg",
        lyric = "[00:00.00]line",
        translatedLyric = "translation"
    )

    @Test
    fun `cover and translation are not part of the song identity`() {
        val sameSong = details.copy(coverUrl = null, translatedLyric = null)

        assertEquals(details, details)
        assertEquals(details, sameSong)
        assertEquals(details.hashCode(), sameSong.hashCode())
    }

    @Test
    fun `each identity field breaks equality`() {
        listOf(
            details.copy(id = "2"),
            details.copy(songName = "other"),
            details.copy(singer = "other"),
            details.copy(album = "other"),
            details.copy(lyric = null)
        ).forEach { changed -> assertNotEquals(details, changed) }
        assertFalse(details.equals(null))
        assertFalse(details.equals("1"))
    }
}
