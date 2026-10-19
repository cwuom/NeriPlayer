package moe.ouom.neriplayer.data.local.audioimport

import moe.ouom.neriplayer.data.local.media.source.LocalMediaHostAccess

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.download.naming.ParsedManagedDownloadFileName
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalMetadataSidecar
import moe.ouom.neriplayer.data.local.media.LOCAL_METADATA_SUFFIX
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.isNeteaseManagedSourceStableKey
import moe.ouom.neriplayer.data.local.media.isMediaStoreSidecarReference
import moe.ouom.neriplayer.data.local.media.normalizeLocalAlbumIdentity
import moe.ouom.neriplayer.data.local.media.repairLocalMetadataText
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.local.LocalMediaDetails
import java.io.File
import java.util.Locale

@RequiresApi(Build.VERSION_CODES.Q)
internal fun LocalAudioImportManager.resolveExternalStorageFolderMediaStoreScope(
    context: Context,
    folderUri: Uri
): ExternalStorageFolderMediaStoreScope? {
    if (folderUri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) {
        return null
    }
    val documentId = runCatching {
        DocumentsContract.getTreeDocumentId(folderUri)
    }.getOrElse {
        runCatching { DocumentsContract.getDocumentId(folderUri) }.getOrNull()
    } ?: return null
    return parseExternalStorageFolderMediaStoreScope(
        documentId = documentId,
        knownVolumeNames = MediaStore.getExternalVolumeNames(context)
    )
}

internal fun LocalAudioImportManager.escapeMediaStoreLikeValue(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}

internal fun LocalAudioImportManager.hydrateLocalSongFastIdentity(
    context: Context,
    song: SongItem,
    metadataReference: String? = null
): SongItem {
    val shouldReadSidecar = shouldHydrateLocalSongFastIdentity(song, metadataReference)
    if (!isRawId3IdentityCandidate(song) && !shouldReadSidecar) {
        return song
    }
    val rawMetadata = readRawId3Identity(context, song)
    val adjacentMetadata = song.localFilePath?.takeUnless { it.startsWith("content://", ignoreCase = true) }
        ?.let(::File)?.let { file -> File(file.parentFile, file.name + LOCAL_METADATA_SUFFIX) }
        ?.takeIf(File::isFile)?.absolutePath
    val reference = metadataReference ?: adjacentMetadata
    val sidecarHydrated = if (shouldReadSidecar || reference != null) {
        hydrateLocalSongFromMetadataSidecar(context, song, reference)
    } else {
        song
    }
    return repairQuickIdentityFromFileName(hydrateLocalSongRawId3Identity(sidecarHydrated, rawMetadata, song))
}

internal fun LocalAudioImportManager.toImportedSongItem(context: Context, details: LocalMediaDetails): SongItem {
    val mapped = LocalMediaSupport.toSongItem(details)
    if (mapped.matchedLyric == null && mapped.matchedTranslatedLyric == null && mapped.matchedRomanizedLyric == null) {
        return mapped
    }
    val metadata = LocalMediaSupport.readLocalMetadataSidecarFast(context, mapped)
    val lyrics = runCatching {
        LocalMediaSupport.inspectLyricsFast(
            context = context,
            song = mapped,
            includeStoredFallback = false,
            forceRefresh = true
        )
    }.getOrRethrowCancellation {
        NPLogger.w(TAG, "import original lyrics unavailable: ${it.message}")
    }
    return mapped.copy(
        originalLyric = metadata?.originalLyric ?: lyrics?.originalLyricBaseline,
        originalTranslatedLyric = metadata?.originalTranslatedLyric ?: lyrics?.translatedLyricBaseline,
        originalRomanizedLyric = metadata?.originalRomanizedLyric ?: lyrics?.romanizedLyricBaseline
    )
}

internal fun LocalAudioImportManager.hydrateScannedSongRawIdentity(context: Context, song: SongItem): SongItem {
    return hydrateLocalSongRawId3Identity(song, readRawId3Identity(context, song), originalSnapshot = song)
}

