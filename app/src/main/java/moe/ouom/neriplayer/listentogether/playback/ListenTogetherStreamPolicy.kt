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
    pendingAuthoritativeStreamUrl: String? = null
): Boolean {
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
    alreadyReloadedForStableKey: Boolean = false
): Boolean {
    return !isController &&
        localPlaybackRequiresAuthoritativeStream &&
        !alreadyReloadedForStableKey
}

internal fun shouldRequestListenTogetherControllerLink(
    force: Boolean,
    controllerLinkUnavailable: Boolean
): Boolean {
    return force || !controllerLinkUnavailable
}
