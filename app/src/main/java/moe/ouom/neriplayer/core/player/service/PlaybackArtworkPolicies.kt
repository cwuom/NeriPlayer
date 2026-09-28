package moe.ouom.neriplayer.core.player.service

import android.net.Uri
import androidx.core.net.toUri
import java.net.URLDecoder

internal const val MEDIA_ARTWORK_RETRY_COOLDOWN_MS = 3_000L

internal fun normalizedArtworkReference(reference: String?): String? =
    reference?.trim()?.takeIf(String::isNotBlank)

internal fun resolveImmediateArtworkSource(
    isLocal: Boolean,
    customCover: String?,
    isDirectoryReference: (String) -> Boolean,
    localCover: () -> String?,
    displayedCover: () -> String?,
): String? {
    if (!isLocal) return nonLocalImmediateCover(displayedCover)
    return usableCustomCover(customCover, isDirectoryReference)
        ?: resolveImmediateLocalCover(localCover, displayedCover)
}

private fun nonLocalImmediateCover(displayedCover: () -> String?): String? =
    displayedCover()?.takeIf(String::isNotBlank)

private fun usableCustomCover(
    customCover: String?, isDirectoryReference: (String) -> Boolean,
): String? {
    val custom = normalizedArtworkReference(customCover) ?: return null
    return custom.takeUnless(isDirectoryReference)
}

private fun resolveImmediateLocalCover(
    localCover: () -> String?,
    displayedCover: () -> String?,
): String? {
    val local = normalizedArtworkReference(localCover())
    if (local != null) return local
    return usableImmediateDisplayCover(displayedCover())
}

private fun usableImmediateDisplayCover(reference: String?): String? {
    val displayed = normalizedArtworkReference(reference) ?: return null
    return displayed.takeUnless { resolveRemoteMetadataArtworkUri(it) != null }
}

internal fun isLocalCoverReference(reference: String?): Boolean {
    val normalized = reference?.trim().orEmpty()
    return normalized.startsWith("content://", ignoreCase = true) ||
        normalized.startsWith("file:", ignoreCase = true) ||
        normalized.startsWith("/")
}

internal fun coverReferenceFileName(reference: String?): String? {
    val normalized = normalizedArtworkReference(reference) ?: return null
    val rawSegment = referenceLastSegment(normalized) ?: return null
    val fileName = decodeReferenceSegment(rawSegment).substringAfterLast('/').trim()
    return fileName.takeIf(::isValidCoverFileName)
}

private fun referenceLastSegment(reference: String): String? {
    val parsed = parsedReferenceLastSegment(reference)
    return parsed ?: normalizedArtworkReference(reference.substringAfterLast('/'))
}

private fun parsedReferenceLastSegment(reference: String): String? =
    normalizedArtworkReference(runCatching { reference.toUri().lastPathSegment }.getOrNull())

private fun decodeReferenceSegment(segment: String): String = runCatching {
    URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
}.getOrElse { Uri.decode(segment) }

private fun isValidCoverFileName(fileName: String): Boolean =
    fileName.isNotBlank() && fileName != "." && fileName != ".."

internal fun shouldCommitCoverSourceRecovery(
    currentSongKey: String?,
    expectedSongKey: String,
    currentCoverSource: String?,
    expectedCoverSource: String?
): Boolean {
    return currentSongKey == expectedSongKey && currentCoverSource == expectedCoverSource
}

internal fun isArtworkReadyForSource(
    artworkPresent: Boolean,
    artworkOwnerSongKey: String?,
    currentSongKey: String?,
    artworkSource: String?,
    requestedSource: String?
): Boolean {
    val normalizedRequestedSource = normalizedArtworkReference(requestedSource) ?: return false
    return artworkPresent && matchesArtworkIdentity(
        artworkOwnerSongKey, currentSongKey, artworkSource, normalizedRequestedSource,
    )
}

private fun matchesArtworkIdentity(
    ownerSongKey: String?, songKey: String?, artworkSource: String?, source: String,
): Boolean = ownerSongKey == songKey && artworkSource == source

