package moe.ouom.neriplayer.core.player.service.car.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CarMediaIdsTest {
    @Test
    fun `playlist routes preserve full signed identifier range`() {
        listOf(Long.MIN_VALUE, -1001L, 0L, Long.MAX_VALUE).forEach { id ->
            val route = CarMediaIds.parse(CarMediaIds.playlist(id)) as CarMediaRoute.Directory
            assertEquals(CarDirectoryKind.PLAYLIST, route.kind)
            assertEquals(id, route.playlistId)
        }
    }

    @Test
    fun `search context safely encodes unicode slashes and URI looking query text`() {
        val query = "你好 / content://music 🎵"
        val id = CarMediaIds.search(query)
        val route = CarMediaIds.parse(id) as CarMediaRoute.Directory
        val song = CarMediaIds.parse(CarMediaIds.song(id, 42, "source")) as CarMediaRoute.Song

        assertEquals(query, route.query)
        assertEquals(CarDirectoryKind.SEARCH, route.kind)
        assertEquals(route, song.directory)
        assertEquals(42, song.index)
        assertTrue(song.digest.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `song route validates argument count numeric index digest and directory kind syntax`() {
        val queue = encode(CarMediaIds.QUEUE)
        val digest = "0".repeat(64)
        val invalid = listOf(
            "neri-car:v1/song/$queue/0", "neri-car:v1/song/$queue/0/$digest/extra",
            "neri-car:v1/song/$queue/nope/$digest", "neri-car:v1/song/$queue/-1/$digest",
            "neri-car:v1/song/$queue/99999999999999999999/$digest",
            "neri-car:v1/song/$queue/0/short", "neri-car:v1/song/$queue/0/${"G".repeat(64)}",
            "neri-car:v1/song/${encode("https://server/audio")}/0/$digest"
        )
        invalid.forEach { assertNull(it, CarMediaIds.parse(it)) }
    }

    @Test
    fun `page route rejects missing arguments invalid numbers and nested pages`() {
        val queue = encode(CarMediaIds.QUEUE)
        val invalid = listOf(
            "neri-car:v1/page/$queue/0", "neri-car:v1/page/$queue/0/100/extra",
            "neri-car:v1/page/$queue/nope/100", "neri-car:v1/page/$queue/0/nope",
            "neri-car:v1/page/$queue/-1/100", "neri-car:v1/page/$queue/100/99",
            CarMediaIds.page(CarMediaIds.page(CarMediaIds.QUEUE, 0, 100), 0, 10),
            CarMediaIds.page("https://server/audio", 0, 1)
        )
        invalid.forEach { assertNull(it, CarMediaIds.parse(it)) }
    }

    @Test
    fun `invalid encoded search and oversized query directories are rejected`() {
        listOf("neri-car:v1/search/", "neri-car:v1/search/${encode(" \t ")}",
            "neri-car:v1/search/${encode("x".repeat(121))}", "neri-car:v1/search/${encode("Song")}/extra")
            .forEach { assertNull(it, CarMediaIds.parse(it)) }
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))
}
