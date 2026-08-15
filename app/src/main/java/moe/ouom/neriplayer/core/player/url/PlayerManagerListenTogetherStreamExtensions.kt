package moe.ouom.neriplayer.core.player.url

import java.security.MessageDigest
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
import moe.ouom.neriplayer.listentogether.mapping.toSongItem
import moe.ouom.neriplayer.listentogether.playback.currentTrack
import moe.ouom.neriplayer.listentogether.mapping.trustedListenTogetherStreamUrls
import moe.ouom.neriplayer.listentogether.playback.sameTrackAs
import moe.ouom.neriplayer.listentogether.protocol.ListenTogetherRoomStatuses
import moe.ouom.neriplayer.core.player.watchdog.currentPlaybackCandidate

internal const val LISTEN_TOGETHER_STREAM_CACHE_KEY_PREFIX = "listen-together-stream"

internal fun PlayerManager.currentListenTogetherShareableStreamUrls(): List<String> {
    val currentSong = _currentSongFlow.value
    return collectListenTogetherShareableStreamUrls(
        currentMediaUrl = _currentMediaUrl.value,
        currentPlaybackCandidate = currentPlaybackCandidate(),
        activePlaybackCandidates = activePlaybackCandidates,
        allowUntrackedCurrentStream = currentSong != null &&
            (isYouTubeMusicTrack(currentSong) || isBiliTrack(currentSong))
    )
}

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
    val audioInfo = listenTogetherFallbackAudioInfo(song)
    val candidates = listenTogetherFallbackStreamUrls(song).map { streamUrl ->
        PlaybackUrlCandidate(
            url = streamUrl,
            audioInfo = audioInfo,
            cacheKeyOverride = listenTogetherStreamCacheKey(song.stableKey(), streamUrl)
        )
    }
    val primary = candidates.firstOrNull() ?: return null
    return SongUrlResult.Success(
        url = primary.url,
        audioInfo = audioInfo,
        cacheKeyOverride = primary.cacheKeyOverride,
        fallbackCandidates = candidates.drop(1)
    )
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
    listenTogetherFallback: SongUrlResult.Success?
): SongUrlResult {
    listenTogetherFallback ?: return localResult
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
            } else {
                localResult.copy(
                    fallbackCandidates = localResult.fallbackCandidates +
                        listenTogetherFallback.playbackCandidates()
                )
            }
        }
        SongUrlResult.Failure,
        SongUrlResult.RequiresLogin -> listenTogetherFallback
        SongUrlResult.WaitingForAuthoritativeStream -> localResult
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
