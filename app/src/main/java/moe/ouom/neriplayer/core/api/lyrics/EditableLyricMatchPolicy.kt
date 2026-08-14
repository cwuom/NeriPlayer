package moe.ouom.neriplayer.core.api.lyrics

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToInt
import moe.ouom.neriplayer.ui.component.lyrics.hasWordTimedEntries
import moe.ouom.neriplayer.ui.component.lyrics.parseNeteaseLyricsAuto
import moe.ouom.neriplayer.util.search.SearchTextMatcher

enum class EditableLyricMatchSource {
    KUGOU,
    CLOUD_MUSIC,
    QQ_MUSIC,
    AMLL_TTML,
    LRCLIB,
    YOUTUBE_MUSIC
}

enum class EditableLyricFormat {
    LRC,
    YRC,
    TTML,
    PLAIN
}

data class EditableLyricMatchRequest(
    val keyword: String,
    val trackName: String,
    val artistName: String,
    val albumName: String? = null,
    val durationMs: Long = 0L,
    val preferWordTimed: Boolean = true,
    val sources: Set<EditableLyricMatchSource> = defaultEditableLyricMatchSources()
)

data class EditableLyricMatchCandidate(
    val id: String,
    val source: EditableLyricMatchSource,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val lyrics: String,
    val translatedLyrics: String? = null,
    val format: EditableLyricFormat = EditableLyricFormat.LRC,
    val sourceScore: Int = 0
)

data class RankedEditableLyricMatch(
    val candidate: EditableLyricMatchCandidate,
    val score: Int,
    val durationDeltaMs: Long?,
    val confidence: EditableLyricMatchConfidence = EditableLyricMatchConfidence.LOW
)

enum class EditableLyricMatchConfidence(val rank: Int) {
    LOW(0),
    MEDIUM(1),
    HIGH(2)
}

private const val MIN_EDITABLE_LYRIC_MATCH_SCORE = 35
private const val MIN_RELIABLE_LYRIC_TITLE_SCORE = 52
private const val MIN_RELIABLE_LYRIC_ARTIST_SCORE = 24
private const val KUGOU_LYRIC_SOURCE_PRIORITY = 5
private const val CLOUD_MUSIC_LYRIC_SOURCE_PRIORITY = 4
private const val QQ_MUSIC_LYRIC_SOURCE_PRIORITY = 3
private const val LRCLIB_LYRIC_SOURCE_PRIORITY = 2
private const val AMLL_TTML_LYRIC_SOURCE_PRIORITY = 1
private const val YOUTUBE_MUSIC_LYRIC_SOURCE_PRIORITY = 0

private val lyricMatchWhitespaceRegex = Regex("\\s+")
private val lyricMatchHardArtistSeparatorRegex = Regex("[/,，、&+]|\\s+[xX]\\s+")
private val lyricMatchFeaturedArtistSeparatorRegex = Regex(
    "\\b(?:feat\\.?|ft\\.?|featuring)\\b",
    RegexOption.IGNORE_CASE
)

fun defaultEditableLyricMatchSources(
    isYouTubeMusicTrack: Boolean = false
): Set<EditableLyricMatchSource> {
    if (isYouTubeMusicTrack) {
        return setOf(
            EditableLyricMatchSource.KUGOU,
            EditableLyricMatchSource.CLOUD_MUSIC,
            EditableLyricMatchSource.QQ_MUSIC,
            EditableLyricMatchSource.LRCLIB
        )
    }
    return setOf(
        EditableLyricMatchSource.AMLL_TTML,
        EditableLyricMatchSource.CLOUD_MUSIC,
        EditableLyricMatchSource.KUGOU
    )
}