internal fun LocalAudioImportManager.hydrateLocalSongFromIndexedMetadataSidecar(
    context: Context,
    song: SongItem,
    metadataReference: String?
): SongItem {
    val reference = metadataReference?.trim()?.takeIf(String::isNotBlank)
        ?.takeUnless(::isMediaStoreSidecarReference) ?: return song
    val metadata = runCatching {
        LocalMediaSupport.readTextContent(context, reference)
            ?.let { LocalMediaSupport.parseLocalMetadataSidecar(reference, it) }
    }.getOrRethrowCancellation {
        NPLogger.w(TAG, "indexed metadata sidecar unavailable: ${it.message}")
    } ?: return song
    return hydrateLocalSongFromMetadataSidecar(song, metadata)
}

internal fun LocalAudioImportManager.hydrateLocalSongFromMetadataSidecar(
    context: Context,
    song: SongItem,
    metadataReference: String? = null
): SongItem {
    val metadata = runCatching {
        LocalMediaSupport.readLocalMetadataSidecarFast(
            context = context,
            song = song,
            metadataReference = metadataReference
        )
    }.getOrNull() ?: return song
    return hydrateLocalSongFromMetadataSidecar(song, metadata)
}

private fun LocalAudioImportManager.hydrateLocalSongFromMetadataSidecar(
    song: SongItem,
    metadata: LocalMetadataSidecar
): SongItem {
    val resolvedName = firstMeaningfulMetadataValue(
        metadata.customName,
        metadata.name,
        metadata.originalName
    )
    val resolvedArtist = firstMeaningfulMetadataValue(
        metadata.customArtist,
        metadata.artist,
        metadata.originalArtist
    )
    val resolvedAlbum = firstMeaningfulMetadataValue(metadata.album)?.let {
        normalizeLocalAlbumIdentity(
            album = it,
            usesFallbackAlbum = false,
            stripManagedSourcePrefix = isNeteaseManagedSourceStableKey(metadata.stableKey)
        )
    }
    val sidecarCover = firstMeaningfulMetadataValue(metadata.coverPath)
    val reboundCover = if (sidecarCover != null &&
        isPotentiallyStaleSafCoverReference(sidecarCover)
    ) {
        resolveCachedManagedCoverReference(song, metadata)
    } else {
        null
    }
    val metadataFallbackCover = listOf(
        metadata.customCoverUrl,
        metadata.coverUrl,
        metadata.originalCoverUrl
    )
        .mapNotNull(::normalizeQuickImportedMetadata)
        .mapNotNull { it.normalizeImportedCoverReference() }
        .firstOrNull { candidate -> !sameImportedCoverReference(candidate, sidecarCover) }
    val resolvedCover = selectHydratedLocalCoverReference(
        sidecarCover = sidecarCover,
        existingCover = song.coverUrl,
        reboundCover = reboundCover,
        metadataFallbackCover = metadataFallbackCover
    )
    val resolvedOriginalCover = if (metadata.originalCoverUrl == "") "" else selectHydratedLocalCoverReference(
        sidecarCover = metadata.originalCoverUrl.normalizeImportedCoverReference(),
        existingCover = song.originalCoverUrl,
        reboundCover = null,
        metadataFallbackCover = firstMeaningfulMetadataValue(
            metadata.originalCoverUrl,
            metadata.coverUrl
        )?.takeUnless { sameImportedCoverReference(it, sidecarCover) }
    )
    val metadataCoverForIdentity = listOf(
        metadata.customCoverUrl,
        metadata.coverUrl,
        metadata.originalCoverUrl
    )
        .mapNotNull(::normalizeQuickImportedMetadata)
        .mapNotNull { it.normalizeImportedCoverReference() }
        .firstOrNull { candidate -> !sameImportedCoverReference(candidate, sidecarCover) }
    val hasIdentity = resolvedName != null || resolvedArtist != null || resolvedAlbum != null ||
        firstMeaningfulMetadataValue(
            metadata.channelId,
            metadata.audioId,
            metadata.stableKey
        ) != null
    if (!hasIdentity && metadata.durationMs <= 0L && resolvedCover == null && metadata.sourceModifiedAtMs == null &&
        metadata.originalLyric == null && metadata.originalTranslatedLyric == null &&
        metadata.originalRomanizedLyric == null
    ) return song
    return song.copy(
        sourceModifiedAtMs = metadata.sourceModifiedAtMs ?: song.sourceModifiedAtMs,
        id = metadata.songId ?: song.id,
        name = resolvedName ?: song.name,
        artist = resolvedArtist ?: song.artist,
        album = resolvedAlbum ?: normalizeLocalAlbumIdentity(
            album = song.album,
            usesFallbackAlbum = song.album == LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            stripManagedSourcePrefix = isNeteaseManagedSourceStableKey(
                metadata.stableKey ?: song.sourceStableKey
            )
        ),
        durationMs = metadata.durationMs.takeIf { it > 0L } ?: song.durationMs,
        coverUrl = resolvedCover ?: metadataCoverForIdentity
            ?: song.coverUrl.normalizeImportedCoverReference(),
        originalCoverUrl = resolvedOriginalCover
            ?: metadataCoverForIdentity
            ?: song.originalCoverUrl.normalizeImportedCoverReference()
            ?: resolvedCover,
        customName = firstMeaningfulMetadataValue(metadata.customName)
            ?: song.customName?.takeUnless(::isQuickMetadataPlaceholder),
        customArtist = firstMeaningfulMetadataValue(metadata.customArtist)
            ?: song.customArtist?.takeUnless(::isQuickMetadataPlaceholder),
        originalName = firstMeaningfulMetadataValue(metadata.originalName)
            ?: firstMeaningfulMetadataValue(song.originalName)
            ?: resolvedName,
        originalArtist = firstMeaningfulMetadataValue(metadata.originalArtist)
            ?: firstMeaningfulMetadataValue(song.originalArtist)
            ?: resolvedArtist,
        originalLyric = metadata.originalLyric ?: song.originalLyric,
        originalTranslatedLyric = metadata.originalTranslatedLyric ?: song.originalTranslatedLyric,
        originalRomanizedLyric = metadata.originalRomanizedLyric ?: song.originalRomanizedLyric,
        customCoverUrl = firstMeaningfulMetadataValue(metadata.customCoverUrl)
            ?: song.customCoverUrl,
        channelId = metadata.channelId?.takeIf(String::isNotBlank) ?: song.channelId,
        audioId = metadata.audioId?.takeIf(String::isNotBlank) ?: song.audioId,
        subAudioId = metadata.subAudioId?.takeIf(String::isNotBlank) ?: song.subAudioId,
        playlistContextId = metadata.playlistContextId?.takeIf(String::isNotBlank)
            ?: song.playlistContextId,
        sourceStableKey = metadata.stableKey?.takeIf(String::isNotBlank)
            ?: song.sourceStableKey
    )
}

