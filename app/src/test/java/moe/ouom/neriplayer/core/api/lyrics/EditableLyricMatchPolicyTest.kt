package moe.ouom.neriplayer.core.api.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditableLyricMatchPolicyTest {

    @Test
    fun `youtube music editable lyric matching defaults to external lyric sources`() {
        assertEquals(
            setOf(
                EditableLyricMatchSource.LRCLIB,
                EditableLyricMatchSource.KUGOU,
                EditableLyricMatchSource.CLOUD_MUSIC,
                EditableLyricMatchSource.QQ_MUSIC
            ),
            defaultEditableLyricMatchSources(isYouTubeMusicTrack = true)
        )
    }

    @Test
    fun `non youtube editable lyric matching keeps the existing defaults`() {
        assertEquals(
            setOf(
                EditableLyricMatchSource.AMLL_TTML,
                EditableLyricMatchSource.CLOUD_MUSIC,
                EditableLyricMatchSource.KUGOU
            ),
            defaultEditableLyricMatchSources()
        )
    }

    @Test
    fun `rankEditableLyricMatches prefers same song with compatible duration`() {
        val request = EditableLyricMatchRequest(
            keyword = "爱你 陈芳语",
            trackName = "爱你",
            artistName = "陈芳语",
            albumName = "爱你",
            durationMs = 206_000L
        )

        val ranked = rankEditableLyricMatches(
            request = request,
            candidates = listOf(
                candidate(
                    id = "far",
                    durationMs = 250_000L
                ),
                candidate(
                    id = "match",
                    source = EditableLyricMatchSource.KUGOU,
                    durationMs = 207_000L,
                    sourceScore = 5
                ),
                candidate(
                    id = "unrelated",
                    title = "后来",
                    artist = "刘若英",
                    durationMs = 300_000L
                )
            )
        )

        assertEquals("match", ranked.first().candidate.id)
        assertFalse(ranked.any { it.candidate.id == "unrelated" })
    }

    @Test
    fun `rankEditableLyricMatches rejects same duration with unrelated identity`() {
        val request = EditableLyricMatchRequest(
            keyword = "Signal Artist One",
            trackName = "Signal",
            artistName = "Artist One",
            durationMs = 180_000L
        )

        val ranked = rankEditableLyricMatches(
            request = request,
            candidates = listOf(
                candidate(
                    id = "same-duration-wrong-song",
                    title = "Average",
                    artist = "Other Artist",
                    durationMs = 180_000L
                )
            )
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `isReliableLyricMatchIdentity accepts featured artist and matching version`() {
        assertTrue(
            isReliableLyricMatchIdentity(
                expectedTitle = "Signal (Remix)",
                expectedArtist = "Artist One feat. Guest",
                candidateTitle = "Signal - Remix",
                candidateArtist = "Artist One & Guest"
            )
        )
        assertFalse(
            isReliableLyricMatchIdentity(
                expectedTitle = "Signal",
                expectedArtist = "Artist One",
                candidateTitle = "Signal (Live)",
                candidateArtist = "Artist One"
            )
        )
    }

    @Test
    fun `rankEditableLyricMatches uses artist signal before lyric quality`() {
        val request = EditableLyricMatchRequest(
            keyword = "Signal Artist One",
            trackName = "Signal",
            artistName = "Artist One",
            durationMs = 180_000L,
            preferWordTimed = false
        )

        val ranked = rankEditableLyricMatches(
            request = request,
            candidates = listOf(
                candidate(
                    id = "word-timed-wrong-artist",
                    title = "Signal",
                    artist = "Other Artist",
                    durationMs = 180_000L,
                    format = EditableLyricFormat.TTML
                ),
                candidate(
                    id = "plain-right-artist",
                    title = "Signal",
                    artist = "Artist One",
                    durationMs = 180_000L,
                    format = EditableLyricFormat.LRC
                )
            )
        )

        assertEquals(listOf("plain-right-artist"), ranked.map { it.candidate.id })
    }

    @Test
    fun `rankEditableLyricMatches prefers word timed lyrics without hiding line timed lyrics`() {
        val request = EditableLyricMatchRequest(
            keyword = "爱你 陈芳语",
            trackName = "爱你",
            artistName = "陈芳语",
            durationMs = 206_000L
        )

        val ranked = rankEditableLyricMatches(
            request = request,
            candidates = listOf(
                candidate(
                    id = "plain-lrc",
                    durationMs = 206_000L,
                    lyrics = "[00:00.00]爱你"
                ),
                candidate(
                    id = "word-yrc",
                    durationMs = 206_000L,
                    lyrics = "[0,1000](0,500,0)爱(500,500,0)你",
                    format = EditableLyricFormat.YRC
                )
            )
        )

        assertEquals(listOf("word-yrc", "plain-lrc"), ranked.map { it.candidate.id })
    }

    @Test
    fun `rankEditableLyricMatches keeps line timed lyrics when no word timed result exists`() {
        val request = EditableLyricMatchRequest(
            keyword = "爱你 陈芳语",
            trackName = "爱你",
            artistName = "陈芳语",
            durationMs = 206_000L
        )

        val ranked = rankEditableLyricMatches(
            request = request,
            candidates = listOf(
                candidate(
                    id = "plain-lrc",
                    durationMs = 206_000L,
                    lyrics = "[00:00.00]爱你"
                )
            )
        )

        assertEquals(listOf("plain-lrc"), ranked.map { it.candidate.id })
    }

    @Test
    fun rankEditableLyricMatchesRejectsCollapsedTimedLyrics() {
        val request = EditableLyricMatchRequest(
            keyword = "Signal Artist One",
            trackName = "Signal",
            artistName = "Artist One",
            durationMs = 180_000L
        )

        val ranked = rankEditableLyricMatches(
            request = request,
            candidates = listOf(
                candidate(
                    id = "collapsed",
                    title = "Signal",
                    artist = "Artist One",
                    durationMs = 180_000L,
                    lyrics = """
                        [00:00.00]First line
                        [00:00.00]Second line
                        [00:00.00]Third line
                    """.trimIndent()
                ),
                candidate(
                    id = "timed",
                    title = "Signal",
                    artist = "Artist One",
                    durationMs = 180_000L,
                    lyrics = """
                        [00:00.00]First line
                        [00:10.00]Second line
                        [00:20.00]Third line
                    """.trimIndent()
                )
            )
        )

        assertEquals(listOf("timed"), ranked.map { it.candidate.id })
    }

    @Test
    fun `rankEditableLyricMatches accepts pinyin keyword signal when metadata is weak`() {
        val request = EditableLyricMatchRequest(
            keyword = "qingtian",
            trackName = "未知标题",
            artistName = "未知歌手",
            durationMs = 0L
        )

        val ranked = rankEditableLyricMatches(
            request = request,
            candidates = listOf(
                candidate(
                    id = "sunny-day",
                    title = "晴天",
                    artist = "周杰伦",
                    durationMs = 0L
                )
            )
        )

        assertEquals(listOf("sunny-day"), ranked.map { it.candidate.id })
    }

    @Test
    fun `scoreLyricMatchKeyword supports pinyin initials`() {
        val score = scoreLyricMatchKeyword(
            keyword = "qt",
            candidate = candidate(
                id = "sunny-day",
                title = "晴天",
                artist = "周杰伦",
                durationMs = 0L
            )
        )

        assertTrue(score > 0)
    }

    @Test
    fun `editableLyricMatchSearchQueries adds metadata fallbacks`() {
        val queries = editableLyricMatchSearchQueries(
            EditableLyricMatchRequest(
                keyword = "qt",
                trackName = "晴天",
                artistName = "周杰伦",
                durationMs = 265_000L
            )
        )

        assertEquals(listOf("qt", "晴天 周杰伦", "晴天"), queries)
    }

    private fun candidate(
        id: String,
        source: EditableLyricMatchSource = EditableLyricMatchSource.CLOUD_MUSIC,
        title: String = "爱你",
        artist: String = "陈芳语",
        durationMs: Long,
        format: EditableLyricFormat = EditableLyricFormat.YRC,
        sourceScore: Int = 0,
        lyrics: String = "[0,1000](0,500,0)爱(500,500,0)你"
    ): EditableLyricMatchCandidate {
        return EditableLyricMatchCandidate(
            id = id,
            source = source,
            title = title,
            artist = artist,
            album = "爱你",
            durationMs = durationMs,
            lyrics = lyrics,
            format = format,
            sourceScore = sourceScore
        )
    }
}
