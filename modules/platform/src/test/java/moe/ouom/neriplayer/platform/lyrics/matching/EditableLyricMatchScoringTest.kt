package moe.ouom.neriplayer.platform.lyrics.matching

import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricFormat
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchCandidate
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchRequest
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditableLyricMatchScoringTest {

    @Test
    fun `source priority orders every editable lyric source`() {
        assertEquals(
            mapOf(
                EditableLyricMatchSource.KUGOU to 5,
                EditableLyricMatchSource.CLOUD_MUSIC to 4,
                EditableLyricMatchSource.QQ_MUSIC to 3,
                EditableLyricMatchSource.LRCLIB to 2,
                EditableLyricMatchSource.AMLL_TTML to 1,
                EditableLyricMatchSource.YOUTUBE_MUSIC to 0
            ),
            EditableLyricMatchSource.entries.associateWith(::editableLyricMatchSourcePriority)
        )
    }

    @Test
    fun `title score grades exact, prefix, containment and token overlap`() {
        assertEquals(0, scoreLyricMatchTitle("", "Signal"))
        assertEquals(0, scoreLyricMatchTitle("Signal", " ( ) "))
        assertEquals(80, scoreLyricMatchTitle("Signal", "SIGNAL"))
        assertEquals(68, scoreLyricMatchTitle("Signal", "Signal Remix"))
        assertEquals(62, scoreLyricMatchTitle("Signal Remix", "Signal"))
        assertEquals(52, scoreLyricMatchTitle("Signal", "The Signal Song"))
        assertEquals(52, scoreLyricMatchTitle("The Signal Song", "Signal"))
        assertEquals(15, scoreLyricMatchTitle("Blue Moon Night", "Blue Sky"))
        assertEquals(0, scoreLyricMatchTitle("Signal", "Echo"))
    }

    @Test
    fun `match signal needs title or keyword evidence and a compatible artist`() {
        val request = EditableLyricMatchRequest(keyword = "", trackName = "Signal", artistName = "Artist")

        assertTrue(hasLyricMatchSignal(request, candidate(id = "same", title = "Signal", artist = "Artist")))
        assertTrue(
            hasLyricMatchSignal(
                request.copy(keyword = "Echo"),
                candidate(id = "keyword", title = "Echo", artist = "")
            )
        )
        assertFalse(hasLyricMatchSignal(request, candidate(id = "unrelated", title = "Echo", artist = "")))
        assertFalse(
            hasLyricMatchSignal(request, candidate(id = "other-artist", title = "Signal", artist = "Someone Else"))
        )
    }

    @Test
    fun `album score rewards exact and partial album matches`() {
        val request = EditableLyricMatchRequest(
            keyword = "",
            trackName = "Signal",
            artistName = "Artist",
            albumName = "Night Drive",
            durationMs = 180_000L
        )
        val candidates = listOf(
            candidate(id = "exact", album = "night drive"),
            candidate(id = "deluxe", album = "Night Drive (Deluxe)"),
            candidate(id = "shorter", album = "Night"),
            candidate(id = "other", album = "Daylight"),
            candidate(id = "missing", album = null)
        )

        val scores = rankEditableLyricMatches(request, candidates)
            .associate { it.candidate.id to it.score }
        val withoutRequestedAlbum = rankEditableLyricMatches(request.copy(albumName = null), candidates)
            .map { it.score }
            .toSet()

        val base = scores.getValue("other")
        assertEquals(
            mapOf("exact" to 8, "deluxe" to 4, "shorter" to 4, "other" to 0, "missing" to 0),
            scores.mapValues { (_, score) -> score - base }
        )
        assertEquals(setOf(base), withoutRequestedAlbum)
    }

    private fun candidate(
        id: String,
        title: String = "Signal",
        artist: String = "Artist",
        album: String? = null
    ) = EditableLyricMatchCandidate(
        id = id,
        source = EditableLyricMatchSource.LRCLIB,
        title = title,
        artist = artist,
        album = album,
        durationMs = 180_000L,
        lyrics = "[00:01.00]First line\n[00:04.00]Second line",
        format = EditableLyricFormat.LRC
    )
}
