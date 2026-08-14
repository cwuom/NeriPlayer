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
                EditableLyricMatchSource.KUGOU,
                EditableLyricMatchSource.CLOUD_MUSIC,
                EditableLyricMatchSource.QQ_MUSIC,
                EditableLyricMatchSource.LRCLIB
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
    fun `domestic lyric search converts traditional Chinese to simplified`() {
        assertEquals(
            "搁浅 周杰伦",
            toSimplifiedChineseForDomesticSearch("擱淺 周杰倫")
        )
    }

    @Test
    fun `rankEditableLyricMatches keeps a title alias with matching artist and duration`() {
        val ranked = rankEditableLyricMatches(
            request = EditableLyricMatchRequest(
                keyword = "擱淺 周杰倫",
                trackName = "擱淺",
                artistName = "周杰倫",
                durationMs = 248_000L,
                preferWordTimed = false
            ),
            candidates = listOf(
                candidate(
                    id = "alias",
                    title = "搁浅 (官方版)",
                    artist = "周杰伦",
                    durationMs = 249_000L,
                    format = EditableLyricFormat.LRC,
                    lyrics = "[00:01.00]可信歌词"
                )
            )
        )

        assertEquals(listOf("alias"), ranked.map { it.candidate.id })
        assertEquals(EditableLyricMatchConfidence.HIGH, ranked.single().confidence)
    }

    @Test
    fun `rankEditableLyricMatches rejects unrelated candidate even with matching duration`() {
        val ranked = rankEditableLyricMatches(
            request = EditableLyricMatchRequest(
                keyword = "擱淺 周杰倫",
                trackName = "擱淺",
                artistName = "周杰倫",
                durationMs = 248_000L,
                preferWordTimed = false
            ),
            candidates = listOf(
                candidate(
                    id = "average",
                    title = "平均",
                    artist = "其他歌手",
                    durationMs = 248_000L,
                    lyrics = "[00:01.00]不相关歌词"
                )
            )
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `lyric match signal keeps a platform result for low confidence display`() {
        val request = EditableLyricMatchRequest(
            keyword = "Signal Artist One",
            trackName = "Signal",
            artistName = "Artist One",
            durationMs = 180_000L
        )
        val candidate = candidate(
            id = "platform-alias",
            title = "Signal performance",
            artist = "",
            durationMs = 0L,
            sourceScore = 0
        )

        assertTrue(hasLyricMatchSignal(request, candidate))
    }

    @Test
    fun `plausible identity accepts a platform title alias with primary artist`() {
        assertTrue(
            isPlausibleLyricMatchIdentity(
                expectedTitle = "擱淺",
                expectedArtist = "周杰倫",
                candidateTitle = "搁浅 官方版",
                candidateArtist = "周杰伦",
                durationCompatible = true
            )
        )
        assertFalse(
            isPlausibleLyricMatchIdentity(
                expectedTitle = "擱淺",
                expectedArtist = "周杰倫",
                candidateTitle = "平均",
                candidateArtist = "其他歌手",
                durationCompatible = true
            )
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
    fun `isReliableLyricMatchIdentity keeps release variants distinct`() {
        assertTrue(
            isReliableLyricMatchIdentity(
                expectedTitle = "Signal (Remastered)",
                expectedArtist = "Artist One",
                candidateTitle = "Signal (Remaster)",
                candidateArtist = "Artist One"
            )
        )
        assertFalse(
            isReliableLyricMatchIdentity(
                expectedTitle = "Signal (Remastered)",
                expectedArtist = "Artist One",
                candidateTitle = "Signal",
                candidateArtist = "Artist One"
            )
        )
        assertFalse(
            isReliableLyricMatchIdentity(
                expectedTitle = "Signal (Radio Edit)",
                expectedArtist = "Artist One",
                candidateTitle = "Signal (Extended)",
                candidateArtist = "Artist One"
            )
        )
        assertFalse(
            isReliableLyricMatchIdentity(
                expectedTitle = "Signal (Explicit)",
                expectedArtist = "Artist One",
                candidateTitle = "Signal (Clean)",
                candidateArtist = "Artist One"
            )
        )
    }

    @Test
    fun `isReliableLyricMatchIdentity requires the expected primary artist`() {
        assertTrue(
            isReliableLyricMatchIdentity(
                expectedTitle = "Signal",
                expectedArtist = "Artist One",
                candidateTitle = "Signal",
                candidateArtist = "Artist One feat. Guest"
            )
        )
        assertTrue(
            isReliableLyricMatchIdentity(
                expectedTitle = "Signal",
                expectedArtist = "Artist One",
                candidateTitle = "Signal",
                candidateArtist = "Artist One and Guest"
            )
        )
        assertFalse(
            isReliableLyricMatchIdentity(
                expectedTitle = "Signal",
                expectedArtist = "Artist One",
                candidateTitle = "Signal",
                candidateArtist = "Guest"
            )
        )
        assertFalse(
            isReliableLyricMatchIdentity(
                expectedTitle = "Signal",
                expectedArtist = "Artist One feat. Guest",
                candidateTitle = "Signal",
                candidateArtist = "Guest"
            )
        )
    }

    @Test
    fun `isReliableLyricMatchIdentity rejects plain artist name suffixes`() {
        assertFalse(
            isReliableLyricMatchIdentity(
                expectedTitle = "Signal",
                expectedArtist = "Artist One",
                candidateTitle = "Signal",
                candidateArtist = "Artist One Tribute"
            )
        )
        assertFalse(
            isReliableLyricMatchIdentity(
                expectedTitle = "Signal",
                expectedArtist = "Artist One",
                candidateTitle = "Signal",
                candidateArtist = "Artist One Band"
            )
        )
        assertTrue(
            isReliableLyricMatchIdentity(
                expectedTitle = "Signal",
                expectedArtist = "Artist One",
                candidateTitle = "Signal",
                candidateArtist = "Artist One with Guest"
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
    fun `rankEditableLyricMatches lets score beat source priority within the same confidence`() {
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
                    id = "source-priority-lrc",
                    source = EditableLyricMatchSource.KUGOU,
                    durationMs = 206_000L,
                    lyrics = "[00:00.00]爱你",
                    format = EditableLyricFormat.LRC
                ),
                candidate(
                    id = "word-timed-ttml",
                    source = EditableLyricMatchSource.AMLL_TTML,
                    durationMs = 206_000L,
                    lyrics = """
                        <tt xmlns="http://www.w3.org/ns/ttml">
                            <body><div><p begin="00:01.000" end="00:02.000">
                                <span begin="00:01.000" end="00:01.500">爱</span>
                                <span begin="00:01.500" end="00:02.000">你</span>
                            </p></div></body>
                        </tt>
                    """.trimIndent(),
                    format = EditableLyricFormat.TTML
                )
            )
        )

        assertEquals(listOf("word-timed-ttml", "source-priority-lrc"), ranked.map { it.candidate.id })
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

    @Test
    fun `editableLyricMatchDomesticSearchQueries never sends traditional Chinese`() {
        val queries = editableLyricMatchDomesticSearchQueries(
            EditableLyricMatchRequest(
                keyword = "擱淺 周杰倫",
                trackName = "擱淺",
                artistName = "周杰倫"
            )
        )

        assertEquals(listOf("搁浅 周杰伦", "搁浅"), queries)
        assertTrue(queries.none { it.contains('擱') || it.contains('淺') || it.contains('傑') })
    }

    @Test
    fun `artist matching does not split band names that contain with or and`() {
        assertFalse(
            isReliableLyricMatchIdentity(
                expectedTitle = "Keeper",
                expectedArtist = "With Confidence",
                candidateTitle = "Keeper",
                candidateArtist = "Confidence"
            )
        )

        val ranked = rankEditableLyricMatches(
            request = EditableLyricMatchRequest(
                keyword = "Keeper With Confidence",
                trackName = "Keeper",
                artistName = "With Confidence",
                durationMs = 210_000L,
                preferWordTimed = false
            ),
            candidates = listOf(
                candidate(
                    id = "wrong-band",
                    title = "Keeper",
                    artist = "Confidence",
                    durationMs = 210_000L,
                    lyrics = "[00:01.00]Wrong artist"
                )
            )
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `lyric detail lookup keeps unknown durations and filters known incompatible durations`() {
        assertTrue(
            isLyricDetailLookupDurationAllowed(
                expectedDurationMs = 180_000L,
                candidateDurationMs = 0L
            )
        )
        assertTrue(
            isLyricDetailLookupDurationAllowed(
                expectedDurationMs = 180_000L,
                candidateDurationMs = 185_000L
            )
        )
        assertFalse(
            isLyricDetailLookupDurationAllowed(
                expectedDurationMs = 180_000L,
                candidateDurationMs = 250_000L
            )
        )
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
