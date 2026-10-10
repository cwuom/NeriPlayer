package moe.ouom.neriplayer.ui.viewmodel.tab

import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseHomeResponseShapeTest {
    @Test
    fun `song lists are read from every supported response shape`() {
        val shapes = listOf(
            """{"code":200,"data":{"dailySongs":[$SONG]}}""",
            """{"code":200,"data":{"songs":[$SONG]}}""",
            """{"code":200,"data":[$SONG]}""",
            """{"code":200,"result":[{"song":$SONG}]}""",
            """{"code":200,"songs":[$SONG]}""",
            """{"code":200,"playlist":{"tracks":[$SONG]}}"""
        )

        shapes.forEach { raw ->
            assertEquals(raw, listOf(7L), parseNeteaseHomeSongs(raw).map(SongItem::id))
        }
    }

    @Test
    fun `responses without a song list yield no songs`() {
        assertTrue(parseNeteaseHomeSongs("""{"code":200}""").isEmpty())
        assertTrue(parseNeteaseHomeSongs("""{"code":200,"data":{}}""").isEmpty())
        assertTrue(parseNeteaseHomeSongs("""{"code":200,"playlist":{}}""").isEmpty())
    }

    @Test
    fun `song parsing skips malformed rows and stops at the limit`() {
        val raw = """{"code":200,"songs":[
            "bad",
            {"id":0,"name":"Missing id"},
            {"id":5,"name":" "},
            {"id":1,"name":"One","album":{"id":3,"name":"Album","picUrl":"","picUrl_str":"http://p.music/a.jpg"},"duration":1200},
            {"id":2,"name":"Two"},
            {"id":3,"name":"Three"}
        ]}"""

        val songs = parseNeteaseHomeSongs(raw, limit = 2)

        assertEquals(listOf(1L, 2L), songs.map(SongItem::id))
        assertEquals("Album", songs.first().album)
        assertEquals(3L, songs.first().albumId)
        assertEquals(1200L, songs.first().durationMs)
        assertEquals("https://p.music/a.jpg", songs.first().coverUrl)
        assertEquals("", songs[1].album)
        assertEquals(null, songs[1].coverUrl)
    }

    @Test
    fun `non success codes are reported as API errors`() {
        val songError = assertThrows(ApiCodeException::class.java) {
            parseNeteaseHomeSongs("""{"code":301}""")
        }
        val playlistError = assertThrows(ApiCodeException::class.java) {
            parseNeteaseHomePlaylists("""{"msg":"missing code"}""")
        }

        assertEquals(301, songError.code)
        assertEquals(-1, playlistError.code)
    }

    @Test
    fun `playlist lists are read from every supported response shape`() {
        val shapes = listOf(
            """{"code":200,"result":[$PLAYLIST]}""",
            """{"code":200,"recommend":[$PLAYLIST]}""",
            """{"code":200,"playlists":[$PLAYLIST]}""",
            """{"code":200,"data":{"playlists":[$PLAYLIST]}}""",
            """{"code":200,"data":{"list":[$PLAYLIST]}}"""
        )

        shapes.forEach { raw ->
            assertEquals(raw, listOf(42L), parseNeteaseHomePlaylists(raw).map(PlaylistSummary::id))
        }
        assertTrue(parseNeteaseHomePlaylists("""{"code":200}""").isEmpty())
        assertTrue(parseNeteaseHomePlaylists("""{"code":200,"data":{}}""").isEmpty())
    }

    @Test
    fun `playlist parsing skips malformed rows uses cover fallbacks and stops at the limit`() {
        val raw = """{"code":200,"playlists":[
            1,
            {"id":0,"name":"Missing id"},
            {"id":4,"name":""},
            {"id":10,"name":"Cover image","coverImgUrl":"http://p.music/cover.jpg","playcount":9,"songCount":3},
            {"id":11,"name":"Cover url","picUrl":" ","coverUrl":"https://p.music/url.jpg","playCount":5,"trackCount":2},
            {"id":12,"name":"Over limit"}
        ]}"""

        val playlists = parseNeteaseHomePlaylists(raw, limit = 2)

        assertEquals(
            listOf(
                PlaylistSummary(10L, "Cover image", "https://p.music/cover.jpg", 9L, 3),
                PlaylistSummary(11L, "Cover url", "https://p.music/url.jpg", 5L, 2)
            ),
            playlists
        )
    }

    @Test
    fun `appending songs deduplicates by audio id or a channel id name fallback`() {
        val current = listOf(
            song(id = 1L, audioId = "a1"),
            song(id = 2L, audioId = null)
        )
        val next = listOf(
            song(id = 99L, audioId = "a1"),
            song(id = 2L, audioId = " "),
            song(id = 3L, audioId = null),
            song(id = 4L, audioId = "a4")
        )

        val merged = appendUniqueNeteaseHomeSongs(current, next, limit = 3)

        assertEquals(listOf(1L, 2L, 3L), merged.map(SongItem::id))
        assertTrue(appendUniqueNeteaseHomeSongs(current, next, limit = 0).isEmpty())
    }

    private fun song(id: Long, audioId: String?) = SongItem(
        id = id,
        name = "Song $id",
        artist = "Artist",
        album = "",
        albumId = 0L,
        durationMs = 0L,
        coverUrl = null,
        channelId = "netease",
        audioId = audioId
    )

    private companion object {
        const val SONG = """{"id":7,"name":"Seven","ar":[{"id":1,"name":"Singer"}],"al":{"id":8,"name":"LP","picUrl":"http://p.music/7.jpg"},"dt":3000}"""
        const val PLAYLIST = """{"id":42,"name":"Mix","picUrl":"http://p.music/mix.jpg","playCount":10,"trackCount":20}"""
    }
}