fun rankEditableLyricMatches(
    request: EditableLyricMatchRequest,
    candidates: List<EditableLyricMatchCandidate>
): List<RankedEditableLyricMatch> {
    return candidates.asSequence()
        .filter { it.lyrics.isNotBlank() }
        .filterNot { hasCollapsedTimedLyricTimeline(it.lyrics) }
        .mapNotNull { candidate ->
            val titleScore = scoreLyricMatchTitle(request.trackName, candidate.title)
            val artistScore = scoreLyricMatchArtist(request.artistName, candidate.artist)
            val albumScore = scoreLyricMatchAlbum(request.albumName.orEmpty(), candidate.album.orEmpty())
            val durationScore = scoreLyricMatchDuration(request.durationMs, candidate.durationMs)
            val qualityScore = scoreLyricMatchQuality(candidate)
            val wordTimingScore = scoreLyricMatchWordTiming(request, candidate)
            val keywordScore = scoreLyricMatchKeyword(request.keyword, candidate)
            val canUseKeywordFallback = hasPlaceholderLyricMetadata(request.trackName) ||
                hasPlaceholderLyricMetadata(request.artistName)
            val hasPrimaryArtist = hasPrimaryLyricMatchArtist(request.artistName, candidate.artist)
            val hasDurationSignal = request.durationMs <= 0L || candidate.durationMs <= 0L ||
                isExternalLyricDurationCompatible(request.durationMs, candidate.durationMs)
            val hasReliableIdentity = isReliableLyricMatchIdentity(
                expectedTitle = request.trackName,
                expectedArtist = request.artistName,
                candidateTitle = candidate.title,
                candidateArtist = candidate.artist
            )
            val hasPlausibleIdentity = isPlausibleLyricMatchIdentity(
                expectedTitle = request.trackName,
                expectedArtist = request.artistName,
                candidateTitle = candidate.title,
                candidateArtist = candidate.artist,
                durationCompatible = hasDurationSignal
            ) || (canUseKeywordFallback && (keywordScore > 0 || titleScore >= 20))
            if (!hasPlausibleIdentity) {
                return@mapNotNull null
            }
            val score = titleScore +
                artistScore +
                albumScore +
                durationScore +
                qualityScore +
                wordTimingScore +
                keywordScore +
                candidate.sourceScore.coerceIn(0, 20)
            if (score < MIN_EDITABLE_LYRIC_MATCH_SCORE) {
                return@mapNotNull null
            }
            val confidence = when {
                hasReliableIdentity && hasDurationSignal -> EditableLyricMatchConfidence.HIGH
                hasPrimaryArtist && hasDurationSignal && titleScore >= 20 ->
                    EditableLyricMatchConfidence.MEDIUM
                titleScore >= MIN_RELIABLE_LYRIC_TITLE_SCORE && hasDurationSignal ->
                    EditableLyricMatchConfidence.MEDIUM
                else -> EditableLyricMatchConfidence.LOW
            }
            RankedEditableLyricMatch(
                candidate = candidate,
                score = score,
                durationDeltaMs = durationDeltaMs(request.durationMs, candidate.durationMs),
                confidence = confidence
            )
        }
        .sortedWith(
            editableLyricMatchResultComparator(
                sourceRank = ::editableLyricMatchSourcePriority,
                sourceFallbackRank = { it.ordinal }
            )
        )
        .toList()
}

internal fun editableLyricMatchResultComparator(
    sourceRank: (EditableLyricMatchSource) -> Int,
    sourceFallbackRank: (EditableLyricMatchSource) -> Int = { it.ordinal }
): Comparator<RankedEditableLyricMatch> {
    return compareByDescending<RankedEditableLyricMatch> { it.confidence.rank }
        .thenByDescending { it.score }
        .thenBy { it.durationDeltaMs ?: Long.MAX_VALUE }
        .thenByDescending { sourceRank(it.candidate.source) }
        .thenBy { sourceFallbackRank(it.candidate.source) }
        .thenBy { normalizeLyricMatchText(it.candidate.title) }
}

internal fun hasLyricMatchSignal(
    request: EditableLyricMatchRequest,
    candidate: EditableLyricMatchCandidate
): Boolean {
    val titleScore = scoreLyricMatchTitle(request.trackName, candidate.title)
    val artistScore = scoreLyricMatchArtist(request.artistName, candidate.artist)
    val keywordScore = scoreLyricMatchKeyword(request.keyword, candidate)
    return (titleScore >= 20 || keywordScore > 0) &&
        (artistScore >= MIN_RELIABLE_LYRIC_ARTIST_SCORE || candidate.artist.isBlank())
}

private fun scoreLyricMatchWordTiming(
    request: EditableLyricMatchRequest,
    candidate: EditableLyricMatchCandidate
): Int {
    if (!request.preferWordTimed) {
        return 0
    }
    return if (hasEditableLyricWordTiming(candidate.lyrics)) 36 else 0
}