internal fun LocalAudioImportManager.resolveCachedManagedCoverReference(
    song: SongItem,
    metadata: LocalMetadataSidecar
): String? {
    val lookupSong = song.copy(
        id = metadata.songId ?: song.id,
        channelId = metadata.channelId ?: song.channelId,
        audioId = metadata.audioId ?: song.audioId,
        subAudioId = metadata.subAudioId ?: song.subAudioId,
        sourceStableKey = metadata.stableKey ?: song.sourceStableKey
    )
    val audio = runCatching {
        LocalMediaHostAccess.downloads.peekDownloadedAudio(lookupSong)
    }.getOrNull() ?: return null
    val reference = runCatching {
        LocalMediaHostAccess.downloads.peekCoverReference(audio)
    }.getOrNull()?.trim()?.takeIf(String::isNotBlank) ?: return null
    return LocalMediaHostAccess.downloads.toPlayableUri(reference) ?: reference
}

internal fun LocalAudioImportManager.firstMeaningfulMetadataValue(vararg values: String?): String? {
    return values.firstNotNullOfOrNull(::normalizeQuickImportedMetadata)
}

internal fun LocalAudioImportManager.repairQuickIdentityFromFileName(song: SongItem): SongItem {
    val displayName = quickIdentityDisplayName(song) ?: return song
    val parsed = parseFileNameMetadata(displayName) ?: return song
    val fileBaseName = displayName.substringBeforeLast('.', displayName)
    val unknownArtist = isQuickMetadataPlaceholder(song.artist)
    val unknownAlbum = isQuickMetadataPlaceholder(song.album) || song.album == LocalSongSupport.LOCAL_ALBUM_IDENTITY
    val parsedArtist = normalizeQuickImportedMetadata(parsed.artist)
    val parsedAlbum = normalizeQuickImportedMetadata(parsed.album)
    return song.copy(
        name = repairedQuickTitle(song.name, fileBaseName, normalizeQuickImportedMetadata(parsed.title)),
        artist = replaceUnknown(unknownArtist, parsedArtist, song.artist),
        album = normalizeLocalAlbumIdentity(
            album = replaceUnknown(unknownAlbum, parsedAlbum, song.album),
            usesFallbackAlbum = unknownAlbum && parsedAlbum == null,
            stripManagedSourcePrefix = isNeteaseManagedSourceStableKey(song.sourceStableKey)
        ),
        originalArtist = replaceUnknown(unknownArtist, parsedArtist, song.originalArtist)
    )
}

