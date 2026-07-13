package moe.ouom.neriplayer.core.player.resolver.netease

import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.model.PlaybackUrlCandidate
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.core.player.policy.refresh.RefreshResolverSideEffects
import moe.ouom.neriplayer.core.player.url.buildBiliPlaybackAudioInfo
import moe.ouom.neriplayer.core.player.url.inferBiliQualityKey
import moe.ouom.neriplayer.data.platform.bili.BiliAudioStreamInfo
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import kotlin.math.absoluteValue

private const val NETEASE_AUTO_SOURCE_SEARCH_LIMIT = 6
private const val NETEASE_AUTO_SOURCE_MIN_ACCEPT_SCORE = 70
private const val NETEASE_AUTO_SOURCE_FALLBACK_LIMIT = 2
private val autoSourceCacheKeyUnsafeRegex = Regex("[^A-Za-z0-9_.-]+")
private val autoSourceNonTextRegex = Regex("[^\\p{L}\\p{N}]+")
private val autoSourceWhitespaceRegex = Regex("\\s+")

internal suspend fun PlayerManager.tryResolveNeteaseAutoBiliSource(
    song: SongItem,
    sideEffects: RefreshResolverSideEffects
): SongUrlResult? {
    if (!neteaseAutoSourceSwitchEnabled) return null

    val queries = buildNeteaseAutoSourceQueries(song)
    if (queries.isEmpty()) return null

    NPLogger.w(
        "NERI-PlayerManager",
        "Netease source unavailable, trying Bili auto source: song=${song.name}, artist=${song.artist}"
    )

    val visitedCandidates = mutableSetOf<String>()
    var primaryResult: SongUrlResult.Success? = null
    val fallbackResults = mutableListOf<PlaybackUrlCandidate>()

    for (query in queries) {
        val candidates = fetchBiliAutoSourceCandidates(song, "$query 无损")
            .sortedByDescending { scoreNeteaseAutoBiliCandidate(song, it) }
            .take(NETEASE_AUTO_SOURCE_SEARCH_LIMIT)

        for (candidate in candidates) {
            val candidateKey = candidate.bvid.ifBlank { candidate.aid.toString() }
            if (!visitedCandidates.add(candidateKey)) continue

            val result = resolveNeteaseAutoBiliCandidate(
                song = song,
                candidate = candidate,
                sideEffects = sideEffects
            )
            if (result == null) continue
            if (primaryResult == null) {
                primaryResult = result
            } else {
                fallbackResults += result.toPlaybackUrlCandidate()
                if (fallbackResults.size >= NETEASE_AUTO_SOURCE_FALLBACK_LIMIT) {
                    return primaryResult.copy(fallbackCandidates = fallbackResults)
                }
            }
        }
    }

    primaryResult?.let { result ->
        return result.copy(fallbackCandidates = fallbackResults)
    }

    NPLogger.w(
        "NERI-PlayerManager",
        "Bili auto source not found: song=${song.name}, artist=${song.artist}"
    )
    return null
}

private fun SongUrlResult.Success.toPlaybackUrlCandidate(): PlaybackUrlCandidate {
    return PlaybackUrlCandidate(
        url = url,
        candidateUrls = candidateUrls,
        mimeType = mimeType,
        expectedContentLength = expectedContentLength,
        audioInfo = audioInfo,
        cacheKeyOverride = cacheKeyOverride
    )
}

private fun buildNeteaseAutoSourceQueries(song: SongItem): List<String> {
    val title = (song.originalName ?: song.name).trim()
    val artist = (song.originalArtist ?: song.artist).trim()
    return listOf(
        "$title $artist",
        "$artist $title",
        title
    ).map(::normalizeAutoSourceQuery)
        .filter { it.isNotBlank() }
        .distinct()
}