internal fun resolveMetadataCoverSource(
    songKey: String?,
    immediateCoverSource: String?,
    retainedSongKey: String?,
    retainedCoverSource: String?
): String? {
    normalizedArtworkReference(immediateCoverSource)?.let { return it }
    return retainedCoverSource?.takeIf {
        retainedSongKey == songKey && it.isNotBlank()
    }
}

internal fun resolveMetadataCoverSourceWithRecovery(
    songKey: String?,
    immediateCoverSource: String?,
    retainedSongKey: String?,
    retainedCoverSource: String?,
    recoverySongKey: String?,
    recoveryCoverSource: String?,
    recoveryImmediateCoverSource: String?
): String? {
    val immediate = normalizedArtworkReference(immediateCoverSource)
    val recoveryImmediate = normalizedArtworkReference(recoveryImmediateCoverSource)
    val recovery = normalizedArtworkReference(recoveryCoverSource)
    if (canUseRecoveredCover(songKey, recoverySongKey, recovery, immediate, recoveryImmediate)) {
        return recovery
    }
    return resolveMetadataCoverSource(
        songKey = songKey,
        immediateCoverSource = immediate,
        retainedSongKey = retainedSongKey,
        retainedCoverSource = retainedCoverSource
    )
}

private fun canUseRecoveredCover(
    songKey: String?, recoverySongKey: String?, recovery: String?,
    immediate: String?, recoveryImmediate: String?,
): Boolean = recoverySongKey == songKey && recovery != null &&
    (immediate == null || immediate == recoveryImmediate)

internal fun shouldRequestArtworkLoad(
    coverSource: String?,
    artworkReady: Boolean,
    inFlightCoverSource: String?,
    lastFailedCoverSource: String?,
    lastFailureAtElapsedRealtime: Long,
    nowElapsedRealtime: Long,
    retryCooldownMs: Long = MEDIA_ARTWORK_RETRY_COOLDOWN_MS,
    currentSongKey: String? = null,
    inFlightSongKey: String? = null,
    lastFailedSongKey: String? = null,
): Boolean {
    val normalizedSource = normalizedArtworkReference(coverSource) ?: return false
    if (artworkReady) {
        return false
    }
    if (hasMatchingArtworkRequest(normalizedSource, currentSongKey, inFlightCoverSource, inFlightSongKey)) return false
    if (!hasRecentArtworkFailure(
            normalizedSource, currentSongKey, lastFailedCoverSource,
            lastFailedSongKey, lastFailureAtElapsedRealtime,
        )) return true
    val elapsed = nowElapsedRealtime - lastFailureAtElapsedRealtime
    return elapsed !in 0L..<retryCooldownMs
}

private fun hasMatchingArtworkRequest(
    source: String, songKey: String?, inFlightSource: String?, inFlightSongKey: String?,
): Boolean = inFlightSource == source && (inFlightSongKey == null || inFlightSongKey == songKey)

private fun hasRecentArtworkFailure(
    source: String, songKey: String?, failedSource: String?, failedSongKey: String?, failedAt: Long,
): Boolean = failedSource == source && (failedSongKey == null || failedSongKey == songKey) && failedAt > 0L

internal fun shouldDeferArtworkRetryToCoverResolver(
    isLocalCover: Boolean,
    coverResolutionRequested: Boolean
): Boolean = isLocalCover && coverResolutionRequested

internal fun shouldAllowServiceRemoteCoverFallback(
    isLocalSong: Boolean,
    hasExplicitCustomCover: Boolean
): Boolean = !isLocalSong || hasExplicitCustomCover

internal fun shouldAcceptArtworkLoadCallback(
    requestGeneration: Long,
    currentGeneration: Long,
    inFlightGeneration: Long,
    requestSource: String?,
    inFlightSource: String?,
    requestSongKey: String?,
    inFlightSongKey: String?
): Boolean {
    return requestGeneration == currentGeneration &&
        requestGeneration == inFlightGeneration &&
        requestSource == inFlightSource &&
        requestSongKey == inFlightSongKey
}

internal fun resolveRemoteMetadataArtworkUri(coverSource: String?): String? {
    val normalizedSource = normalizedArtworkReference(coverSource) ?: return null
    return normalizedSource.takeIf {
        it.startsWith("http://", ignoreCase = true) ||
            it.startsWith("https://", ignoreCase = true)
    }
}