fun hasEditableLyricWordTiming(rawLyric: String): Boolean {
    if (rawLyric.isBlank()) {
        return false
    }
    return runCatching {
        parseNeteaseLyricsAuto(rawLyric).hasWordTimedEntries()
    }.getOrDefault(false)
}

fun scoreLyricMatchTitle(expected: String, candidate: String): Int {
    val expectedText = normalizeLyricMatchText(expected)
    val candidateText = normalizeLyricMatchText(candidate)
    if (expectedText.isBlank() || candidateText.isBlank()) return 0
    return when {
        candidateText == expectedText -> 80
        candidateText.startsWith("$expectedText ") -> 68
        expectedText.startsWith("$candidateText ") -> 62
        candidateText.contains(expectedText) || expectedText.contains(candidateText) -> 52
        else -> (tokenOverlapRatio(expectedText, candidateText) * 44).roundToInt()
    }
}

fun scoreLyricMatchArtist(expected: String, candidate: String): Int {
    val expectedArtists = splitLyricMatchArtists(expected)
    val candidateArtists = splitLyricMatchArtists(candidate)
    if (expectedArtists.isEmpty() || candidateArtists.isEmpty()) return 0
    if (expectedArtists == candidateArtists) return 55
    if (candidateArtists.containsAll(expectedArtists)) return 46
    val intersectionSize = expectedArtists.intersect(candidateArtists).size
    if (intersectionSize > 0) {
        return 28 + (18 * intersectionSize / expectedArtists.size.coerceAtLeast(1))
    }
    return expectedArtists.maxOf { expectedArtist ->
        candidateArtists.maxOf { candidateArtist ->
            when {
                hasAlignedLyricMatchArtistContainment(expectedArtist, candidateArtist) -> 24
                else -> (tokenOverlapRatio(expectedArtist, candidateArtist) * 20).roundToInt()
            }
        }
    }
}

private fun hasAlignedLyricMatchArtistContainment(left: String, right: String): Boolean {
    return when {
        left == right -> true
        right.startsWith("$left ") -> true
        else -> false
    }
}

fun isReliableLyricMatchIdentity(
    expectedTitle: String,
    expectedArtist: String,
    candidateTitle: String,
    candidateArtist: String
): Boolean {
    if (
        expectedTitle.isBlank() ||
        expectedArtist.isBlank() ||
        candidateTitle.isBlank() ||
        candidateArtist.isBlank()
    ) {
        return false
    }
    if (!hasCompatibleLyricVersion(expectedTitle, candidateTitle)) {
        return false
    }
    val expectedPrimaryArtist = primaryLyricMatchArtist(expectedArtist)
    val candidateArtists = splitLyricMatchArtists(candidateArtist)
    val primaryArtistMatches = expectedPrimaryArtist != null && candidateArtists.any { candidateArtistName ->
        candidateArtistName == expectedPrimaryArtist ||
            hasCollaboratorLyricMatchArtistSuffix(candidateArtistName, expectedPrimaryArtist)
    }
    return primaryArtistMatches &&
        canonicalLyricMatchTitle(expectedTitle) == canonicalLyricMatchTitle(candidateTitle) &&
        scoreLyricMatchTitle(expectedTitle, candidateTitle) >= MIN_RELIABLE_LYRIC_TITLE_SCORE &&
        scoreLyricMatchArtist(expectedArtist, candidateArtist) >= MIN_RELIABLE_LYRIC_ARTIST_SCORE
}

fun isPlausibleLyricMatchIdentity(
    expectedTitle: String,
    expectedArtist: String,
    candidateTitle: String,
    candidateArtist: String,
    durationCompatible: Boolean
): Boolean {
    if (isReliableLyricMatchIdentity(expectedTitle, expectedArtist, candidateTitle, candidateArtist)) {
        return true
    }
    val titleScore = scoreLyricMatchTitle(expectedTitle, candidateTitle)
    val artistScore = scoreLyricMatchArtist(expectedArtist, candidateArtist)
    val hasPrimaryArtist = hasPrimaryLyricMatchArtist(expectedArtist, candidateArtist)
    return (hasPrimaryArtist && titleScore >= 20 && durationCompatible) ||
        (titleScore >= MIN_RELIABLE_LYRIC_TITLE_SCORE && durationCompatible && candidateArtist.isBlank()) ||
        (titleScore >= 20 && artistScore >= MIN_RELIABLE_LYRIC_ARTIST_SCORE && durationCompatible)
}