private suspend fun PlayerManager.fetchBiliAutoSourceCandidates(
    song: SongItem,
    query: String
): List<BiliClient.SearchVideoItem> {
    val durationFilter = durationFilterForBiliSearch(song.durationMs)
    val firstTry = runCatching {
        biliClient.searchVideos(
            keyword = query,
            page = 1,
            duration = durationFilter
        ).items
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        NPLogger.w(
            "NERI-PlayerManager",
            "Bili auto source search failed: query=$query, duration=$durationFilter, error=${error.message}"
        )
        emptyList()
    }

    if (firstTry.isNotEmpty() || durationFilter == 0) return firstTry

    return runCatching {
        biliClient.searchVideos(
            keyword = query,
            page = 1,
            duration = 0
        ).items
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        NPLogger.w(
            "NERI-PlayerManager",
            "Bili auto source search retry failed: query=$query, error=${error.message}"
        )
        emptyList()
    }
}

private suspend fun PlayerManager.resolveNeteaseAutoBiliCandidate(
    song: SongItem,
    candidate: BiliClient.SearchVideoItem,
    sideEffects: RefreshResolverSideEffects
): SongUrlResult.Success? {
    val videoInfo = fetchBiliAutoSourceVideoInfo(candidate) ?: return null
    val pageMatch = selectNeteaseAutoBiliPage(song, candidate, videoInfo) ?: return null
    if (pageMatch.score < NETEASE_AUTO_SOURCE_MIN_ACCEPT_SCORE) {
        NPLogger.d(
            "NERI-PlayerManager",
            "Skip weak Bili auto source match: song=${song.name}, bvid=${videoInfo.bvid}, score=${pageMatch.score}"
        )
        return null
    }

    val (availableStreams, audioStream) = runCatching {
        biliRepo.getAudioWithDecision(videoInfo.bvid, pageMatch.page.cid)
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        NPLogger.w(
            "NERI-PlayerManager",
            "Bili auto source stream resolve failed: bvid=${videoInfo.bvid}, cid=${pageMatch.page.cid}, error=${error.message}"
        )
        return null
    }

    val selectedStream = audioStream?.takeIf { it.url.isNotBlank() } ?: return null

    val durationMs = pageMatch.page.durationSec
        .takeIf { it > 0 }
        ?.times(1000L)
    if (durationMs != null) {
        sideEffects.updateDuration {
            maybeUpdateSongDuration(song, durationMs)
        }
    }

    NPLogger.w(
        "NERI-PlayerManager",
        "Bili auto source selected: song=${song.name}, bvid=${videoInfo.bvid}, cid=${pageMatch.page.cid}, score=${pageMatch.score}"
    )
    return SongUrlResult.Success(
        url = selectedStream.url,
        candidateUrls = selectedStream.candidateUrls,
        durationMs = durationMs,
        mimeType = selectedStream.mimeType,
        audioInfo = buildBiliPlaybackAudioInfo(selectedStream, availableStreams) {
            getLocalizedString(it)
        },
        cacheKeyOverride = buildNeteaseAutoBiliCacheKey(
            bvid = videoInfo.bvid,
            cid = pageMatch.page.cid,
            selectedStream = selectedStream
        )
    )
}

internal fun buildNeteaseAutoBiliCacheKey(
    bvid: String,
    cid: Long,
    selectedStream: BiliAudioStreamInfo
): String {
    val bvidPart = sanitizeNeteaseAutoBiliCacheKeyPart(bvid)
    val streamPart = selectedStream.id
        ?.let { "id-$it" }
        ?: selectedStream.qualityTag
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?.let { "tag-${sanitizeNeteaseAutoBiliCacheKeyPart(it)}" }
        ?: "quality-${inferBiliQualityKey(selectedStream)}-${selectedStream.bitrateKbps}"
    return "bili-auto-$bvidPart-$cid-$streamPart"
}

private fun sanitizeNeteaseAutoBiliCacheKeyPart(value: String): String {
    return autoSourceCacheKeyUnsafeRegex
        .replace(value.trim(), "_")
        .trim('_')
        .ifBlank { "unknown" }
}

private suspend fun PlayerManager.fetchBiliAutoSourceVideoInfo(
    candidate: BiliClient.SearchVideoItem
): BiliClient.VideoBasicInfo? {
    return runCatching {
        if (candidate.bvid.isNotBlank()) {
            biliClient.getVideoBasicInfoByBvid(candidate.bvid)
        } else {
            biliClient.getVideoBasicInfoByAvid(candidate.aid)
        }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        NPLogger.w(
            "NERI-PlayerManager",
            "Bili auto source video info failed: bvid=${candidate.bvid}, aid=${candidate.aid}, error=${error.message}"
        )
        null
    }
}

