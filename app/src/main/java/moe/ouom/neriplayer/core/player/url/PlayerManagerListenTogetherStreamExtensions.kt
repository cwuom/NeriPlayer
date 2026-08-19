package moe.ouom.neriplayer.core.player.url

import java.security.MessageDigest
import kotlin.math.abs
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.PlaybackQualityOption
import moe.ouom.neriplayer.core.player.model.PlaybackUrlCandidate
import moe.ouom.neriplayer.core.player.quality.effectiveBiliQuality
import moe.ouom.neriplayer.core.player.quality.effectiveNeteaseQuality
import moe.ouom.neriplayer.core.player.quality.effectiveYouTubeQuality
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.listentogether.mapping.MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherChannels
import moe.ouom.neriplayer.listentogether.mapping.toSongItem
import moe.ouom.neriplayer.listentogether.playback.currentTrack
import moe.ouom.neriplayer.listentogether.mapping.trustedListenTogetherStreamUrls
import moe.ouom.neriplayer.listentogether.playback.sameTrackAs
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.core.player.watchdog.currentPlaybackCandidate

internal const val LISTEN_TOGETHER_STREAM_CACHE_KEY_PREFIX = "listen-together-stream"
private const val LISTEN_TOGETHER_QUALITY_FRAGMENT_KEY = "neriplayer-ltw-quality="

internal fun PlayerManager.currentListenTogetherShareableStreamUrls(): List<String> {
    val currentSong = _currentSongFlow.value
    val currentSource = currentSong?.let(::listenTogetherPlaybackSource)
    if (
        !shouldPublishCurrentListenTogetherStream(
            listenTogetherActive = isListenTogetherActive(),
            isCurrentUserController = isCurrentUserControllerInListenTogether()
        )
    ) {
        return emptyList()
    }
    val currentCandidate = currentPlaybackCandidate()
        ?.takeUnless(::isListenTogetherSessionStreamCandidate)
    val shareableCandidates = activePlaybackCandidates
        .filterNot(::isListenTogetherSessionStreamCandidate)
    return collectListenTogetherShareableStreamUrls(
        currentMediaUrl = _currentMediaUrl.value,
        currentPlaybackCandidate = currentCandidate,
        activePlaybackCandidates = shareableCandidates,
        allowUntrackedCurrentStream = currentSong != null &&
            (isYouTubeMusicTrack(currentSong) || isBiliTrack(currentSong))
    ).map { streamUrl ->
        val matchingCandidate = sequenceOf(currentCandidate)
            .filterNotNull()
            .plus(shareableCandidates.asSequence())
            .firstOrNull { candidate ->
                candidate.playbackUrls().any { candidateUrl ->
                    stripListenTogetherStreamQualityMetadata(candidateUrl) ==
                        stripListenTogetherStreamQualityMetadata(streamUrl)
                }
            }
        val audioInfo = resolveListenTogetherPublishedStreamAudioInfo(
            matchingCandidate = matchingCandidate,
            currentCandidate = currentCandidate,
            currentAudioInfo = _currentPlaybackAudioInfo.value
        )
        decorateListenTogetherStreamUrl(
            streamUrl = streamUrl,
            source = audioInfo?.source ?: currentSong?.let(::listenTogetherPlaybackSource)
                ?: return@map streamUrl,
            qualityKey = audioInfo?.qualityKey
        )
    }.distinct()
        .take(
            currentSource?.let(::maxListenTogetherStreamUrlCandidates)
                ?: MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES
        )
}

internal fun shouldPublishCurrentListenTogetherStream(
    listenTogetherActive: Boolean,
    isCurrentUserController: Boolean
): Boolean {
    return !listenTogetherActive || isCurrentUserController
}

private fun isListenTogetherSessionStreamCandidate(candidate: PlaybackUrlCandidate): Boolean {
    return candidate.cacheKeyOverride?.startsWith(LISTEN_TOGETHER_STREAM_CACHE_KEY_PREFIX) == true
}