fun scoreLyricMatchDuration(expectedDurationMs: Long, candidateDurationMs: Long): Int {
    if (expectedDurationMs <= 0L || candidateDurationMs <= 0L) return 0
    val deltaMs = abs(expectedDurationMs - candidateDurationMs)
    if (isExternalLyricDurationCompatible(expectedDurationMs, candidateDurationMs)) {
        return (42 - deltaMs / 500L).toInt().coerceAtLeast(22)
    }
    return -(deltaMs / 3_000L).toInt().coerceAtMost(48)
}

fun scoreLyricMatchKeyword(keyword: String, candidate: EditableLyricMatchCandidate): Int {
    val query = toSimplifiedChineseForDomesticSearch(keyword.trim())
    if (query.isBlank()) return 0
    val fuzzyScore = SearchTextMatcher.score(
        query = query,
        values = listOf(candidate.title, candidate.artist, candidate.album)
            .map { value -> toSimplifiedChineseForDomesticSearch(value.orEmpty()) }
    ) ?: return 0
    return (48 - fuzzyScore / 2).coerceIn(12, 48)
}

fun normalizeLyricMatchText(value: String): String {
    return Normalizer.normalize(toSimplifiedChineseForDomesticSearch(value), Normalizer.Form.NFKC)
        .lowercase()
        .replace("&", " and ")
        .replace(Regex("""\b(feat|ft|featuring)\.?\b"""), " ")
        .replace(Regex("""[(){}\[\]【】（）]"""), " ")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .replace(lyricMatchWhitespaceRegex, " ")
}

private fun hasPlaceholderLyricMetadata(value: String): Boolean {
    return normalizeLyricMatchText(value) in setOf(
        "unknown",
        "unknown artist",
        "unknown song",
        "unknown title",
        "未知",
        "未知歌手",
        "未知歌曲",
        "未知标题"
    )
}

private fun hasCompatibleLyricVersion(expectedTitle: String, candidateTitle: String): Boolean {
    return lyricVersionSignature(expectedTitle) == lyricVersionSignature(candidateTitle)
}

private fun lyricVersionSignature(value: String): Set<String> {
    return lyricVersionModifierRegex.findAll(normalizeLyricMatchText(value))
        .map { match ->
            when (match.value) {
                "remastered" -> "remaster"
                else -> match.value
            }
        }
        .toSet()
}

private fun canonicalLyricMatchTitle(value: String): String {
    return normalizeLyricMatchText(value)
        .replace(
            Regex("(?:\\s+|^)(?:official|audio|video|lyrics?|visualizer|hd|hq|4k|mv|官方|官方版|官方视频|音频|歌词|歌词版|高清|完整版)(?:\\s+(?:official|audio|video|lyrics?|visualizer|hd|hq|4k|mv|官方|官方版|官方视频|音频|歌词|歌词版|高清|完整版))*$"),
            " "
        )
        .replace(lyricVersionModifierRegex, " ")
        .replace(lyricMatchWhitespaceRegex, " ")
        .trim()
}

private val lyricVersionModifierRegex = Regex(
    """\b(?:remaster(?:ed)?|remix|live|acoustic|instrumental|karaoke|demo|cover|rework|slowed|sped\s+up|version|edit|extended|radio|clean|explicit)\b"""
)

private fun scoreLyricMatchAlbum(expected: String, candidate: String): Int {
    val expectedAlbum = normalizeLyricMatchText(expected)
    val candidateAlbum = normalizeLyricMatchText(candidate)
    if (expectedAlbum.isBlank() || candidateAlbum.isBlank()) return 0
    return when {
        candidateAlbum == expectedAlbum -> 8
        candidateAlbum.contains(expectedAlbum) || expectedAlbum.contains(candidateAlbum) -> 4
        else -> 0
    }
}

