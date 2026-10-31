package moe.ouom.neriplayer.core.player.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchCandidate
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchConfidence
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.lyrics.matching.RankedEditableLyricMatch
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectedSongMetadataLyricsTest {
    private val song = SongItem(7L, "Original", "Artist", "__local_files__", 0L, 120_000L, null,
        matchedLyric = "kept original", matchedTranslatedLyric = "kept translation",
        matchedRomanizedLyric = "", matchedSongId = "previous", matchedLyricSource = MusicPlatform.QQ_MUSIC,
        lyricSyncEdited = true, lyricSyncRevision = 10L)
    private val selected = SongSearchInfo("42", "Selected", "Singer", "02:00", MusicPlatform.CLOUD_MUSIC, null, null)
    private val details = selected.toBasicSongDetails()

    @Test
    fun `no selected lyric tracks or sources skips all lyric network calls`() = runTest {
        for (options in listOf(
            SongMetadataApplyOptions(originalLyrics = false, translatedLyrics = false, romanizedLyrics = false),
            SongMetadataApplyOptions(lyricSources = emptySet())
        )) {
            val result = fetchSelectedSongMetadataLyrics(song, selected, details, options,
                matchLyrics = { error("matcher must not run") },
                loadRomanized = { error("romanization must not run") })

            assertFalse(result.hasUpdates)
        }
    }

    @Test
    fun `local songs use selected sources directly and keep disabled tracks`() = runTest {
        val result = fetchSelectedSongMetadataLyrics(song, selected, details,
            SongMetadataApplyOptions(
                lyricSources = setOf(EditableLyricMatchSource.KUGOU),
                originalLyrics = false, translatedLyrics = true, romanizedLyrics = false,
                preferWordTimed = false
            ),
            matchLyrics = { request ->
                assertEquals(setOf(EditableLyricMatchSource.KUGOU), request.sources)
                assertEquals("Selected", request.trackName)
                assertFalse(request.preferWordTimed)
                listOf(match(EditableLyricMatchSource.KUGOU))
            }, loadRomanized = { error("disabled romanization must not run") })

        val updated = applySelectedSongMetadata(song, selected, details, null, result, true, 11L, false)

        assertEquals(song.matchedLyric, updated.matchedLyric)
        assertEquals("[00:01.00]translation", updated.matchedTranslatedLyric)
        assertEquals("", updated.matchedRomanizedLyric)
        assertEquals("Selected", updated.customName)
        assertNull(updated.matchedLyricSource)
        assertNull(updated.matchedSongId)
        assertEquals(11L, updated.lyricSyncRevision)
    }

    @Test
    fun `unselected sources cannot leak detail lyrics or romanization into the result`() = runTest {
        val result = fetchSelectedSongMetadataLyrics(song, selected,
            details.copy(lyric = "[00:01.00]unselected detail lyric"),
            SongMetadataApplyOptions(lyricSources = setOf(EditableLyricMatchSource.KUGOU)),
            matchLyrics = { listOf(match(EditableLyricMatchSource.CLOUD_MUSIC)) },
            loadRomanized = { error("unselected source must not run") })

        assertFalse(result.hasUpdates)
    }

    @Test
    fun `mismatched candidate identity or duration leaves stored tracks unchanged`() = runTest {
        val valid = match(EditableLyricMatchSource.KUGOU)
        for (invalid in listOf(
            valid.copy(candidate = valid.candidate.copy(title = "Other track")),
            valid.copy(candidate = valid.candidate.copy(durationMs = 240_000L))
        )) {
            val result = fetchSelectedSongMetadataLyrics(song, selected, details,
                SongMetadataApplyOptions(lyricSources = setOf(EditableLyricMatchSource.KUGOU)),
                matchLyrics = { listOf(invalid) }, loadRomanized = { emptyList() })
            val updated = applySelectedSongMetadata(song, selected, details, null, result, true, 11L, false)

            assertFalse(result.hasUpdates)
            assertEquals(song.matchedLyric, updated.matchedLyric)
            assertEquals(song.matchedTranslatedLyric, updated.matchedTranslatedLyric)
            assertEquals(song.matchedRomanizedLyric, updated.matchedRomanizedLyric)
            assertEquals(song.matchedSongId, updated.matchedSongId)
            assertEquals(song.lyricSyncRevision, updated.lyricSyncRevision)
        }
    }

    @Test
    fun `word timed selected lyrics retain their syllable timing`() = runTest {
        val candidate = match(EditableLyricMatchSource.KUGOU).let {
            it.copy(candidate = it.candidate.copy(lyrics = "[1000,2000](1000,1000,0)hello(2000,1000,0) world"))
        }
        val result = fetchSelectedSongMetadataLyrics(song, selected, details,
            SongMetadataApplyOptions(lyricSources = setOf(EditableLyricMatchSource.KUGOU), romanizedLyrics = false),
            matchLyrics = { listOf(candidate) }, loadRomanized = { emptyList() })

        assertTrue(requireNotNull(result.original).contains("(1000,1000,0)hello"))
        assertTrue(requireNotNull(result.original).contains("(2000,1000,0) world"))
    }

    @Test
    fun `lyric request cancellation propagates before a prepared result exists`() = runTest {
        val cancellation = CancellationException("cancelled selected lyrics")

        val failure = runCatching {
            fetchSelectedSongMetadataLyrics(song, selected, details, SongMetadataApplyOptions(),
                matchLyrics = { throw cancellation }, loadRomanized = { error("must not run") })
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(cancellation.message, failure?.message)
    }

    private fun match(source: EditableLyricMatchSource) = RankedEditableLyricMatch(
        candidate = EditableLyricMatchCandidate(
            id = "42", source = source, title = "Selected", artist = "Singer", durationMs = 120_000L,
            lyrics = "[00:01.00]new original", translatedLyrics = "[00:01.00]translation"
        ),
        score = 100, durationDeltaMs = 0L, confidence = EditableLyricMatchConfidence.HIGH
    )
}
