package moe.ouom.neriplayer.data.local.playlist.model

import moe.ouom.neriplayer.ui.viewmodel.artist.NeteaseArtistSummary
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalArtistSummaryTest {

    @Test
    fun `slash separated duet belongs to each local artist`() {
        val duet = song(
            id = 1L,
            name = "花的共用世界",
            artist = "yihuik苡慧 / 吴炳文"
        )

        val summaries = buildLocalArtistSummaries(
            songs = listOf(duet),
            unknownArtist = "Unknown Artist"
        ).associateBy { artist -> artist.name }

        assertTrue("yihuik苡慧" in summaries)
        assertTrue("吴炳文" in summaries)
        assertFalse("yihuik苡慧 / 吴炳文" in summaries)
        assertEquals(listOf(duet), summaries.getValue("yihuik苡慧").songs)
        assertEquals(listOf(duet), summaries.getValue("吴炳文").songs)
    }

    @Test
    fun `compact slash artist name is not split`() {
        assertEquals(
            listOf("AC/DC"),
            splitLocalArtistNames("AC/DC", unknownArtist = "Unknown Artist")
        )
    }

    @Test
    fun `compact slash collaboration is split into every artist`() {
        assertEquals(
            listOf("尹美莱", "Tiger JK", "Bizzy"),
            splitLocalArtistNames("尹美莱/Tiger JK/Bizzy", unknownArtist = "Unknown Artist")
        )
    }

    @Test
    fun `mixed spaced and compact slash collaboration is fully split`() {
        assertEquals(
            listOf("尹美莱", "Tiger JK", "Bizzy"),
            splitLocalArtistNames("尹美莱 / Tiger JK/Bizzy", unknownArtist = "Unknown Artist")
        )
    }

    @Test
    fun `structured netease artists are used before raw artist text`() {
        val duet = song(
            id = 2L,
            name = "是多遗憾",
            artist = "吴俊佑 / 庄淇玟(29#)",
            neteaseArtists = listOf(
                NeteaseArtistSummary(id = 100L, name = "吴俊佑"),
                NeteaseArtistSummary(id = 101L, name = "庄淇玟(29#)")
            )
        )

        val names = buildLocalArtistSummaries(
            songs = listOf(duet),
            unknownArtist = "Unknown Artist"
        ).map { artist -> artist.name }.toSet()

        assertEquals(setOf("吴俊佑", "庄淇玟(29#)"), names)
    }

    @Test
    fun `blank artist falls back to unknown artist`() {
        assertEquals(
            listOf("Unknown Artist"),
            splitLocalArtistNames("  ", unknownArtist = "Unknown Artist")
        )
    }

    private fun song(
        id: Long,
        name: String,
        artist: String,
        neteaseArtists: List<NeteaseArtistSummary>? = emptyList()
    ): SongItem {
        return SongItem(
            id = id,
            name = name,
            artist = artist,
            album = "album",
            albumId = 1L,
            durationMs = 0L,
            coverUrl = null,
            neteaseArtists = neteaseArtists,
            addedAt = id
        )
    }
}
