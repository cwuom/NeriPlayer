package moe.ouom.neriplayer.ui.viewmodel.playlist

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongItemLegacyCompatibilityTest {

    private val gson = Gson()

    @Test
    fun `copy still works when legacy gson payload misses netease artists`() {
        val song = gson.fromJson(
            """
            {
              "id": 1,
              "name": "Legacy song",
              "artist": "Legacy artist",
              "album": "local",
              "albumId": 0,
              "durationMs": 1234,
              "coverUrl": null,
              "mediaUri": "file:///tmp/legacy.mp3"
            }
            """.trimIndent(),
            SongItem::class.java
        )

        assertNull(song.neteaseArtists)

        val updatedSong = song.copy(userLyricOffsetMs = 120L)

        assertEquals(120L, updatedSong.userLyricOffsetMs)
        assertTrue(updatedSong.neteaseArtists.orEmpty().isEmpty())
    }
}
