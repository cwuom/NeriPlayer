package moe.ouom.neriplayer.data.local.playlist.artist

import moe.ouom.neriplayer.data.model.NeteaseArtistSummary
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalArtistNamePrecedenceTest {
    @Test
    fun `custom artists win over structured and raw artist names`() {
        val custom = song(
            id = 1,
            artist = "Raw",
            customArtist = "Custom A / Custom B",
            neteaseArtists = listOf(NeteaseArtistSummary(10, "Structured"))
        )

        assertEquals(listOf("Custom A", "Custom B"), artistNames(custom))
    }

    @Test
    fun `blank custom artists fall back to distinct non-blank structured artists`() {
        val structured = song(
            id = 2,
            artist = "Raw",
            customArtist = "  ",
            neteaseArtists = listOf(
                NeteaseArtistSummary(11, " Net "),
                NeteaseArtistSummary(12, " "),
                NeteaseArtistSummary(13, "NET")
            )
        )

        assertEquals(listOf("Net"), artistNames(structured))
    }

    @Test
    fun `songs without structured artists use their raw artist text`() {
        val raw = song(id = 3, artist = "Plain", customArtist = null, neteaseArtists = null)

        assertEquals(listOf("Plain"), artistNames(raw))
    }

    private fun artistNames(song: SongItem): List<String> =
        buildLocalArtistSummaries(listOf(song), unknownArtist = "Unknown").map { it.name }.sorted()

    private fun song(
        id: Long,
        artist: String,
        customArtist: String?,
        neteaseArtists: List<NeteaseArtistSummary>?
    ) = SongItem(
        id = id,
        name = "Song $id",
        artist = artist,
        album = "Album",
        albumId = 0,
        durationMs = 0,
        coverUrl = null,
        customArtist = customArtist,
        neteaseArtists = neteaseArtists
    )
}