private data class BiliAutoSourcePageMatch(
    val page: BiliClient.VideoPage,
    val score: Int
)

private fun selectNeteaseAutoBiliPage(
    song: SongItem,
    candidate: BiliClient.SearchVideoItem,
    videoInfo: BiliClient.VideoBasicInfo
): BiliAutoSourcePageMatch? {
    val candidateScore = scoreNeteaseAutoBiliCandidate(song, candidate)
    return videoInfo.pages
        .filter { it.cid > 0L }
        .map { page ->
            val pageScore = scoreNeteaseAutoBiliText(
                song = song,
                title = "${page.part} ${videoInfo.title}",
                author = videoInfo.ownerName,
                durationSec = page.durationSec
            )
            BiliAutoSourcePageMatch(
                page = page,
                score = candidateScore + pageScore
            )
        }
        .maxByOrNull { it.score }
}

internal fun scoreNeteaseAutoBiliCandidate(
    song: SongItem,
    candidate: BiliClient.SearchVideoItem
): Int {
    return scoreNeteaseAutoBiliText(
        song = song,
        title = candidate.titlePlain,
        author = candidate.author,
        durationSec = candidate.durationSec
    )
}

internal fun scoreNeteaseAutoBiliText(
    song: SongItem,
    title: String,
    author: String,
    durationSec: Int
): Int {
    val songTitle = song.originalName ?: song.name
    val songArtist = song.originalArtist ?: song.artist
    val normalizedTitle = normalizeAutoSourceText(title)
    val normalizedAuthor = normalizeAutoSourceText(author)
    val titleHit = containsAutoSourceText(normalizedTitle, songTitle)
    val artistHit = containsAutoSourceText(normalizedTitle, songArtist) ||
        containsAutoSourceText(normalizedAuthor, songArtist)

    var score = 0
    if (titleHit) {
        score += 55
    } else {
        score += tokenOverlapScore(normalizedTitle, songTitle)
    }
    if (songArtist.isNotBlank() && artistHit) {
        score += 25
    }
    score += durationSimilarityScore(song.durationMs, durationSec)
    return score
}

private fun durationFilterForBiliSearch(durationMs: Long): Int {
    val durationSec = (durationMs / 1000L).toInt()
    return when {
        durationSec <= 0 -> 0
        durationSec < 10 * 60 -> 1
        durationSec < 30 * 60 -> 2
        durationSec < 60 * 60 -> 3
        else -> 4
    }
}

private fun durationSimilarityScore(originalDurationMs: Long, candidateDurationSec: Int): Int {
    if (originalDurationMs <= 0L || candidateDurationSec <= 0) return 0

    val candidateDurationMs = candidateDurationSec * 1000L
    val diffMs = (candidateDurationMs - originalDurationMs).absoluteValue
    return when {
        diffMs <= 8_000L -> 30
        diffMs <= 20_000L -> 22
        diffMs <= 45_000L -> 12
        candidateDurationMs > originalDurationMs * 2 -> -15
        else -> 0
    }
}

private fun containsAutoSourceText(normalizedText: String, rawNeedle: String): Boolean {
    val needle = compactAutoSourceText(rawNeedle)
    if (needle.length < 2) return false
    return normalizedText.replace(" ", "").contains(needle)
}

private fun tokenOverlapScore(normalizedText: String, rawNeedle: String): Int {
    val tokens = normalizeAutoSourceText(rawNeedle)
        .split(' ')
        .filter { it.length >= 2 }
    if (tokens.isEmpty()) return 0

    val hits = tokens.count { normalizedText.contains(it) }
    return when {
        hits == tokens.size -> 35
        hits > 0 -> 18
        else -> 0
    }
}

private fun normalizeAutoSourceQuery(value: String): String {
    return autoSourceWhitespaceRegex
        .replace(value.trim(), " ")
        .trim()
}

private fun normalizeAutoSourceText(value: String): String {
    return autoSourceWhitespaceRegex
        .replace(autoSourceNonTextRegex.replace(value.lowercase(), " "), " ")
        .trim()
}

private fun compactAutoSourceText(value: String): String {
    return normalizeAutoSourceText(value).replace(" ", "")
}
