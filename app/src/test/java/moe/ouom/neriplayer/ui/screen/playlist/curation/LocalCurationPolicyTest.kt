package moe.ouom.neriplayer.ui.screen.playlist.curation

import moe.ouom.neriplayer.data.local.media.LocalMediaMetadataWriteOutcome
import moe.ouom.neriplayer.data.local.media.curation.CommittedEmbeddedCover
import moe.ouom.neriplayer.data.local.media.curation.LocalEmbeddedAssetState
import moe.ouom.neriplayer.data.local.media.curation.PreparedEmbeddedCover
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchCandidate
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchConfidence
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchSource
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricTimelineFit
import moe.ouom.neriplayer.data.model.lyrics.matching.RankedEditableLyricMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCurationPolicyTest {

    @Test
    fun `another folder grant is requested only while the pending songs keep shrinking`() {
        assertTrue(folderGrantMadeProgress(Int.MAX_VALUE, 5))
        assertTrue(folderGrantMadeProgress(5, 2))
        assertFalse(folderGrantMadeProgress(2, 2))
        assertFalse(folderGrantMadeProgress(2, 3))
    }

    @Test
    fun `only covers created in this run and adopted by no song are orphans`() {
        val usage = CommittedCoverUsage()
        usage.committed(CommittedEmbeddedCover("new-failed", newlyCreated = true))
        usage.committed(CommittedEmbeddedCover("shared", newlyCreated = true))
        usage.committed(CommittedEmbeddedCover("shared", newlyCreated = false))
        usage.committed(CommittedEmbeddedCover("pre-existing", newlyCreated = false))
        usage.adopted("shared")

        assertEquals(setOf("new-failed"), usage.orphans())
        usage.reset()
        assertTrue(usage.orphans().isEmpty())
    }

    @Test
    fun `fill missing embeds existing lyrics before matching online`() {
        val empty = assets()

        assertEquals(
            LocalLyricWork.EMBED_EXISTING,
            resolveLocalLyricWork(LocalCurationLyricMode.FILL_MISSING, empty, hasCurrentLyrics = true)
        )
        assertEquals(
            LocalLyricWork.MATCH_ONLINE,
            resolveLocalLyricWork(LocalCurationLyricMode.FILL_MISSING, empty, hasCurrentLyrics = false)
        )
        assertEquals(
            LocalLyricWork.NONE,
            resolveLocalLyricWork(LocalCurationLyricMode.FILL_MISSING, assets(lyricBytes = 10), true)
        )
    }

    @Test
    fun `rematch always goes online and reembed never does`() {
        val embedded = assets(lyricBytes = 10)

        assertEquals(
            LocalLyricWork.MATCH_ONLINE,
            resolveLocalLyricWork(LocalCurationLyricMode.REMATCH_ALL, embedded, hasCurrentLyrics = true)
        )
        assertEquals(
            LocalLyricWork.EMBED_EXISTING,
            resolveLocalLyricWork(LocalCurationLyricMode.REEMBED_CURRENT, embedded, hasCurrentLyrics = true)
        )
        assertEquals(
            LocalLyricWork.NONE,
            resolveLocalLyricWork(LocalCurationLyricMode.REEMBED_CURRENT, embedded, hasCurrentLyrics = false)
        )
        assertEquals(
            LocalLyricWork.NONE,
            resolveLocalLyricWork(LocalCurationLyricMode.SKIP, assets(), hasCurrentLyrics = false)
        )
    }

    @Test
    fun `cover need follows mode and existing artwork`() {
        assertFalse(needsLocalCover(LocalCurationCoverMode.SKIP, assets()))
        assertTrue(needsLocalCover(LocalCurationCoverMode.FILL_MISSING, assets()))
        assertFalse(needsLocalCover(LocalCurationCoverMode.FILL_MISSING, assets(coverBytes = 100)))
        assertTrue(needsLocalCover(LocalCurationCoverMode.REPLACE_ALL, assets(coverBytes = 100)))
    }

    @Test
    fun `lyric pick skips overflowing timelines`() {
        val overflow = ranked("long", timelineFit = EditableLyricTimelineFit.OVERFLOWS)
        val fit = ranked("fit", confidence = EditableLyricMatchConfidence.MEDIUM)

        assertEquals("fit", pickLocalLyricMatch(listOf(overflow, fit))?.candidate?.id)
        assertNull(pickLocalLyricMatch(listOf(overflow)))
    }

    @Test
    fun `alternatives exclude the chosen lyric, duplicates and overflowing timelines`() {
        val chosen = ranked("chosen")
        val duplicateOfChosen = ranked("dup")
        val overflow = ranked("overflow", timelineFit = EditableLyricTimelineFit.OVERFLOWS).let {
            it.copy(candidate = it.candidate.copy(lyrics = "[00:01.00]other"))
        }
        val distinct = (1..5).map { index ->
            ranked("alt$index").let { it.copy(candidate = it.candidate.copy(lyrics = "[00:01.00]alt $index")) }
        }

        val alternatives = pickLocalLyricAlternatives(listOf(chosen, duplicateOfChosen, overflow) + distinct, chosen)

        assertEquals(listOf("alt1", "alt2", "alt3"), alternatives.map { it.candidate.id })
        assertTrue(pickLocalLyricAlternatives(emptyList(), null).isEmpty())
    }

    @Test
    fun `parallelism defaults to ten and stays within the supported range`() {
        assertEquals(10, LocalCurationOptions().searchParallelism)
        assertEquals(10, LocalCurationOptions().writeParallelism)
        assertEquals(1, clampLocalCurationParallelism(0))
        assertEquals(16, clampLocalCurationParallelism(64))
        assertEquals(7, clampLocalCurationParallelism(7))
    }

    @Test
    fun `write outcomes map to the precise failure kind`() {
        val success = LocalMediaMetadataWriteOutcome.SUCCESS
        val failed = LocalMediaMetadataWriteOutcome.FAILED

        assertNull(localCurationFailureFor(success, writesLyrics = true, writesCover = true))
        assertEquals(LocalCurationFailure.FILE_WRITE, localCurationFailureFor(failed, writesLyrics = true, writesCover = true))
        assertEquals(LocalCurationFailure.LYRICS_WRITE, localCurationFailureFor(failed, true, false))
        assertEquals(LocalCurationFailure.COVER_WRITE, localCurationFailureFor(failed, false, true))
    }

    @Test
    fun `embedding clears stale translations for new lyrics and leaves untouched parts null`() {
        val online = localSongAssetEmbedding(lyric(), coverReference = null)
        assertEquals("[00:01.00]line", online.lyrics)
        assertEquals("", online.translatedLyrics)
        assertEquals("", online.romanizedLyrics)
        assertTrue(online.userEditedLyrics)
        assertFalse(online.writesCover)

        val existing = localSongAssetEmbedding(lyric(origin = LocalCurationLyricOrigin.EXISTING), "file:///c.jpg")
        assertFalse(existing.userEditedLyrics)
        assertTrue(existing.writesCover)

        val coverOnly = localSongAssetEmbedding(null, "file:///c.jpg")
        assertNull(coverOnly.lyrics)
        assertNull(coverOnly.translatedLyrics)
        assertFalse(coverOnly.writesLyrics)
    }

    @Test
    fun `stored enum names fall back to defaults when unknown`() {
        assertEquals(
            LocalCurationLyricMode.REMATCH_ALL,
            enumValueOrDefault("REMATCH_ALL", LocalCurationLyricMode.FILL_MISSING)
        )
        assertEquals(
            LocalCurationLyricMode.FILL_MISSING,
            enumValueOrDefault("REMOVED_MODE", LocalCurationLyricMode.FILL_MISSING)
        )
        assertEquals(LocalCurationCoverMode.SKIP, enumValueOrDefault(null, LocalCurationCoverMode.SKIP))
    }

    @Test
    fun `cover pick prefers the chosen lyric source and rejects low confidence`() {
        val lyricMatch = ranked("lyric", coverUrl = "https://a/cover.jpg")
        val other = ranked("other", coverUrl = "https://b/cover.jpg")
        val low = ranked("low", confidence = EditableLyricMatchConfidence.LOW, coverUrl = "https://c")
        val noCover = ranked("plain")

        assertEquals("lyric", pickLocalCoverMatch(listOf(other, lyricMatch), lyricMatch)?.candidate?.id)
        assertEquals("other", pickLocalCoverMatch(listOf(low, noCover, other), noCover)?.candidate?.id)
        assertNull(pickLocalCoverMatch(listOf(low, noCover), null))
    }

    @Test
    fun `auto selection trusts existing lyrics, high confidence and fitting medium matches`() {
        assertTrue(shouldAutoApplyLyric(lyric(origin = LocalCurationLyricOrigin.EXISTING)))
        assertTrue(shouldAutoApplyLyric(lyric(confidence = EditableLyricMatchConfidence.HIGH)))
        assertTrue(
            shouldAutoApplyLyric(
                lyric(confidence = EditableLyricMatchConfidence.MEDIUM, fit = EditableLyricTimelineFit.FITS)
            )
        )
        assertFalse(shouldAutoApplyLyric(lyric(confidence = EditableLyricMatchConfidence.MEDIUM)))
        assertFalse(shouldAutoApplyLyric(lyric(confidence = EditableLyricMatchConfidence.LOW)))
        assertFalse(
            shouldAutoApplyLyric(
                lyric(confidence = EditableLyricMatchConfidence.HIGH, fit = EditableLyricTimelineFit.OVERFLOWS)
            )
        )
        assertTrue(shouldAutoApplyCover(cover(LocalCurationCoverOrigin.FOLDER, EditableLyricMatchConfidence.LOW)))
        assertFalse(shouldAutoApplyCover(cover(LocalCurationCoverOrigin.ONLINE, EditableLyricMatchConfidence.MEDIUM)))
    }

    @Test
    fun `size estimate counts selected work against existing tags`() {
        val item = LocalCurationItem(
            key = "a",
            song = song(),
            phase = LocalCurationItemPhase.READY,
            existing = assets(lyricBytes = 10, coverBytes = 1_000),
            lyric = lyric(lyrics = "abcde", translated = "xy"),
            cover = cover(LocalCurationCoverOrigin.FOLDER, EditableLyricMatchConfidence.HIGH, bytes = 4_000),
            applyLyric = true,
            applyCover = true
        )

        // flac 只有一个标准字段：合并 7 + 原文 5 + 三个翻译字段 6
        assertEquals(7L + 5 + 3 * 2 - 10 + 4_000 - 1_000, estimateLocalCurationBytesDelta(listOf(item)))
        assertEquals(0L, estimateLocalCurationBytesDelta(listOf(item.copy(applyLyric = false, applyCover = false))))
    }

    @Test
    fun `lyric size estimate counts every tag the writer fills for the container`() {
        val proposal = lyric(lyrics = "abcde", translated = "xy")

        // mp3 写 LYRICS 与 UNSYNCEDLYRICS 两个标准字段
        assertEquals(2L * 7 + 5 + 3 * 2, estimatedLyricTagBytes(proposal, "mp3"))
        assertEquals(7L + 5 + 3 * 2, estimatedLyricTagBytes(proposal, "flac"))
        assertEquals(7L + 5 + 3 * 2, estimatedLyricTagBytes(proposal, null))
    }

    @Test
    fun `apply order puts the playing song last and skips unselected items`() {
        val items = listOf(
            readyItem("playing"),
            readyItem("a"),
            readyItem("unselected").copy(applyLyric = false),
            readyItem("b")
        )

        assertEquals(listOf("a", "b", "playing"), orderLocalCurationApply(items, "playing").map { it.key })
        assertEquals(listOf("playing", "a", "b"), orderLocalCurationApply(items, null).map { it.key })
    }

    @Test
    fun `analyzed phase distinguishes unsupported, complete and missing results`() {
        val existing = assets()
        assertEquals(
            LocalCurationItemPhase.UNSUPPORTED,
            resolveAnalyzedPhase(null, LocalLyricWork.MATCH_ONLINE, true, null, null)
        )
        assertEquals(
            LocalCurationItemPhase.COMPLETE,
            resolveAnalyzedPhase(existing, LocalLyricWork.NONE, false, null, null)
        )
        assertEquals(
            LocalCurationItemPhase.NO_MATCH,
            resolveAnalyzedPhase(existing, LocalLyricWork.MATCH_ONLINE, false, null, null)
        )
        assertEquals(
            LocalCurationItemPhase.READY,
            resolveAnalyzedPhase(existing, LocalLyricWork.MATCH_ONLINE, false, lyric(), null)
        )
    }

    @Test
    fun `request uses display metadata and the cover capable sources`() {
        val request = buildLocalCurationMatchRequest(
            song().copy(customName = " Custom ", album = " Night "),
            preferWordTimed = false
        )

        assertEquals("Custom", request.trackName)
        assertEquals("Custom Artist", request.keyword)
        assertEquals("Night", request.albumName)
        assertEquals(200_000L, request.durationMs)
        assertTrue(EditableLyricMatchSource.CLOUD_MUSIC in request.sources)
        assertFalse(EditableLyricMatchSource.AMLL_TTML in request.sources)
    }

    @Test
    fun `filters separate attention items from confident ones`() {
        val confident = readyItem("confident")
        val uncertain = readyItem("uncertain").copy(lyric = lyric(confidence = EditableLyricMatchConfidence.LOW))
        val complete = readyItem("complete").copy(phase = LocalCurationItemPhase.COMPLETE)

        assertFalse(needsLocalCurationReview(confident))
        assertTrue(needsLocalCurationReview(uncertain))
        assertTrue(complete.matches(LocalCurationFilter.SKIPPED))
        assertFalse(complete.matches(LocalCurationFilter.READY))
        assertTrue(uncertain.matches(LocalCurationFilter.ATTENTION))
    }

    @Test
    fun `lyric preview strips timing markup and metadata tags`() {
        val preview = localCurationLyricPreview(
            "[ti:Song]\n[00:01.00]Hello\n[1000,500](1000,500,0)Wor(1500,10,0)ld\n\n<00:03.00>Again\n[00:05.00]Four",
            maxLines = 3
        )

        assertEquals(listOf("Hello", "World", "Again"), preview)
    }

    private fun assets(lyricBytes: Int = 0, coverBytes: Int = 0) = LocalEmbeddedAssetState(
        embeddedLyricBytes = lyricBytes,
        embeddedCoverBytes = coverBytes,
        fileSizeBytes = 1_000_000L,
        audioExtension = "flac"
    )

    private fun song() = SongItem(
        id = 1L,
        name = "Song",
        artist = "Artist",
        album = "",
        albumId = 0L,
        durationMs = 200_000L,
        coverUrl = null,
        localFilePath = "/music/song.flac"
    )

    private fun ranked(
        id: String,
        confidence: EditableLyricMatchConfidence = EditableLyricMatchConfidence.HIGH,
        timelineFit: EditableLyricTimelineFit = EditableLyricTimelineFit.FITS,
        coverUrl: String? = null
    ) = RankedEditableLyricMatch(
        candidate = EditableLyricMatchCandidate(
            id = id,
            source = EditableLyricMatchSource.CLOUD_MUSIC,
            title = "Song",
            artist = "Artist",
            lyrics = "[00:01.00]line",
            coverUrl = coverUrl
        ),
        score = 100,
        durationDeltaMs = 0L,
        confidence = confidence,
        timelineFit = timelineFit
    )

    private fun lyric(
        origin: LocalCurationLyricOrigin = LocalCurationLyricOrigin.ONLINE,
        confidence: EditableLyricMatchConfidence = EditableLyricMatchConfidence.HIGH,
        fit: EditableLyricTimelineFit = EditableLyricTimelineFit.UNKNOWN,
        lyrics: String = "[00:01.00]line",
        translated: String? = null
    ) = LocalCurationLyricProposal(
        lyrics = lyrics,
        translatedLyrics = translated,
        romanizedLyrics = null,
        origin = origin,
        source = EditableLyricMatchSource.CLOUD_MUSIC,
        matchedTitle = "Song",
        matchedArtist = "Artist",
        confidence = confidence,
        timelineFit = fit,
        hasWordTiming = false
    )

    private fun cover(
        origin: LocalCurationCoverOrigin,
        confidence: EditableLyricMatchConfidence,
        bytes: Int = 1_000
    ) = LocalCurationCoverProposal(
        prepared = PreparedEmbeddedCover(reference = "file:///cover.jpg", byteSize = bytes, width = 500, height = 500),
        origin = origin,
        confidence = confidence
    )

    private fun readyItem(key: String) = LocalCurationItem(
        key = key,
        song = song(),
        phase = LocalCurationItemPhase.READY,
        existing = assets(),
        lyric = lyric(),
        applyLyric = true
    )
}
