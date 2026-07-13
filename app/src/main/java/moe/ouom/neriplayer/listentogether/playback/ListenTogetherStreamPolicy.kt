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
    localResolvedStreamUrl: String?
): Boolean {
    val remote = normalizedDirectStreamUrl(remoteStreamUrl) ?: return false
    return remote != normalizedDirectStreamUrl(localResolvedStreamUrl)
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
