package moe.ouom.neriplayer.core.download.catalog.assembly

internal fun acceptIndexedDownloadedCover(
    reference: String?,
    knownReferences: Set<String>,
    verifySnapshotReferences: Boolean,
    inspect: (String) -> Boolean
): String? {
    if (reference == null) return null
    if (!verifySnapshotReferences && reference in knownReferences) return reference
    return reference.takeIf(inspect)
}

internal fun selectDownloadedSongCover(
    indexedCover: () -> String?,
    cachedCover: () -> String?,
    allowSlowInspection: Boolean,
    cachedEmbeddedCover: () -> String?,
    embeddedCover: () -> String?
): String? {
    indexedCover()?.let { return it }
    cachedCover()?.let { return it }
    if (!allowSlowInspection) return null
    return cachedEmbeddedCover() ?: embeddedCover()
}
