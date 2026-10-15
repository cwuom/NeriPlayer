package moe.ouom.neriplayer.platform.lyrics.matching

import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricFormat
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchCandidate
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchConfidence
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchRequest
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricTimelineFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricMatchSupplementalSignalsTest {

    @Test
    fun `timeline end uses latest timed lyric line and ignores id tags`() {
        val lyric = """
            [ti:Signal]
            [ar:Artist]
            [00:01.00]First
            [03:10.50][00:20.00]Chorus
            [03:59.00]
        """.trimIndent()

        assertEquals(190_500L, lyricTimelineEndMs(lyric))
        assertNull(lyricTimelineEndMs("[ti:Only tags]\nplain text"))
    }

    @Test
    fun `timeline end keeps the maximum when later lines start earlier`() {
        assertEquals(
            200_000L,
            lyricTimelineEndMs("[03:20.00]Outro first\n[00:05.00]Intro later\n[01:00.00]Middle")
        )
    }

    @Test
    fun `timeline end counts seconds above 59 the way the playback parser does`() {
        assertEquals(159_900L, lyricTimelineEndMs("[00:10.00]Start\n[001:99.9]Late line"))
        assertEquals(
            EditableLyricTimelineFit.OVERFLOWS,
            resolveLyricTimelineFit(100_000L, "[00:10.00]Start\n[001:99.9]Late line", EditableLyricFormat.LRC)
        )
    }

    @Test
    fun `timeline end reads yrc line start plus duration`() {
        val lyric = "[1000,2000](1000,500,0)Hello\n[200000,4500](200000,4500,0)Bye"

        assertEquals(204_500L, lyricTimelineEndMs(lyric))
    }

    @Test
    fun `timeline fit flags lyrics that run past song duration plus tolerance`() {
        val longLyric = "[00:01.00]Intro\n[04:30.00]Outro"

        assertEquals(
            EditableLyricTimelineFit.OVERFLOWS,
            resolveLyricTimelineFit(200_000L, longLyric, EditableLyricFormat.LRC)
        )
        assertEquals(
            EditableLyricTimelineFit.FITS,
            resolveLyricTimelineFit(275_000L, longLyric, EditableLyricFormat.LRC)
        )
        // 250s 的歌按 8% 容差允许最后一行落在 270s 以内
        assertEquals(
            EditableLyricTimelineFit.FITS,
            resolveLyricTimelineFit(250_000L, longLyric, EditableLyricFormat.LRC)
        )
    }

    @Test
    fun `timeline fit is unknown without duration, for plain or ttml lyrics and for short timelines`() {
        val lyric = "[00:01.00]Intro\n[09:00.00]Outro"

        assertEquals(EditableLyricTimelineFit.UNKNOWN, resolveLyricTimelineFit(0L, lyric, EditableLyricFormat.LRC))
        assertEquals(
            EditableLyricTimelineFit.UNKNOWN,
            resolveLyricTimelineFit(100_000L, lyric, EditableLyricFormat.PLAIN)
        )
        assertEquals(
            EditableLyricTimelineFit.UNKNOWN,
            resolveLyricTimelineFit(100_000L, lyric, EditableLyricFormat.TTML)
        )
        assertEquals(
            EditableLyricTimelineFit.UNKNOWN,
            resolveLyricTimelineFit(600_000L, "[00:01.00]a\n[00:30.00]b", EditableLyricFormat.LRC)
        )
    }

    @Test
    fun `live markers are detected in latin and cjk titles and albums`() {
        assertTrue(isLiveLyricVersion("Signal (Live)"))
        assertTrue(isLiveLyricVersion("晴天 现场版"))
        assertTrue(isLiveLyricVersion("晴天", album = "地表最强世界巡回演唱会"))
        assertTrue(isLiveLyricVersion("Song - ライブ"))
        assertFalse(isLiveLyricVersion("Alive"))
        assertFalse(isLiveLyricVersion("Deliver"))
        assertFalse(isLiveLyricVersion("晴天"))
    }

    @Test
    fun `version preference favours matching live state and studio by default`() {
        assertTrue(scoreLyricVersionPreference("晴天 (Live)", "晴天 现场", null) > 0)
        assertTrue(scoreLyricVersionPreference("晴天 (Live)", "晴天", null) < 0)
        assertTrue(scoreLyricVersionPreference("晴天", "晴天 (Live)", null) < 0)
        assertTrue(
            scoreLyricVersionPreference("晴天", "晴天 (Live)", null) <
                scoreLyricVersionPreference("晴天 (Live)", "晴天", null)
        )
        assertEquals(0, scoreLyricVersionPreference("晴天", "晴天", "叶惠美"))
    }

    @Test
    fun `lrc id tags are parsed from the header only`() {
        val tags = parseLrcIdTags("[ti: Signal ]\n[AR:Artist]\n[al:Night Drive]\n[00:01.00]line\n[ar:Late]")

        assertEquals(LrcIdTags(title = "Signal", artist = "Artist", album = "Night Drive"), tags)
        assertEquals(LrcIdTags(), parseLrcIdTags("[00:01.00]line"))
        assertEquals(LrcIdTags(artist = "Real"), parseLrcIdTags("[ar: ]\n[ar:Real]"))
    }

    @Test
    fun `partial id tag similarity is neutral and album mismatch is never penalised`() {
        val request = request(trackName = "Signal Fire", artistName = "Artist One", albumName = "Night Drive")

        assertEquals(0, scoreLrcIdTags(request, LrcIdTags(title = "Signal Light", artist = "Artist Two")))
        assertEquals(0, scoreLrcIdTags(request, LrcIdTags(album = "Daylight")))
        assertEquals(6, scoreLrcIdTags(request, LrcIdTags(album = "Night Drive (Deluxe)")))
        assertEquals(0, scoreLrcIdTags(request.copy(albumName = null), LrcIdTags(album = "Night Drive")))
        assertEquals(0, scoreLrcIdTags(request.copy(albumName = "()"), LrcIdTags(album = "Night Drive")))
        assertEquals(0, scoreLrcIdTags(request.copy(trackName = " "), LrcIdTags(title = "Signal Fire")))
    }

    @Test
    fun `lrc id tags add weight when present and skip when missing or placeholder`() {
        val request = request(albumName = "Night Drive")

        assertEquals(22, scoreLrcIdTags(request, LrcIdTags("Signal", "Artist", "Night Drive")))
        assertEquals(-20, scoreLrcIdTags(request, LrcIdTags("Echo", "Someone", null)))
        assertEquals(0, scoreLrcIdTags(request, LrcIdTags()))
        assertEquals(
            0,
            scoreLrcIdTags(request.copy(artistName = "未知歌手"), LrcIdTags(artist = "Someone"))
        )
    }

    @Test
    fun `ranking demotes overflowing timeline to low confidence below a fitting match`() {
        val request = request(durationMs = 200_000L)
        val overflow = candidate(id = "extended", lyrics = "[00:01.00]a\n[05:30.00]b")
        val fitting = candidate(id = "fit", lyrics = "[00:01.00]a\n[03:10.00]b")

        val ranked = rankEditableLyricMatches(request, listOf(overflow, fitting))

        assertEquals("fit", ranked.first().candidate.id)
        assertEquals(EditableLyricMatchConfidence.HIGH, ranked.first().confidence)
        val overflowRanked = ranked.firstOrNull { it.candidate.id == "extended" }
        if (overflowRanked != null) {
            assertEquals(EditableLyricMatchConfidence.LOW, overflowRanked.confidence)
        }
    }

    @Test
    fun `ranking prefers studio lyrics for a studio title and live lyrics for a live title`() {
        val studio = candidate(id = "studio", title = "Signal")
        val live = candidate(id = "live", title = "Signal (现场)")

        val studioRanked = rankEditableLyricMatches(request(trackName = "Signal"), listOf(live, studio))
        val liveRanked = rankEditableLyricMatches(request(trackName = "Signal (Live)"), listOf(studio, live))

        assertEquals("studio", studioRanked.first().candidate.id)
        assertEquals("live", liveRanked.first().candidate.id)
    }

    private fun request(
        trackName: String = "Signal",
        artistName: String = "Artist",
        albumName: String? = null,
        durationMs: Long = 200_000L
    ) = EditableLyricMatchRequest(
        keyword = "$trackName $artistName",
        trackName = trackName,
        artistName = artistName,
        albumName = albumName,
        durationMs = durationMs,
        preferWordTimed = false
    )

    private fun candidate(
        id: String,
        title: String = "Signal",
        artist: String = "Artist",
        lyrics: String = "[00:01.00]First line\n[03:00.00]Last line"
    ) = EditableLyricMatchCandidate(
        id = id,
        source = EditableLyricMatchSource.CLOUD_MUSIC,
        title = title,
        artist = artist,
        durationMs = 0L,
        lyrics = lyrics,
        format = EditableLyricFormat.LRC
    )
}