internal fun resolveListenTogetherPublishedStreamAudioInfo(
    matchingCandidate: PlaybackUrlCandidate?,
    currentCandidate: PlaybackUrlCandidate?,
    currentAudioInfo: PlaybackAudioInfo?
): PlaybackAudioInfo? {
    return matchingCandidate?.audioInfo
        ?: currentAudioInfo.takeIf {
            matchingCandidate != null && matchingCandidate === currentCandidate
        }
}

internal fun PlayerManager.listenTogetherPlaybackSource(song: SongItem): PlaybackAudioSource {
    return when {
        song.channelId == ListenTogetherChannels.YOUTUBE_MUSIC || isYouTubeMusicTrack(song) ->
            PlaybackAudioSource.YOUTUBE_MUSIC
        song.channelId == ListenTogetherChannels.BILIBILI || isBiliTrack(song) ->
            PlaybackAudioSource.BILIBILI
        else -> PlaybackAudioSource.NETEASE
    }
}

internal fun decorateListenTogetherStreamUrl(
    streamUrl: String,
    source: PlaybackAudioSource,
    qualityKey: String?
): String {
    val normalizedQualityKey = normalizeListenTogetherQualityKey(source, qualityKey)
        ?: return streamUrl
    val marker = "$LISTEN_TOGETHER_QUALITY_FRAGMENT_KEY${listenTogetherSourceKey(source)}:"
    val baseUrl = streamUrl.substringBefore('#')
    val existingFragments = streamUrl
        .substringAfter('#', missingDelimiterValue = "")
        .split('&')
        .filter { it.isNotBlank() && !it.startsWith(LISTEN_TOGETHER_QUALITY_FRAGMENT_KEY) }
    val fragments = existingFragments + "$marker$normalizedQualityKey"
    return if (fragments.isEmpty()) baseUrl else "$baseUrl#${fragments.joinToString("&")}"
}

internal fun listenTogetherQualityKeyFromStreamUrl(
    streamUrl: String,
    source: PlaybackAudioSource
): String? {
    val sourceKey = listenTogetherSourceKey(source)
    val prefix = "$LISTEN_TOGETHER_QUALITY_FRAGMENT_KEY$sourceKey:"
    return streamUrl
        .substringAfter('#', missingDelimiterValue = "")
        .split('&')
        .firstOrNull { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.let { normalizeListenTogetherQualityKey(source, it) }
}

internal fun stripListenTogetherStreamQualityMetadata(streamUrl: String): String {
    val fragment = streamUrl.substringAfter('#', missingDelimiterValue = "")
    if (!fragment.contains(LISTEN_TOGETHER_QUALITY_FRAGMENT_KEY)) return streamUrl
    val remainingFragments = fragment
        .split('&')
        .filter { it.isNotBlank() && !it.startsWith(LISTEN_TOGETHER_QUALITY_FRAGMENT_KEY) }
    val baseUrl = streamUrl.substringBefore('#')
    return if (remainingFragments.isEmpty()) {
        baseUrl
    } else {
        "$baseUrl#${remainingFragments.joinToString("&")}"
    }
}

internal fun orderListenTogetherStreamUrlsForPreference(
    streamUrls: List<String>,
    source: PlaybackAudioSource,
    preferredQualityKey: String
): List<String> {
    val preferredRank = listenTogetherQualityRank(source, preferredQualityKey)
    val fallbackRank = preferredRank ?: 0
    return streamUrls
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.substringBefore('#') }
        .withIndex()
        .sortedWith(
            compareBy<IndexedValue<String>> {
                val rank = listenTogetherQualityKeyFromStreamUrl(it.value, source)
                    ?.let { key -> listenTogetherQualityRank(source, key) }
                rank?.let { qualityRank -> abs(qualityRank - fallbackRank) } ?: Int.MAX_VALUE
            }.thenBy {
                val rank = listenTogetherQualityKeyFromStreamUrl(it.value, source)
                    ?.let { key -> listenTogetherQualityRank(source, key) }
                when {
                    rank == null -> 2
                    rank <= fallbackRank -> 0
                    else -> 1
                }
            }.thenByDescending {
                listenTogetherQualityKeyFromStreamUrl(it.value, source)
                    ?.let { key -> listenTogetherQualityRank(source, key) }
                    ?: Int.MIN_VALUE
            }.thenBy { it.index }
        )
        .map { it.value }
}

