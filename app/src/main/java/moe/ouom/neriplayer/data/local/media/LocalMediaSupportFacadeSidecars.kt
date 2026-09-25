package moe.ouom.neriplayer.data.local.media

import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.LyricKind
import android.content.Context
import android.net.Uri
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.util.network.isFileInsideDirectory
import java.io.File
import java.text.Normalizer

internal fun LocalMediaSupport.embeddedCoverCacheSampleSizeImpl(
    width: Int,
    height: Int,
    targetDimension: Int = MAX_EMBEDDED_COVER_CACHE_DIMENSION_PX
): Int {
    if (width <= 0 || height <= 0 || targetDimension <= 0) return 1

    var sampleSize = 1
    while (
        width.toLong() / sampleSize > targetDimension ||
            height.toLong() / sampleSize > targetDimension
    ) {
        sampleSize = sampleSize shl 1
    }
    return sampleSize
}

internal fun LocalMediaSupport.findNearbyLyricFilesImpl(
    file: File?,
    extensions: List<String> = lyricExtensions
): NearbyLyricFiles {
    val actualFile = file ?: return NearbyLyricFiles(null, null, null)
    val parent = actualFile.parentFile ?: return NearbyLyricFiles(null, null, null)
    val baseName = actualFile.nameWithoutExtension
    val legacyDownloadRoot = File(LEGACY_DOWNLOAD_ROOT)
    val isLegacyDownload = runCatching {
        isFileInsideDirectory(actualFile, legacyDownloadRoot)
    }.getOrDefault(false)
    val searchDirectories = buildList {
        if (isLegacyDownload) {
            add(File(legacyDownloadRoot, "Lyrics"))
        }
        add(File(parent, "Lyrics"))
        add(parent)
    }
        .filter(File::isDirectory)
        .distinctBy { it.absolutePath }

    return NearbyLyricFiles(
        original = findFirstLyricSidecar(
            searchDirectories = searchDirectories,
            fileNames = lyricSidecarNames(
                baseName = baseName,
                kind = LyricKind.ORIGINAL,
                extensions = extensions
            )
        ),
        translated = findFirstLyricSidecar(
            searchDirectories = searchDirectories,
            fileNames = lyricSidecarNames(
                baseName = baseName,
                kind = LyricKind.TRANSLATED,
                extensions = extensions
            )
        ),
        romanized = findFirstLyricSidecar(
            searchDirectories = searchDirectories,
            fileNames = lyricSidecarNames(
                baseName = baseName,
                kind = LyricKind.ROMANIZED,
                extensions = extensions
            )
        )
    )
}

internal fun LocalMediaSupport.copyNearbyLyricSidecarsImpl(
    context: Context,
    sourceUri: Uri,
    sourceDisplayName: String,
    targetFile: File
) {
    if (!sourceUri.scheme.equals("content", ignoreCase = true)) {
        return
    }
    val references = findNearbyLyricReferences(
        context = context,
        uri = sourceUri,
        file = null,
        displayName = sourceDisplayName
    )
    val targetLyricFiles = findNearbyLyricFiles(targetFile)
    val metadataReference = resolveLocalMetadataReference(
        context = context,
        sourceUri = sourceUri,
        file = null,
        displayName = sourceDisplayName
    )
    if (metadataReference != null) {
        copyLyricReference(
            context = context,
            reference = metadataReference,
            target = File(targetFile.parentFile ?: return, targetFile.name + LOCAL_METADATA_SUFFIX)
        )
    }
    listOf(
        Triple(references.original, targetLyricFiles.original, ""),
        Triple(references.translated, targetLyricFiles.translated, "_trans"),
        Triple(references.romanized, targetLyricFiles.romanized, "_roma")
    ).forEach { (reference, existingTarget, suffix) ->
        if (reference == null || existingTarget != null) {
            return@forEach
        }
        copyLyricReference(
            context = context,
            reference = reference,
            target = File(
                targetFile.parentFile ?: return@forEach,
                "${targetFile.nameWithoutExtension}$suffix.lrc"
            )
        )
    }
}

internal fun LocalMediaSupport.matchesDocumentPathParentImpl(
    path: List<String>,
    parentDocumentId: String,
    sourceDocumentId: String?,
    displayName: String,
    actualDisplayName: String?
): Boolean {
    if (
        actualDisplayName != null &&
        !actualDisplayName.equals(displayName, ignoreCase = true)
    ) {
        return false
    }
    return !sourceDocumentId.isNullOrBlank() &&
        path.dropLast(1).lastOrNull() == parentDocumentId &&
        path.lastOrNull() == sourceDocumentId
}

internal fun LocalMediaSupport.isManagedSidecarDirectoryNameImpl(actualName: String, desiredName: String): Boolean {
    if (canonicalSafName(actualName) == canonicalSafName(desiredName)) return true
    val normalizedActual = Normalizer.normalize(actualName, Normalizer.Form.NFC)
    val normalizedDesired = Normalizer.normalize(desiredName, Normalizer.Form.NFC)
    val prefix = "$normalizedDesired ("
    if (!normalizedActual.startsWith(prefix, ignoreCase = true) ||
        !normalizedActual.endsWith(")")
    ) {
        return false
    }
    return normalizedActual.substring(prefix.length, normalizedActual.length - 1)
        .toIntOrNull() != null
}

internal fun LocalMediaSupport.localCoverSidecarNameImpl(
    baseName: String,
    extension: String,
    stableIdentityKey: String?
): String {
    val normalizedKey = stableIdentityKey?.trim()?.takeIf(String::isNotBlank)
        ?: return "$baseName.$extension"
    val suffix = ManagedDownloadStorageNaming.coverStableKeySuffix(normalizedKey)
    return "$baseName-$suffix.$extension"
}

internal fun LocalMediaSupport.sidecarNameMatchesImpl(actualName: String, canonicalName: String): Boolean {
    if (
        canonicalSafName(actualName) == canonicalSafName(canonicalName) ||
        numberedSidecarNameMatches(actualName, canonicalName)
    ) {
        return true
    }
    // 部分 DocumentsProvider 会为非 txt 文本 MIME 自动补 .txt
    if (
        !canonicalName.endsWith(".txt", ignoreCase = true) &&
        actualName.endsWith(".txt", ignoreCase = true)
    ) {
        val providerNameWithoutTextExtension = actualName.dropLast(".txt".length)
        val matches = canonicalSafName(providerNameWithoutTextExtension) ==
            canonicalSafName(canonicalName) ||
            numberedSidecarNameMatches(providerNameWithoutTextExtension, canonicalName)
        return matches
    }
    return false
}

internal fun LocalMediaSupport.findNearbyCoverImpl(file: File?): File? {
    val actualFile = file ?: return null
    val parent = actualFile.parentFile ?: return null
    val baseName = actualFile.nameWithoutExtension
    val cacheKey = nearbyCoverLookupKey(actualFile, parent, baseName)
    cachedNearbyCover(cacheKey)?.let { hit ->
        return hit.path?.let(::File)?.takeIf { it.exists() }
    }

    val cover = findNearbyCoverUncached(parent, baseName)
    rememberNearbyCover(cacheKey, cover)
    return cover
}