private fun quickIdentityDisplayName(song: SongItem): String? {
    return listOfNotNull(
        song.localFileName,
        song.localFilePath?.substringAfterLast(File.separatorChar),
        song.mediaUri?.substringAfterLast('/')
    ).firstOrNull(String::isNotBlank)
}

private fun LocalAudioImportManager.repairedQuickTitle(name: String, fileBaseName: String, parsedTitle: String?): String {
    val keepsName = !name.trim().equals(fileBaseName.trim(), ignoreCase = true) && isReadableQuickImportedTitle(name)
    return if (keepsName) name else parsedTitle ?: name
}

private fun <T> replaceUnknown(unknown: Boolean, parsed: T?, current: T): T = if (unknown) parsed ?: current else current

internal fun LocalAudioImportManager.isReadableScannedTitle(title: String?): Boolean {
    val trimmed = title?.trim().orEmpty()
    if (trimmed.isBlank()) return false
    if (trimmed.startsWith("content://", ignoreCase = true)) return false
    if (trimmed.startsWith("file://", ignoreCase = true)) return false
    if (isQuickMetadataPlaceholder(trimmed)) return false
    return true
}

internal fun LocalAudioImportManager.isReadableQuickImportedTitle(title: String?): Boolean {
    val trimmed = title?.trim().orEmpty()
    if (trimmed.isBlank()) return false
    if (trimmed.startsWith("content://", ignoreCase = true)) return false
    if (trimmed.startsWith("file://", ignoreCase = true)) return false
    if (isQuickMetadataPlaceholder(trimmed)) return false
    return true
}

internal fun LocalAudioImportManager.normalizeQuickImportedMetadata(value: String?): String? {
    val trimmed = value?.trim()?.let(::repairLocalMetadataText).orEmpty()
    if (trimmed.isBlank() || isQuickMetadataPlaceholder(trimmed)) return null
    return trimmed
}

internal fun LocalAudioImportManager.isQuickMetadataPlaceholder(value: String): Boolean {
    return value.trim().lowercase(Locale.ROOT) in quickMetadataPlaceholders
}

internal fun LocalAudioImportManager.parseFileNameMetadata(displayName: String): ParsedManagedDownloadFileName? {
    val baseName = displayName
        .substringBeforeLast('.', displayName)
        .trim()
        .takeIf { it.isNotEmpty() }
        ?: return null
    return parseWithDownloadFileNameTemplates(baseName) ?: parseCommonManagedDownloadFileName(baseName)
}