internal fun listenTogetherQualityRank(
    source: PlaybackAudioSource,
    qualityKey: String?
): Int? {
    val normalized = normalizeListenTogetherQualityKey(source, qualityKey) ?: return null
    return when (source) {
        PlaybackAudioSource.NETEASE -> NETEASE_LISTEN_TOGETHER_QUALITY_ORDER
        PlaybackAudioSource.BILIBILI -> BILI_LISTEN_TOGETHER_QUALITY_ORDER
        PlaybackAudioSource.YOUTUBE_MUSIC -> YOUTUBE_LISTEN_TOGETHER_QUALITY_ORDER
        PlaybackAudioSource.LOCAL -> emptyList()
    }.indexOf(normalized).takeIf { it >= 0 }
}

internal fun listenTogetherQualityMatchesPreference(
    source: PlaybackAudioSource,
    actualQualityKey: String?,
    preferredQualityKey: String?
): Boolean {
    val actual = normalizeListenTogetherQualityKey(source, actualQualityKey) ?: return false
    val preferred = normalizeListenTogetherQualityKey(source, preferredQualityKey) ?: return false
    return actual == preferred
}

private fun normalizeListenTogetherQualityKey(
    source: PlaybackAudioSource,
    qualityKey: String?
): String? {
    val normalized = qualityKey?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return when (source) {
        PlaybackAudioSource.NETEASE -> normalized.takeIf {
            it in NETEASE_LISTEN_TOGETHER_QUALITY_ORDER
        }
        PlaybackAudioSource.BILIBILI -> normalized.takeIf {
            it in BILI_LISTEN_TOGETHER_QUALITY_ORDER
        }
        PlaybackAudioSource.YOUTUBE_MUSIC -> normalized.takeIf {
            it in YOUTUBE_LISTEN_TOGETHER_QUALITY_ORDER
        }
        PlaybackAudioSource.LOCAL -> null
    }
}

private fun listenTogetherSourceKey(source: PlaybackAudioSource): String {
    return when (source) {
        PlaybackAudioSource.NETEASE -> "netease"
        PlaybackAudioSource.BILIBILI -> "bili"
        PlaybackAudioSource.YOUTUBE_MUSIC -> "youtube"
        PlaybackAudioSource.LOCAL -> "local"
    }
}

private val NETEASE_LISTEN_TOGETHER_QUALITY_ORDER = listOf(
    "standard",
    "higher",
    "exhigh",
    "lossless",
    "hires",
    "jyeffect",
    "sky",
    "jymaster"
)

private val BILI_LISTEN_TOGETHER_QUALITY_ORDER = listOf(
    "low",
    "medium",
    "high",
    "lossless",
    "hires",
    "dolby"
)

private val YOUTUBE_LISTEN_TOGETHER_QUALITY_ORDER = listOf(
    "low",
    "medium",
    "high",
    "very_high"
)

internal fun isShareableListenTogetherStreamResolution(result: SongUrlResult): Boolean {
    return shareableListenTogetherStreamUrls(result).isNotEmpty()
}

internal fun shareableListenTogetherStreamUrls(result: SongUrlResult): List<String> {
    val success = result as? SongUrlResult.Success ?: return emptyList()
    return success.playbackCandidates()
        .asSequence()
        .filterNot { candidate -> candidate.isPreviewClip }
        .flatMap { candidate -> candidate.playbackUrls().asSequence() }
        .filter(::isDirectHttpStreamUrl)
        .distinct()
        .take(MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES)
        .toList()
}

