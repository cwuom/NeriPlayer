package moe.ouom.neriplayer.ui.viewmodel.tab

import org.junit.Assert.assertEquals
import org.junit.Test

class NeteaseSearchSongParserTest {

    @Test
    fun `search parser preserves album id and netease source metadata`() {
        val raw = """
            {
              "code": 200,
              "result": {
                "songs": [
                  {
                    "id": 7,
                    "name": "Demo Song",
                    "dt": 1234,
                    "ar": [{ "id": 8, "name": "Demo Artist" }],
                    "al": {
                      "id": 99,
                      "name": "Demo Album",
                      "picUrl": "http://example.test/cover.jpg"
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val song = parseNeteaseSearchSongs(raw).single()

        assertEquals(99L, song.albumId)
        assertEquals("Demo Album", song.album)
        assertEquals("https://example.test/cover.jpg", song.coverUrl)
        assertEquals("netease", song.channelId)
        assertEquals("7", song.audioId)
    }
}
