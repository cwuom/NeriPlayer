package moe.ouom.neriplayer.platform.lyrics.matching

import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricFormat
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchCandidate
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricMatchRequest
import moe.ouom.neriplayer.data.model.lyrics.matching.EditableLyricTimelineFit

private const val TIMELINE_MIN_TOLERANCE_MS = 10_000L
private const val TIMELINE_TOLERANCE_PERCENT = 8L
private const val TIMELINE_MIN_COVERAGE_PERCENT = 50L
private const val TIMELINE_FIT_BONUS = 8
private const val TIMELINE_OVERFLOW_PENALTY = 60

private const val LIVE_VERSION_MATCH_BONUS = 18
private const val LIVE_VERSION_MISSING_PENALTY = 10
private const val STUDIO_VERSION_LIVE_PENALTY = 24

private const val ID_TAG_ARTIST_MATCH_BONUS = 10
private const val ID_TAG_ARTIST_MISMATCH_PENALTY = 12
private const val ID_TAG_TITLE_MATCH_BONUS = 6
private const val ID_TAG_TITLE_MISMATCH_PENALTY = 8
private const val ID_TAG_ALBUM_MATCH_BONUS = 6
private const val ID_TAG_SCAN_LINE_LIMIT = 40
private const val ID_TAG_RELIABLE_TITLE_SCORE = 52
private const val ID_TAG_RELIABLE_ARTIST_SCORE = 24

private val yrcLineTimelineRegex = Regex("""^\[(\d{1,9}),\s*(\d{1,9})]""")
private val lrcIdTagRegex = Regex("""^\[(ti|ar|al)\s*:(.*)]$""", RegexOption.IGNORE_CASE)
private val liveVersionMarkerRegex = Regex(
    """(?:\blive\b|\bconcert\b|\bunplugged\b|现场|現場|演唱会|演唱會|音乐会|音樂會|巡演|ライブ)""",
    RegexOption.IGNORE_CASE
)

data class LrcIdTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null
)

/** 时间轴、版本与 LRC 头部标签三类补充信号；标题、歌手与时长的主体打分仍在 [rankEditableLyricMatches] */
data class LyricMatchSupplementalSignals(
    val timelineFit: EditableLyricTimelineFit,
    val timelineScore: Int,
    val versionScore: Int,
    val idTagScore: Int
) {
    val score: Int
        get() = timelineScore + versionScore + idTagScore
}

fun evaluateLyricMatchSupplementalSignals(
    request: EditableLyricMatchRequest,
    candidate: EditableLyricMatchCandidate
): LyricMatchSupplementalSignals {
    val timelineFit = resolveLyricTimelineFit(
        expectedDurationMs = request.durationMs,
        rawLyric = candidate.lyrics,
        format = candidate.format
    )
    return LyricMatchSupplementalSignals(
        timelineFit = timelineFit,
        timelineScore = scoreLyricTimelineFit(timelineFit),
        versionScore = scoreLyricVersionPreference(
            expectedTitle = request.trackName,
            candidateTitle = candidate.title,
            candidateAlbum = candidate.album
        ),
        idTagScore = scoreLrcIdTags(request, parseLrcIdTags(candidate.lyrics))
    )
}

/**
 * 只检查自带时间轴的 LRC/YRC，纯文本歌词的时间轴是按时长均分生成的，不能作为证据。
 * 最后一行起点超过歌曲时长加容差时，几乎可以确定是其他版本（加长版、现场版等）。
 */
fun resolveLyricTimelineFit(
    expectedDurationMs: Long,
    rawLyric: String,
    format: EditableLyricFormat
): EditableLyricTimelineFit {
    if (expectedDurationMs <= 0L) return EditableLyricTimelineFit.UNKNOWN
    if (format != EditableLyricFormat.LRC && format != EditableLyricFormat.YRC) {
        return EditableLyricTimelineFit.UNKNOWN
    }
    val endMs = lyricTimelineEndMs(rawLyric) ?: return EditableLyricTimelineFit.UNKNOWN
    val toleranceMs = maxOf(
        TIMELINE_MIN_TOLERANCE_MS,
        expectedDurationMs * TIMELINE_TOLERANCE_PERCENT / 100L
    )
    return when {
        endMs > expectedDurationMs + toleranceMs -> EditableLyricTimelineFit.OVERFLOWS
        endMs * 100L >= expectedDurationMs * TIMELINE_MIN_COVERAGE_PERCENT -> EditableLyricTimelineFit.FITS
        else -> EditableLyricTimelineFit.UNKNOWN
    }
}

fun scoreLyricTimelineFit(fit: EditableLyricTimelineFit): Int = when (fit) {
    EditableLyricTimelineFit.FITS -> TIMELINE_FIT_BONUS
    EditableLyricTimelineFit.OVERFLOWS -> -TIMELINE_OVERFLOW_PENALTY
    EditableLyricTimelineFit.UNKNOWN -> 0
}

