package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class SyncSongLyricMergePerformanceTest {
    @Test fun sameLyricStateKeepsSelectedMetadataAndStorage() {
        val selected = largeSong(1)
        val candidate = separatelyStoredLyrics(selected).copy(
            name = "remote name", artist = "remote artist", durationMs = 900L,
            customName = "remote custom name", coverUrl = "https://example.com/remote.jpg"
        )
        assertNotSame(selected.matchedLyric, candidate.matchedLyric)
        assertSame(selected, SyncSongLyricMergePolicy.merge(selected, listOf(selected, candidate)))
        assertSame(selected, SyncSongLyricMergePolicy.merge(selected, listOf(candidate, selected)))
    }

    @Test fun revisionResetAndLegacyNormalizationStillDetermineSelection() {
        val edit = SyncSong(id = 1L, name = "selected metadata", lyricSyncEdited = true,
            lyricSyncRevision = 20L, matchedLyric = "z", originalLyric = "baseline")
        val newer = edit.copy(name = "remote metadata", lyricSyncRevision = 21L, matchedLyric = "a")
        assertMergeSelects(edit, listOf(edit, newer), newer)

        val reset = edit.copy(lyricSyncEdited = false)
        assertMergeSelects(edit, listOf(edit, reset), reset)
        assertMergeSelects(reset, listOf(edit, reset), reset)
        assertMergeSelects(reset, listOf(reset, newer), newer)

        val unknown = edit.copy(lyricSyncEdited = null, lyricSyncRevision = 100L, matchedLyric = "legacy")
        val explicit = edit.copy(lyricSyncRevision = 2L, matchedLyric = "explicit")
        assertMergeSelects(edit, listOf(unknown, explicit), explicit)
        val normalizedUnknown = SyncSongLyricMergePolicy.normalize(unknown)
        assertMergeSelects(edit, listOf(unknown, normalizedUnknown), normalizedUnknown)
        assertMergeSelects(edit, listOf(edit.copy(lyricSyncRevision = -1L), explicit), explicit)
        assertSame(edit, SyncSongLyricMergePolicy.merge(edit, emptyList()))
    }

    @Test fun everyPayloadFieldRetainsNullEmptyLengthAndHashCollisionOrdering() {
        val selected = SyncSong(id = 1L, name = "selected metadata", lyricSyncEdited = true, lyricSyncRevision = 20L)
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val values = listOf(null to "", "Aa" to "BB", "z" to "aa", "x".repeat(9) to "x".repeat(10),
            "歌词:1" to "歌词:2")
        for ((field, replace) in payloadFields) {
            for ((left, right) in values) {
                val a = replace(selected, left)
                val b = replace(selected, right)
                val expected = if (wirePayloadKey(a) >= wirePayloadKey(b)) a else b
                assertMergeSelects(selected, listOf(a, b), expected, field)
            }
        }
    }

    @Test fun equalCandidatesDoNotHideAConflictingLaterField() {
        val selected = largeSong(1)
        val same = separatelyStoredLyrics(selected).copy(name = "other metadata")
        val conflicting = same.copy(originalRomanizedLyric = checkNotNull(same.originalRomanizedLyric) + "z")
        val expected = listOf(selected, same, conflicting).maxBy(::wirePayloadKey)
        for (candidates in listOf(listOf(selected, same, conflicting), listOf(conflicting, same, selected),
            listOf(same, conflicting, selected))) {
            assertMergeSelects(selected, candidates, expected)
        }
    }

    @Test fun representativeEqualLyricsReportMedianMergeTime() {
        val pairs = (1..BENCHMARK_SONGS).map { index ->
            val selected = largeSong(index)
            selected to separatelyStoredLyrics(selected).copy(name = "remote metadata $index")
        }
        val payloadBytes = pairs.sumOf { (selected, candidate) ->
            lyricPayload(selected).sumOf { it?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L } +
                lyricPayload(candidate).sumOf { it?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L }
        }
        val warm = pairs.first()
        repeat(8) { assertSame(warm.first, SyncSongLyricMergePolicy.merge(warm.first, listOf(warm.first, warm.second))) }
        val elapsed = List(BENCHMARK_SAMPLES) {
            val merged = ArrayList<SyncSong>(pairs.size)
            val started = System.nanoTime()
            for ((selected, candidate) in pairs) {
                merged += SyncSongLyricMergePolicy.merge(selected, listOf(selected, candidate))
            }
            val nanos = System.nanoTime() - started
            pairs.indices.forEach { index -> assertSame(pairs[index].first, merged[index]) }
            nanos
        }
        println("SYNC_LYRIC_MERGE_BENCHMARK mode=same-state songs=$BENCHMARK_SONGS " +
            "payloadBytes=$payloadBytes samples=$BENCHMARK_SAMPLES statistic=median " +
            "elapsedNanos=${elapsed.sorted()[BENCHMARK_SAMPLES / 2]} sampleNanos=${elapsed.joinToString(",")}")
    }

    private fun assertMergeSelects(selected: SyncSong, candidates: List<SyncSong>, winner: SyncSong, field: String = "lyrics") {
        val normalized = SyncSongLyricMergePolicy.normalize(winner)
        val expected = SyncSongLyricMergePolicy.normalize(selected).copy(
            matchedLyric = normalized.matchedLyric, matchedTranslatedLyric = normalized.matchedTranslatedLyric,
            matchedRomanizedLyric = normalized.matchedRomanizedLyric, originalLyric = normalized.originalLyric,
            originalTranslatedLyric = normalized.originalTranslatedLyric, originalRomanizedLyric = normalized.originalRomanizedLyric,
            matchedLyricSource = normalized.matchedLyricSource, matchedSongId = normalized.matchedSongId,
            lyricSyncRevision = normalized.lyricSyncRevision, lyricSyncEdited = normalized.lyricSyncEdited
        )
        assertEquals(field, expected, SyncSongLyricMergePolicy.merge(selected, candidates))
        assertEquals(field, expected, SyncSongLyricMergePolicy.merge(selected, candidates.reversed()))
    }

    private fun largeSong(index: Int): SyncSong {
        fun text(label: String): String = "[00:01.20] $label $index 歌词中文 English line\n"
            .repeat(LYRIC_CHARS / 20 + 1).take(LYRIC_CHARS)
        return SyncSong(id = index.toLong(), name = "selected $index", artist = "local artist", durationMs = 180_000L,
            lyricSyncEdited = true, lyricSyncRevision = 20L,
            matchedLyric = text("main"), matchedTranslatedLyric = text("translation"),
            matchedRomanizedLyric = text("romanized"), originalLyric = text("baseline"),
            originalTranslatedLyric = text("baseline translation"), originalRomanizedLyric = text("baseline romanized"),
            matchedLyricSource = "NETEASE", matchedSongId = "match-$index")
    }

    private fun separatelyStoredLyrics(song: SyncSong): SyncSong = song.copy(
        matchedLyric = song.matchedLyric?.let { String(it.toCharArray()) },
        matchedTranslatedLyric = song.matchedTranslatedLyric?.let { String(it.toCharArray()) },
        matchedRomanizedLyric = song.matchedRomanizedLyric?.let { String(it.toCharArray()) },
        originalLyric = song.originalLyric?.let { String(it.toCharArray()) },
        originalTranslatedLyric = song.originalTranslatedLyric?.let { String(it.toCharArray()) },
        originalRomanizedLyric = song.originalRomanizedLyric?.let { String(it.toCharArray()) }
    )

    private fun lyricPayload(song: SyncSong): List<String?> = listOf(
        song.matchedLyric, song.matchedTranslatedLyric, song.matchedRomanizedLyric,
        song.originalLyric, song.originalTranslatedLyric, song.originalRomanizedLyric,
        song.matchedLyricSource, song.matchedSongId
    )

    // 已发布的平局规则按长度前缀的 UTF-16 字符串排序，不能用哈希或字段长度替代
    private fun wirePayloadKey(song: SyncSong): String = lyricPayload(song)
        .joinToString("") { value -> "${value?.length ?: -1}:${value.orEmpty()}" }

    private companion object {
        const val BENCHMARK_SONGS = 64
        const val BENCHMARK_SAMPLES = 3
        const val LYRIC_CHARS = 8 * 1024
        val payloadFields: List<Pair<String, (SyncSong, String?) -> SyncSong>> = listOf(
            "matchedLyric" to { song, value -> song.copy(matchedLyric = value) },
            "matchedTranslatedLyric" to { song, value -> song.copy(matchedTranslatedLyric = value) },
            "matchedRomanizedLyric" to { song, value -> song.copy(matchedRomanizedLyric = value) },
            "originalLyric" to { song, value -> song.copy(originalLyric = value) },
            "originalTranslatedLyric" to { song, value -> song.copy(originalTranslatedLyric = value) },
            "originalRomanizedLyric" to { song, value -> song.copy(originalRomanizedLyric = value) },
            "matchedLyricSource" to { song, value -> song.copy(matchedLyricSource = value) },
            "matchedSongId" to { song, value -> song.copy(matchedSongId = value) }
        )
    }
}
