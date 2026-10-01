package moe.ouom.neriplayer.platform.netease.mapping

import moe.ouom.neriplayer.ui.viewmodel.tab.PlaylistSummary
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteasePlaylistMappingTest {
    @Test
    fun missingOrEmptyPlaylistArraysReturnEmptyLists() {
        assertTrue(parseNeteaseSearchPlaylists(JSONObject()).isEmpty())
        assertTrue(parseNeteaseSearchPlaylists(JSONObject("""{"playlists":null}""")).isEmpty())
        assertTrue(parseNeteaseSearchPlaylists(JSONObject("""{"playlists":[]}""")).isEmpty())
    }

    @Test
    fun mixedPlaylistMembersKeepValidOrderAndDoNotDeduplicate() {
        val result = JSONObject(
            """{"playlists":[null,7,"text",[],
                {"id":0,"name":"invalid"},{"id":-1,"name":"negative"},{"id":9,"name":" "},
                {"id":2,"name":"second"},{"id":1,"name":"first"},{"id":2,"name":" repeated "}
            ]}"""
        )

        val playlists = parseNeteaseSearchPlaylists(result)

        assertEquals(listOf(2L, 1L, 2L), playlists.map { it.id })
        assertEquals(listOf("second", "first", " repeated "), playlists.map { it.name })
        assertEquals(PlaylistSummary(2L, "second", "", 0L, 0), playlists.first())
    }

    @Test
    fun coverFallbackAndCountersKeepTheirOriginalMapping() {
        val result = JSONObject(
            """{"playlists":[
                {"id":1,"name":"primary","coverImgUrl":"http://primary","picUrl":"http://fallback",
                    "playCount":12345,"trackCount":20},
                {"id":2,"name":"fallback","coverImgUrl":"","picUrl":"http://fallback"}
            ]}"""
        )

        assertEquals(
            listOf(
                PlaylistSummary(1L, "primary", "https://primary", 12345L, 20),
                PlaylistSummary(2L, "fallback", "https://fallback", 0L, 0)
            ),
            parseNeteaseSearchPlaylists(result)
        )
    }
}