/** 返回歌词中最晚的一行起点；YRC 行头是 `[开始,时长]`，取开始加时长 */
fun lyricTimelineEndMs(rawLyric: String): Long? {
    var latest = -1L
    for (rawLine in rawLyric.lineSequence()) {
        val line = rawLine.trim()
        val lineEnd = yrcLineEndMs(line) ?: lrcLineStartMs(line) ?: continue
        if (lineEnd > latest) latest = lineEnd
    }
    return latest.takeIf { it >= 0L }
}

private fun yrcLineEndMs(line: String): Long? {
    val match = yrcLineTimelineRegex.find(line) ?: return null
    // 正则限定最多 9 位数字，转换不会溢出或失败
    return match.groupValues[1].toLong() + match.groupValues[2].toLong()
}

private fun lrcLineStartMs(line: String): Long? {
    if (!hasLyricText(line)) return null
    return lrcTimestampRegex.findAll(line).mapNotNull(::parseLrcTimestampMs).maxOrNull()
}

fun isLiveLyricVersion(title: String, album: String? = null): Boolean {
    return liveVersionMarkerRegex.containsMatchIn(title) ||
        (album != null && liveVersionMarkerRegex.containsMatchIn(album))
}

/** 标题明确是现场版时优先现场版歌词；否则优先录音室版，避免把演唱会口白与报幕嵌进录音室音轨 */
fun scoreLyricVersionPreference(
    expectedTitle: String,
    candidateTitle: String,
    candidateAlbum: String?
): Int {
    val expectsLive = isLiveLyricVersion(expectedTitle)
    val candidateIsLive = isLiveLyricVersion(candidateTitle, candidateAlbum)
    return when {
        expectsLive && candidateIsLive -> LIVE_VERSION_MATCH_BONUS
        expectsLive -> -LIVE_VERSION_MISSING_PENALTY
        candidateIsLive -> -STUDIO_VERSION_LIVE_PENALTY
        else -> 0
    }
}

fun parseLrcIdTags(rawLyric: String): LrcIdTags {
    val values = LinkedHashMap<String, String>()
    val headerLines = rawLyric.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .take(ID_TAG_SCAN_LINE_LIMIT)
    for (line in headerLines) {
        val match = lrcIdTagRegex.matchEntire(line) ?: continue
        val value = match.groupValues[2].trim()
        if (value.isNotEmpty()) values.putIfAbsent(match.groupValues[1].lowercase(), value)
    }
    return LrcIdTags(title = values["ti"], artist = values["ar"], album = values["al"])
}

/** LRC 头部标签只作加减分；缺失或歌曲自身信息为占位值时完全跳过 */
fun scoreLrcIdTags(request: EditableLyricMatchRequest, tags: LrcIdTags): Int {
    return scoreIdTag(
        expected = request.artistName,
        tag = tags.artist,
        similarity = ::scoreLyricMatchArtist,
        reliableSimilarity = ID_TAG_RELIABLE_ARTIST_SCORE,
        bonus = ID_TAG_ARTIST_MATCH_BONUS,
        penalty = ID_TAG_ARTIST_MISMATCH_PENALTY
    ) + scoreIdTag(
        expected = request.trackName,
        tag = tags.title,
        similarity = ::scoreLyricMatchTitle,
        reliableSimilarity = ID_TAG_RELIABLE_TITLE_SCORE,
        bonus = ID_TAG_TITLE_MATCH_BONUS,
        penalty = ID_TAG_TITLE_MISMATCH_PENALTY
    ) + scoreAlbumIdTag(request.albumName, tags.album)
}

private fun scoreIdTag(
    expected: String,
    tag: String?,
    similarity: (String, String) -> Int,
    reliableSimilarity: Int,
    bonus: Int,
    penalty: Int
): Int {
    if (tag.isNullOrBlank() || expected.isBlank() || hasPlaceholderLyricMetadata(expected)) return 0
    val score = similarity(expected, tag)
    return when {
        score >= reliableSimilarity -> bonus
        score == 0 -> -penalty
        else -> 0
    }
}

/** 专辑名在各平台常带 Deluxe 等后缀，只奖励包含关系，不因不同而扣分 */
private fun scoreAlbumIdTag(expected: String?, tag: String?): Int {
    if (expected == null || tag == null) return 0
    return if (areRelatedAlbumNames(normalizeLyricMatchText(expected), normalizeLyricMatchText(tag))) {
        ID_TAG_ALBUM_MATCH_BONUS
    } else {
        0
    }
}

private fun areRelatedAlbumNames(left: String, right: String): Boolean {
    if (left.isEmpty() || right.isEmpty()) return false
    return left.contains(right) || right.contains(left)
}

private fun hasLyricText(line: String): Boolean {
    return lrcTimestampRegex.replace(line, "").isNotBlank()
}