private fun scoreLyricMatchQuality(candidate: EditableLyricMatchCandidate): Int {
    val formatScore = when (candidate.format) {
        EditableLyricFormat.TTML -> 16
        EditableLyricFormat.YRC -> 14
        EditableLyricFormat.LRC -> 10
        EditableLyricFormat.PLAIN -> 2
    }
    val translationScore = if (!candidate.translatedLyrics.isNullOrBlank()) 5 else 0
    val sourceScore = when (candidate.source) {
        EditableLyricMatchSource.KUGOU,
        EditableLyricMatchSource.CLOUD_MUSIC,
        EditableLyricMatchSource.QQ_MUSIC -> 4
        EditableLyricMatchSource.LRCLIB -> 2
        EditableLyricMatchSource.AMLL_TTML -> 1
        EditableLyricMatchSource.YOUTUBE_MUSIC -> 0
    }
    return formatScore + translationScore + sourceScore
}

private fun splitLyricMatchArtists(value: String): Set<String> {
    val wholeName = normalizeLyricMatchText(value)
    val segments = lyricMatchArtistSegments(value)
    return (listOf(wholeName) + segments)
        .filter { it.isNotBlank() }
        .toSet()
}

private fun hasPrimaryLyricMatchArtist(expected: String, candidate: String): Boolean {
    val expectedPrimary = primaryLyricMatchArtist(expected) ?: return false
    return splitLyricMatchArtists(candidate).any { it == expectedPrimary }
}

/**
 * Accepts a candidate artist that extends the expected primary artist only through an explicit
 * collaboration connective (for example "Artist One and Guest"). Plain suffixes such as
 * "Artist One Tribute" must not be treated as the same primary artist.
 */
private fun hasCollaboratorLyricMatchArtistSuffix(
    candidateArtistName: String,
    expectedPrimaryArtist: String
): Boolean {
    if (!candidateArtistName.startsWith("$expectedPrimaryArtist ")) return false
    val connective = candidateArtistName
        .removePrefix("$expectedPrimaryArtist ")
        .trimStart()
        .substringBefore(' ')
    return connective in lyricMatchArtistCollaborationConnectives
}

private val lyricMatchArtistCollaborationConnectives = setOf(
    "and", "with", "x", "vs", "versus", "和", "与"
)

private fun primaryLyricMatchArtist(value: String): String? {
    return lyricMatchArtistSegments(value).firstOrNull()
}

private fun lyricMatchArtistSegments(value: String): List<String> {
    return lyricMatchFeaturedArtistSeparatorRegex
        .split(value)
        .flatMap { segment -> lyricMatchHardArtistSeparatorRegex.split(segment) }
        .map(::normalizeLyricMatchText)
        .filter { it.isNotBlank() }
}

internal fun editableLyricMatchSourcePriority(source: EditableLyricMatchSource): Int {
    return when (source) {
        EditableLyricMatchSource.KUGOU -> KUGOU_LYRIC_SOURCE_PRIORITY
        EditableLyricMatchSource.CLOUD_MUSIC -> CLOUD_MUSIC_LYRIC_SOURCE_PRIORITY
        EditableLyricMatchSource.QQ_MUSIC -> QQ_MUSIC_LYRIC_SOURCE_PRIORITY
        EditableLyricMatchSource.LRCLIB -> LRCLIB_LYRIC_SOURCE_PRIORITY
        EditableLyricMatchSource.AMLL_TTML -> AMLL_TTML_LYRIC_SOURCE_PRIORITY
        EditableLyricMatchSource.YOUTUBE_MUSIC -> YOUTUBE_MUSIC_LYRIC_SOURCE_PRIORITY
    }
}

private fun tokenOverlapRatio(left: String, right: String): Double {
    val leftTokens = left.split(' ').filter { it.isNotBlank() }.toSet()
    val rightTokens = right.split(' ').filter { it.isNotBlank() }.toSet()
    if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
    val intersectionSize = leftTokens.intersect(rightTokens).size
    return intersectionSize.toDouble() / maxOf(leftTokens.size, rightTokens.size)
}

private fun durationDeltaMs(expectedDurationMs: Long, candidateDurationMs: Long): Long? {
    if (expectedDurationMs <= 0L || candidateDurationMs <= 0L) return null
    return abs(expectedDurationMs - candidateDurationMs)
}