internal fun collectListenTogetherShareableStreamUrls(
    currentMediaUrl: String?,
    currentPlaybackCandidate: PlaybackUrlCandidate?,
    activePlaybackCandidates: List<PlaybackUrlCandidate>,
    allowUntrackedCurrentStream: Boolean
): List<String> {
    val normalizedCurrentMediaUrl = currentMediaUrl?.trim().orEmpty()
    return buildList {
        when {
            currentPlaybackCandidate == null &&
                allowUntrackedCurrentStream &&
                isDirectHttpStreamUrl(normalizedCurrentMediaUrl) -> {
                add(normalizedCurrentMediaUrl)
            }

            currentPlaybackCandidate?.isPreviewClip == false &&
                currentPlaybackCandidate.playbackUrls().any {
                    it == normalizedCurrentMediaUrl
                } -> {
                add(normalizedCurrentMediaUrl)
            }
        }
        activePlaybackCandidates
            .asSequence()
            .filterNot { candidate -> candidate.isPreviewClip }
            .flatMap { candidate -> candidate.playbackUrls().asSequence() }
            .filter(::isDirectHttpStreamUrl)
            .forEach(::add)
    }.map { it.trim() }
        .distinct()
        .take(MAX_LISTEN_TOGETHER_STREAM_URL_CANDIDATES)
}

private fun isDirectHttpStreamUrl(value: String): Boolean {
    return value.startsWith("https://", ignoreCase = true) ||
        value.startsWith("http://", ignoreCase = true)
}

internal fun PlayerManager.listenTogetherFallbackStreamUrls(song: SongItem): List<String> {
    if (!isListenTogetherActive() || isCurrentUserControllerInListenTogether()) return emptyList()
    val room = activeListenTogetherRoomState() ?: return emptyList()
    if (!room.settings.shareAudioLinks || room.roomStatus != ListenTogetherRoomStatuses.ACTIVE) {
        return emptyList()
    }
    val targetTrack = room.currentTrack() ?: return emptyList()
    if (!song.sameTrackAs(targetTrack.toSongItem())) return emptyList()
    return trustedListenTogetherStreamUrls(
        channelId = targetTrack.channelId,
        streamUrls = targetTrack.streamUrls,
        legacyStreamUrl = targetTrack.streamUrl
    )
}

internal fun PlayerManager.listenTogetherFallbackResult(song: SongItem): SongUrlResult.Success? {
    val source = listenTogetherPlaybackSource(song)
    val preferredQualityKey = when (source) {
        PlaybackAudioSource.NETEASE -> effectiveNeteaseQuality()
        PlaybackAudioSource.BILIBILI -> effectiveBiliQuality()
        PlaybackAudioSource.YOUTUBE_MUSIC -> effectiveYouTubeQuality()
        PlaybackAudioSource.LOCAL -> ""
    }
    val legacyAudioInfo = listenTogetherFallbackAudioInfo(song)
    val candidates = orderListenTogetherStreamUrlsForPreference(
        streamUrls = listenTogetherFallbackStreamUrls(song),
        source = source,
        preferredQualityKey = preferredQualityKey
    ).map { streamUrl ->
        val actualQualityKey = listenTogetherQualityKeyFromStreamUrl(streamUrl, source)
            ?: legacyAudioInfo.qualityKey
            ?: preferredQualityKey
        PlaybackUrlCandidate(
            url = streamUrl,
            audioInfo = buildListenTogetherFallbackAudioInfo(
                source = source,
                preferredQualityKey = actualQualityKey,
                getLocalizedString = { getLocalizedString(it) }
            ),
            cacheKeyOverride = listenTogetherStreamCacheKey(song.stableKey(), streamUrl)
        )
    }
    val primary = candidates.firstOrNull() ?: return null
    return SongUrlResult.Success(
        url = primary.url,
        audioInfo = primary.audioInfo,
        cacheKeyOverride = primary.cacheKeyOverride,
        fallbackCandidates = candidates.drop(1)
    )
}

