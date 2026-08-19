package moe.ouom.neriplayer.listentogether.playback

internal fun normalizedDirectStreamUrl(value: String?): String? {
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

internal fun shouldReloadListenTogetherAuthoritativeStream(
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

internal fun hasListenTogetherAuthoritativeStreamUrl(
    authoritativeStreamUrls: List<String>,
    localResolvedStreamUrl: String?
): Boolean {
    val local = normalizedDirectStreamUrl(localResolvedStreamUrl) ?: return false
    return authoritativeStreamUrls
        .asSequence()
        .mapNotNull(::normalizedDirectStreamUrl)
        .any { candidate -> candidate == local }
}

internal fun shouldWaitForListenTogetherAuthoritativeStreamPlayback(
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

internal fun shouldReloadForListenTogetherLinkUnavailable(
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

internal fun shouldRequestListenTogetherControllerLink(
    force: Boolean,
    controllerLinkUnavailable: Boolean
): Boolean {
    return force || !controllerLinkUnavailable
}

internal fun shouldDeferControllerLinkResolution(
    playbackResolutionPending: Boolean,
    currentTrackStableKey: String?,
    requestedStableKey: String
): Boolean {
    if (!playbackResolutionPending) return false
    val current = currentTrackStableKey?.trim().orEmpty()
    val requested = requestedStableKey.trim()
    return current.isNotEmpty() && requested.isNotEmpty() && current == requested
}

internal fun shouldRetryControllerLinkResolution(
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

internal fun shouldPublishControllerLinkUnavailable(
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

internal fun shouldAwaitListenTogetherSharedStreamFallback(
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

internal fun shouldSuppressListenTogetherResolverError(
    listenerAudioLinkSharingActive: Boolean,
    controllerLinkConfirmedUnavailable: Boolean
): Boolean {
    return listenerAudioLinkSharingActive && !controllerLinkConfirmedUnavailable
}

internal fun shouldPreferListenTogetherSourceBeforeNeteaseFallback(
    listenerAudioLinkSharingActive: Boolean
): Boolean {
    return listenerAudioLinkSharingActive
}

internal fun shouldShowListenTogetherPreviewClipNotice(
    isPreviewClip: Boolean,
    listenerAudioLinkSharingActive: Boolean,
    controllerLinkConfirmedUnavailable: Boolean
): Boolean {
    return !isPreviewClip ||
        !listenerAudioLinkSharingActive ||
        controllerLinkConfirmedUnavailable
}
