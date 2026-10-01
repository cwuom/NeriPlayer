package moe.ouom.neriplayer.data.ltw.playback

import moe.ouom.neriplayer.data.ltw.session.normalizedListenTogetherIdentity

fun normalizedDirectStreamUrl(value: String?): String? {
    val candidate = value?.trim().orEmpty()
    if (candidate.isBlank()) return null
    return if (
        candidate.startsWith("https://", ignoreCase = true) ||
        candidate.startsWith("http://", ignoreCase = true)
    ) {
        candidate
    } else {
        null
    }
}

fun shouldReloadListenTogetherAuthoritativeStream(
    remoteStreamUrl: String?,
    localResolvedStreamUrl: String?,
    localPlaybackRequiresAuthoritativeStream: Boolean = true,
    localPlaybackResolutionPending: Boolean = false,
    pendingAuthoritativeStreamUrl: String? = null
): Boolean {
    if (localPlaybackResolutionPending) return false
    val remote = normalizedDirectStreamUrl(remoteStreamUrl) ?: return false
    val local = normalizedDirectStreamUrl(localResolvedStreamUrl)
    if (remote == local) return false
    if (local != null && !localPlaybackRequiresAuthoritativeStream) return false
    return remote != normalizedDirectStreamUrl(pendingAuthoritativeStreamUrl)
}

fun hasListenTogetherAuthoritativeStreamUrl(
    authoritativeStreamUrls: List<String>,
    localResolvedStreamUrl: String?
): Boolean {
    val local = normalizedDirectStreamUrl(localResolvedStreamUrl) ?: return false
    return authoritativeStreamUrls
        .asSequence()
        .mapNotNull(::normalizedDirectStreamUrl)
        .any { candidate -> candidate == local }
}

fun shouldWaitForListenTogetherAuthoritativeStreamPlayback(
    playerWaitingForAuthoritativeStream: Boolean,
    localTrackMatchesTarget: Boolean,
    localTrackStreamUrl: String?,
    localResolvedStreamUrl: String?
): Boolean {
    if (!playerWaitingForAuthoritativeStream) return false
    if (!localTrackMatchesTarget) return true
    return normalizedDirectStreamUrl(localTrackStreamUrl) == null &&
        normalizedDirectStreamUrl(localResolvedStreamUrl) == null
}

fun shouldReloadForListenTogetherLinkUnavailable(
    isController: Boolean,
    localPlaybackRequiresAuthoritativeStream: Boolean,
    controllerLinkConfirmedUnavailable: Boolean = true,
    alreadyReloadedForStableKey: Boolean = false
): Boolean {
    return !isController &&
        controllerLinkConfirmedUnavailable &&
        localPlaybackRequiresAuthoritativeStream &&
        !alreadyReloadedForStableKey
}

fun shouldRequestListenTogetherControllerLink(
    force: Boolean,
    controllerLinkUnavailable: Boolean
): Boolean {
    return force || !controllerLinkUnavailable
}

fun shouldDeferControllerLinkResolution(
    playbackResolutionPending: Boolean,
    currentTrackStableKey: String?,
    requestedStableKey: String
): Boolean {
    if (!playbackResolutionPending) return false
    val current = currentTrackStableKey.normalizedListenTogetherIdentity() ?: return false
    val requested = requestedStableKey.normalizedListenTogetherIdentity() ?: return false
    return current == requested
}

fun shouldRetryControllerLinkResolution(
    attempt: Int,
    maximumAttempts: Int,
    hasShareableStream: Boolean,
    playbackResolutionPending: Boolean
): Boolean {
    if (maximumAttempts <= 0) return false
    return !hasShareableStream &&
        !playbackResolutionPending &&
        attempt + 1 < maximumAttempts
}

fun shouldPublishControllerLinkUnavailable(
    attempt: Int,
    maximumAttempts: Int,
    hasShareableStream: Boolean,
    playbackResolutionPending: Boolean
): Boolean {
    if (maximumAttempts <= 0) return false
    return !hasShareableStream &&
        !playbackResolutionPending &&
        attempt + 1 >= maximumAttempts
}

fun shouldAwaitListenTogetherSharedStreamFallback(
    listenerAudioLinkSharingActive: Boolean,
    localResolutionRequiresSharedStream: Boolean,
    controllerLinkConfirmedUnavailable: Boolean,
    hasAuthoritativeStream: Boolean
): Boolean {
    return listenerAudioLinkSharingActive &&
        localResolutionRequiresSharedStream &&
        !controllerLinkConfirmedUnavailable &&
        !hasAuthoritativeStream
}

fun shouldSuppressListenTogetherResolverError(
    listenerAudioLinkSharingActive: Boolean,
    controllerLinkConfirmedUnavailable: Boolean
): Boolean {
    return listenerAudioLinkSharingActive && !controllerLinkConfirmedUnavailable
}

fun shouldPreferListenTogetherSourceBeforeNeteaseFallback(
    listenerAudioLinkSharingActive: Boolean
): Boolean {
    return listenerAudioLinkSharingActive
}

fun shouldShowListenTogetherPreviewClipNotice(
    isPreviewClip: Boolean,
    listenerAudioLinkSharingActive: Boolean,
    controllerLinkConfirmedUnavailable: Boolean
): Boolean {
    return !isPreviewClip ||
        !listenerAudioLinkSharingActive ||
        controllerLinkConfirmedUnavailable
}