private fun parseWithDownloadFileNameTemplates(baseName: String): ParsedManagedDownloadFileName? {
    val downloads = LocalMediaHostAccess.downloads
    return downloads.candidateFileNameTemplates(downloads.currentDownloadFileNameTemplate())
        .asSequence()
        .mapNotNull { template -> downloads.parseBaseName(baseName, template) }
        .firstOrNull(::hasParsedIdentity)
}

private fun hasParsedIdentity(parsed: ParsedManagedDownloadFileName): Boolean {
    return listOf(parsed.title, parsed.artist, parsed.album).any { !it.isNullOrBlank() }
}

internal fun LocalAudioImportManager.parseCommonManagedDownloadFileName(
    baseName: String
): ParsedManagedDownloadFileName? {
    val fields = managedFileNameFields(baseName) ?: return null
    val sourceFirst = isManagedDownloadSourceName(fields.first())
    val sourceLast = isManagedDownloadSourceName(fields.last())
    return when {
        sourceFirst -> ParsedManagedDownloadFileName(
            source = fields.first(),
            artist = fields[1],
            title = fields.drop(2).joinToString(" - ")
        )
        sourceLast -> parseSourceLastFileName(fields)
        else -> parseManagedAlbumFileName(fields)
    }
}

private fun managedFileNameFields(baseName: String): List<String>? {
    val fields = baseName.split(" - ").map(String::trim)
    return fields.takeIf { it.size >= 3 && it.none(String::isBlank) }
}

private fun LocalAudioImportManager.isManagedDownloadSourceName(field: String): Boolean {
    return field.lowercase(Locale.ROOT) in managedDownloadSourceNames
}

private fun parseSourceLastFileName(fields: List<String>): ParsedManagedDownloadFileName {
    if (fields.size == 3) {
        return ParsedManagedDownloadFileName(title = fields[0], artist = fields[1], source = fields[2])
    }
    return ParsedManagedDownloadFileName(
        title = fields.dropLast(3).joinToString(" - "),
        artist = fields[fields.lastIndex - 2],
        album = fields[fields.lastIndex - 1],
        source = fields.last()
    )
}

private fun parseManagedAlbumFileName(fields: List<String>): ParsedManagedDownloadFileName? {
    if (fields.size != 3 || !fields[2].equals(MANAGED_DOWNLOAD_ALBUM, ignoreCase = true)) return null
    return ParsedManagedDownloadFileName(title = fields[0], artist = fields[1], album = fields[2])
}

private const val MANAGED_DOWNLOAD_ALBUM = "neriplayer-download"

internal fun LocalAudioImportManager.resolveParsedTitleFallback(
    currentTitle: String?,
    fallbackTitle: String,
    fileTitle: String,
    parsed: ParsedManagedDownloadFileName?
): String? {
    val parsedTitle = parsed?.title?.takeIf(::isReadableScannedTitle) ?: return null
    val normalizedCurrentTitle = normalizeParsedMetadataValue(currentTitle)
    if (normalizedCurrentTitle.isBlank()) {
        return parsedTitle
    }
    val replaceableTitles = replaceableParsedTitles(listOf(fileTitle, fallbackTitle) + joinedParsedTitles(parsed))
    return parsedTitle.takeIf { normalizedCurrentTitle in replaceableTitles }
}

private fun joinedParsedTitles(parsed: ParsedManagedDownloadFileName): List<String> {
    return listOf(
        listOfNotNull(parsed.artist, parsed.title),
        listOfNotNull(parsed.source, parsed.artist, parsed.title),
        listOfNotNull(parsed.album, parsed.title)
    ).filter { parts -> parts.size >= 2 }.map { parts -> parts.joinToString(" - ") }
}

private fun LocalAudioImportManager.replaceableParsedTitles(titles: List<String>): Set<String> {
    return titles.map(::normalizeParsedMetadataValue).filter(String::isNotBlank).toSet()
}