internal fun PlayerManager.listenTogetherPreferredQualityKey(song: SongItem): String? {
    return when (listenTogetherPlaybackSource(song)) {
        PlaybackAudioSource.NETEASE -> effectiveNeteaseQuality()
        PlaybackAudioSource.BILIBILI -> effectiveBiliQuality()
        PlaybackAudioSource.YOUTUBE_MUSIC -> effectiveYouTubeQuality()
        PlaybackAudioSource.LOCAL -> null
    }
}

private fun PlayerManager.listenTogetherFallbackAudioInfo(song: SongItem): PlaybackAudioInfo {
    val currentAudioInfo = _currentPlaybackAudioInfo.value
        ?.takeIf { _currentSongFlow.value?.sameTrackAs(song) == true }
        ?.takeIf { !it.qualityLabel.isNullOrBlank() }
    if (currentAudioInfo != null) return currentAudioInfo

    return when {
        isYouTubeMusicTrack(song) -> buildListenTogetherFallbackAudioInfo(
            source = PlaybackAudioSource.YOUTUBE_MUSIC,
            preferredQualityKey = effectiveYouTubeQuality(),
            getLocalizedString = { getLocalizedString(it) }
        )
        isBiliTrack(song) -> buildListenTogetherFallbackAudioInfo(
            source = PlaybackAudioSource.BILIBILI,
            preferredQualityKey = effectiveBiliQuality(),
            getLocalizedString = { getLocalizedString(it) }
        )
        else -> buildListenTogetherFallbackAudioInfo(
            source = PlaybackAudioSource.NETEASE,
            preferredQualityKey = effectiveNeteaseQuality(),
            getLocalizedString = { getLocalizedString(it) }
        )
    }
}

internal fun buildListenTogetherFallbackAudioInfo(
    source: PlaybackAudioSource,
    preferredQualityKey: String,
    getLocalizedString: (Int) -> String
): PlaybackAudioInfo {
    return when (source) {
        PlaybackAudioSource.NETEASE -> buildNeteaseOfflineCacheAudioInfo(
            preferredQualityKey = preferredQualityKey,
            getLocalizedString = getLocalizedString
        )
        PlaybackAudioSource.YOUTUBE_MUSIC -> buildYouTubeOfflineCacheAudioInfo(
            preferredQualityKey = preferredQualityKey,
            getLocalizedString = getLocalizedString
        )
        PlaybackAudioSource.BILIBILI -> {
            val qualityKey = preferredQualityKey.trim().lowercase().ifBlank { "high" }
            PlaybackAudioInfo(
                source = PlaybackAudioSource.BILIBILI,
                qualityKey = qualityKey,
                qualityLabel = qualityLabelForBili(qualityKey, getLocalizedString),
                qualityOptions = LISTEN_TOGETHER_BILI_QUALITY_OPTIONS.map { key ->
                    PlaybackQualityOption(key, qualityLabelForBili(key, getLocalizedString))
                }
            )
        }
        PlaybackAudioSource.LOCAL -> PlaybackAudioInfo(source = PlaybackAudioSource.LOCAL)
    }
}

private val LISTEN_TOGETHER_BILI_QUALITY_OPTIONS = listOf(
    "dolby",
    "hires",
    "lossless",
    "high",
    "medium",
    "low"
)

