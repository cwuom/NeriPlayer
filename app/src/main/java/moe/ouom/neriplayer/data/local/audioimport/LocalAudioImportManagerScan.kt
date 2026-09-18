package moe.ouom.neriplayer.data.local.audioimport

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ParsedManagedDownloadFileName
import moe.ouom.neriplayer.core.download.candidateManagedDownloadFileNameTemplates
import moe.ouom.neriplayer.core.download.parseManagedDownloadBaseName
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalMetadataSidecar
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.normalizeLocalAlbumIdentity
import moe.ouom.neriplayer.data.model.SongItem
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
    if (!shouldHydrateLocalSongFastIdentity(song, metadataReference)) {
        return song
    }
    val sidecarHydrated = hydrateLocalSongFromMetadataSidecar(
        context = context,
        song = song,
        metadataReference = metadataReference
    )
    return repairQuickIdentityFromFileName(sidecarHydrated)
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
        normalizeLocalAlbumIdentity(it, usesFallbackAlbum = false)
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
        .firstOrNull { candidate -> !sameImportedCoverReference(candidate, sidecarCover) }
    val resolvedCover = selectHydratedLocalCoverReference(
        sidecarCover = sidecarCover,
        existingCover = song.coverUrl,
        reboundCover = reboundCover,
        metadataFallbackCover = metadataFallbackCover
    )
    val resolvedOriginalCover = selectHydratedLocalCoverReference(
        sidecarCover = sidecarCover,
        existingCover = song.originalCoverUrl,
        reboundCover = reboundCover,
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
        .firstOrNull { candidate -> !sameImportedCoverReference(candidate, sidecarCover) }
    val hasIdentity = resolvedName != null || resolvedArtist != null || resolvedAlbum != null ||
        firstMeaningfulMetadataValue(
            metadata.channelId,
            metadata.audioId,
            metadata.stableKey
        ) != null
    if (!hasIdentity && metadata.durationMs <= 0L && resolvedCover == null && metadata.sourceModifiedAtMs == null) return song
    return song.copy(
        sourceModifiedAtMs = metadata.sourceModifiedAtMs ?: song.sourceModifiedAtMs,
        id = metadata.songId ?: song.id,
        name = resolvedName ?: song.name,
        artist = resolvedArtist ?: song.artist,
        album = resolvedAlbum ?: normalizeLocalAlbumIdentity(
            album = song.album,
            usesFallbackAlbum = song.album == LocalSongSupport.LOCAL_ALBUM_IDENTITY
        ),
        durationMs = metadata.durationMs.takeIf { it > 0L } ?: song.durationMs,
        coverUrl = resolvedCover ?: metadataCoverForIdentity ?: song.coverUrl,
        originalCoverUrl = resolvedOriginalCover
            ?: metadataCoverForIdentity
            ?: song.originalCoverUrl
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
        ManagedDownloadStorage.peekDownloadedAudio(lookupSong)
    }.getOrNull() ?: return null
    val reference = runCatching {
        ManagedDownloadStorage.peekCoverReference(audio)
    }.getOrNull()?.trim()?.takeIf(String::isNotBlank) ?: return null
    return ManagedDownloadStorage.toPlayableUri(reference) ?: reference
}

internal fun LocalAudioImportManager.firstMeaningfulMetadataValue(vararg values: String?): String? {
    return values.firstNotNullOfOrNull(::normalizeQuickImportedMetadata)
}

