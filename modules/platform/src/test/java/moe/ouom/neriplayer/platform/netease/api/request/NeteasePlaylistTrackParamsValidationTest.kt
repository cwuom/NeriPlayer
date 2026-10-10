package moe.ouom.neriplayer.platform.netease.api.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NeteasePlaylistTrackParamsValidationTest {

    @Test
    fun `playlist add params reject a missing playlist or empty song list`() {
        assertEquals(
            "playlistId must be positive",
            assertThrows(IllegalArgumentException::class.java) {
                buildNeteasePlaylistAddTracksParams(playlistId = 0L, songIds = listOf(1L))
            }.message
        )
        assertEquals(
            "songIds must not be empty",
            assertThrows(IllegalArgumentException::class.java) {
                buildNeteasePlaylistAddTracksParams(playlistId = 88L, songIds = emptyList())
            }.message
        )
        assertEquals(
            "songIds must contain a positive id",
            assertThrows(IllegalArgumentException::class.java) {
                buildNeteasePlaylistAddTracksParams(playlistId = 88L, songIds = listOf(0L, -3L))
            }.message
        )
    }

    @Test
    fun `playlist add params accept a single positive song`() {
        assertEquals(
            mapOf("op" to "add", "pid" to "7", "id" to "7", "tracks" to "5", "trackIds" to "[5]", "imme" to "true"),
            buildNeteasePlaylistAddTracksParams(playlistId = 7L, songIds = listOf(5L))
        )
    }
}