internal fun mergeListenTogetherFallbackResult(
    localResult: SongUrlResult,
    listenTogetherFallback: SongUrlResult.Success?,
    preferredQualityKey: String? = null
): SongUrlResult {
    listenTogetherFallback ?: return localResult
    val fallbackMatchesPreference = listenTogetherFallback.audioInfo?.let { audioInfo ->
        listenTogetherQualityKeyFromStreamUrl(
            streamUrl = listenTogetherFallback.url,
            source = audioInfo.source
        )?.let { actualQualityKey ->
            listenTogetherQualityMatchesPreference(
                source = audioInfo.source,
                actualQualityKey = actualQualityKey,
                preferredQualityKey = preferredQualityKey
            )
        }
    } == true
    return when (localResult) {
        is SongUrlResult.Success -> {
            if (localResult.isPreviewClip) {
                listenTogetherFallback.copy(
                    durationMs = listenTogetherFallback.durationMs ?: localResult.durationMs,
                    mimeType = listenTogetherFallback.mimeType ?: localResult.mimeType,
                    audioInfo = listenTogetherFallback.audioInfo ?: localResult.audioInfo,
                    fallbackCandidates = listenTogetherFallback.fallbackCandidates +
                        localResult.fallbackCandidates.filterNot { candidate ->
                            candidate.isPreviewClip
                        }
                )
            } else if (fallbackMatchesPreference) {
                listenTogetherFallback.copy(
                    durationMs = listenTogetherFallback.durationMs ?: localResult.durationMs,
                    mimeType = listenTogetherFallback.mimeType ?: localResult.mimeType,
                    fallbackCandidates = listenTogetherFallback.fallbackCandidates +
                        localResult.playbackCandidates()
                )
            } else {
                localResult.copy(
                    fallbackCandidates = localResult.fallbackCandidates +
                        listenTogetherFallback.playbackCandidates()
                )
            }
        }
        SongUrlResult.Failure,
        SongUrlResult.RequiresLogin -> listenTogetherFallback
        SongUrlResult.WaitingForAuthoritativeStream ->
            if (fallbackMatchesPreference) listenTogetherFallback else localResult
    }
}

internal fun shouldUseDirectStreamShortcut(
    forceRefresh: Boolean,
    hasListenTogetherFallback: Boolean
): Boolean {
    return !forceRefresh && !hasListenTogetherFallback
}

internal fun PlayerManager.isCurrentListenTogetherFallbackMediaUrl(): Boolean {
    val currentUrl = _currentMediaUrl.value ?: return false
    val candidate = currentPlaybackCandidate() ?: return false
    return candidate.url == currentUrl &&
        candidate.cacheKeyOverride?.startsWith(LISTEN_TOGETHER_STREAM_CACHE_KEY_PREFIX) == true
}

internal fun hasUsableListenTogetherLocalDirectStream(
    currentSongMatchesTarget: Boolean,
    currentSongHasDirectStream: Boolean,
    currentMediaHasDirectStream: Boolean,
    currentPlaybackCandidateIsPreview: Boolean
): Boolean {
    if (!currentSongMatchesTarget || currentPlaybackCandidateIsPreview) return false
    return currentSongHasDirectStream || currentMediaHasDirectStream
}

internal fun PlayerManager.currentPlaybackRequiresListenTogetherAuthoritativeStream(): Boolean {
    if (_currentMediaUrl.value.isNullOrBlank()) return true
    return currentPlaybackCandidate()?.isPreviewClip == true
}

internal fun resolvePlaybackAudioInfoForListenTogetherStreamCandidate(
    candidate: PlaybackUrlCandidate?,
    resolvedAudioInfo: PlaybackAudioInfo?,
    existingAudioInfo: PlaybackAudioInfo?
): PlaybackAudioInfo? {
    val selectedAudioInfo = candidate?.audioInfo ?: resolvedAudioInfo
    if (
        candidate?.cacheKeyOverride?.startsWith(LISTEN_TOGETHER_STREAM_CACHE_KEY_PREFIX) != true
    ) {
        return selectedAudioInfo
    }
    return selectedAudioInfo ?: existingAudioInfo
}

internal fun listenTogetherStreamCacheKey(stableKey: String, streamUrl: String): String {
    return "$LISTEN_TOGETHER_STREAM_CACHE_KEY_PREFIX-${sha256Hex(stableKey)}" +
        "-${sha256Hex(streamUrl)}"
}

private fun sha256Hex(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(24)
}