internal fun LocalAudioImportManager.repairQuickIdentityFromFileName(song: SongItem): SongItem {
    val displayName = song.localFileName
        ?.takeIf(String::isNotBlank)
        ?: song.localFilePath
            ?.substringAfterLast(File.separatorChar)
            ?.takeIf(String::isNotBlank)
        ?: song.mediaUri
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
        ?: return song
    val parsed = parseFileNameMetadata(displayName) ?: return song
    val fileBaseName = displayName.substringBeforeLast('.', displayName)
    val unknownArtist = song.artist.trim().lowercase(Locale.ROOT) in quickMetadataPlaceholders
    val unknownAlbum = song.album.trim().lowercase(Locale.ROOT) in quickMetadataPlaceholders ||
        song.album == LocalSongSupport.LOCAL_ALBUM_IDENTITY
    val parsedArtist = normalizeQuickImportedMetadata(parsed.artist)
    val parsedAlbum = normalizeQuickImportedMetadata(parsed.album)
    val parsedTitle = normalizeQuickImportedMetadata(parsed.title)
    return song.copy(
        name = if (
            !song.name.trim().equals(fileBaseName.trim(), ignoreCase = true) &&
                isReadableQuickImportedTitle(song.name)
        ) {
            song.name
        } else {
            parsedTitle ?: song.name
        },
        artist = if (unknownArtist) parsedArtist ?: song.artist else song.artist,
        album = normalizeLocalAlbumIdentity(
            album = if (unknownAlbum) parsedAlbum ?: song.album else song.album,
            usesFallbackAlbum = unknownAlbum && parsedAlbum == null
        ),
        originalArtist = if (unknownArtist) parsedArtist ?: song.originalArtist else song.originalArtist
    )
}

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
    val trimmed = value?.trim().orEmpty()
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
    val parsed = candidateManagedDownloadFileNameTemplates(
        ManagedDownloadStorage.currentDownloadFileNameTemplate()
    ).asSequence()
        .mapNotNull { template -> parseManagedDownloadBaseName(baseName, template) }
        .firstOrNull { parsed ->
            !parsed.title.isNullOrBlank() ||
                !parsed.artist.isNullOrBlank() ||
                !parsed.album.isNullOrBlank()
        }
    return parsed ?: parseCommonManagedDownloadFileName(baseName)
}

internal fun LocalAudioImportManager.parseCommonManagedDownloadFileName(
    baseName: String
): ParsedManagedDownloadFileName? {
    val fields = baseName.split(" - ").map(String::trim)
    if (fields.size < 3 || fields.any(String::isBlank)) return null
    val first = fields.first().lowercase(Locale.ROOT)
    val last = fields.last().lowercase(Locale.ROOT)
    val isKnownSource = first in managedDownloadSourceNames ||
        last in managedDownloadSourceNames
    val hasManagedAlbum = fields.any {
        it.equals("neriplayer-download", ignoreCase = true) ||
            it.startsWith("netease", ignoreCase = true)
    }
    if (!isKnownSource && !hasManagedAlbum) return null
    if (first in managedDownloadSourceNames) {
        return ParsedManagedDownloadFileName(
            source = fields.first(),
            artist = fields.getOrNull(1),
            title = fields.drop(2).joinToString(" - ")
        )
    }
    if (last in managedDownloadSourceNames && fields.size >= 4) {
        return ParsedManagedDownloadFileName(
            title = fields.dropLast(3).joinToString(" - "),
            artist = fields[fields.lastIndex - 2],
            album = fields[fields.lastIndex - 1],
            source = fields.last()
        )
    }
    if (last in managedDownloadSourceNames && fields.size == 3) {
        return ParsedManagedDownloadFileName(
            title = fields[0],
            artist = fields[1],
            source = fields[2]
        )
    }
    if (fields.size == 3 && fields[2].equals("neriplayer-download", ignoreCase = true)) {
        return ParsedManagedDownloadFileName(
            title = fields[0],
            artist = fields[1],
            album = fields[2]
        )
    }
    return null
}

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

    val fallbackCandidates = linkedSetOf(fileTitle, fallbackTitle).apply {
        listOfNotNull(parsed.artist, parsed.title)
            .takeIf { it.size >= 2 }
            ?.joinToString(" - ")
            ?.let(::add)
        listOfNotNull(parsed.source, parsed.artist, parsed.title)
            .takeIf { it.size >= 2 }
            ?.joinToString(" - ")
            ?.let(::add)
        listOfNotNull(parsed.album, parsed.title)
            .takeIf { it.size >= 2 }
            ?.joinToString(" - ")
            ?.let(::add)
    }.map(::normalizeParsedMetadataValue)
        .filter(String::isNotBlank)
        .toSet()

    return parsedTitle.takeIf { normalizedCurrentTitle in fallbackCandidates }
}
