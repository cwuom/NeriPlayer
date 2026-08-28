package moe.ouom.neriplayer.core.download.catalog

import android.content.Context
import androidx.core.net.toUri
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.LazyThreadSafetyMode
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.bootstrap.ManagedLibraryRebuilder
import moe.ouom.neriplayer.core.download.withRecoveredRemoteSourceStableKey
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadArtifactPlanner
import moe.ouom.neriplayer.core.download.naming.candidateManagedDownloadFileNameTemplates
import moe.ouom.neriplayer.core.download.naming.parseManagedDownloadBaseName
import moe.ouom.neriplayer.core.download.policy.resolveDownloadedLyricOverride
import moe.ouom.neriplayer.core.download.policy.shouldInspectDownloadedAudioDetails
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioMetadataStore
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadCoverLookup
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.LocalSongSupport

internal fun fallbackDownloadedSongId(reference: String): Long {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(reference.toByteArray(Charsets.UTF_8))
    return (ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long and Long.MAX_VALUE)
        .coerceAtLeast(1L)
}

internal fun isAccessibleManagedReference(
    result: ManagedDownloadReferenceIo.AccessResult
): Boolean = result == ManagedDownloadReferenceIo.AccessResult.Accessible

internal class DownloadedSongBuilder(
    private val metadataStore: DownloadedAudioMetadataStore,
    private val loggerTag: String
) {
    suspend fun build(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot? = null,
        existingDownloadTime: Long? = null,
        loadLyricContents: Boolean = false,
        resolveLyricFallbacks: Boolean = false,
        allowSlowLocalInspection: Boolean = true
    ): DownloadedSong {
        val effectiveSnapshot = snapshot ?: ManagedDownloadStorage.buildDownloadLibrarySnapshot(context)
        val metadataEntry = effectiveSnapshot.metadataEntriesByAudioName[storedAudio.logicalName]
            ?: effectiveSnapshot.metadataEntriesByAudioName[storedAudio.name]
        val snapshotMetadata = ManagedDownloadStorage.metadataForAudioEntry(
            effectiveSnapshot,
            storedAudio
        )
        val metadata = snapshotMetadata ?: metadataStore.read(
            context = context,
            audio = storedAudio,
            metadataEntry = metadataEntry
        )
        val safeCoverUrl = sanitizeDownloadedCoverMetadataReference(
            metadata?.coverUrl,
            effectiveSnapshot
        )
        val safeCustomCoverUrl = sanitizeDownloadedCoverMetadataReference(
            metadata?.customCoverUrl,
            effectiveSnapshot
        )
        val safeOriginalCoverUrl = sanitizeDownloadedCoverMetadataReference(
            metadata?.originalCoverUrl,
            effectiveSnapshot
        )
        val (parsedArtist, parsedTitle) = parseDownloadedFileName(storedAudio.name)
        val indexedCoverReference = resolveIndexedDownloadedCoverReference(
            metadata = metadata,
            storedAudio = storedAudio,
            snapshot = effectiveSnapshot
        )?.takeIf { reference ->
            isAccessibleManagedReference(
                ManagedDownloadReferenceIo.inspect(context, reference)
            )
        }
        val cachedCoverReference = if (indexedCoverReference == null) {
            resolveAccessibleManagedReference(
                context = context,
                ManagedDownloadArtifactPlanner.trustedMetadataReference(
                    metadata?.coverPath,
                    effectiveSnapshot
                ),
                metadata?.coverUrl,
                metadata?.originalCoverUrl
            )
        } else {
            null
        }
        val metadataCoverReference = if (
            cachedCoverReference == null && indexedCoverReference == null
        ) {
            resolveCachedAudioMetadataCoverReference(context, storedAudio)
                ?: if (allowSlowLocalInspection) {
                    resolveAudioMetadataCoverReference(context, storedAudio)
                } else {
                    null
                }
        } else {
            null
        }
        val lyricContent = resolveLyricContent(
            context = context,
            storedAudio = storedAudio,
            metadata = metadata,
            snapshot = effectiveSnapshot,
            loadLyricContents = loadLyricContents,
            resolveLyricFallbacks = resolveLyricFallbacks
        )
        val needsLocalLyricFallback = shouldInspectDownloadedLocalLyrics(
            loadLyricContents = loadLyricContents,
            fileLyric = lyricContent.fileLyric,
            fileTranslatedLyric = lyricContent.fileTranslatedLyric,
            fileRomanizedLyric = lyricContent.fileRomanizedLyric,
            matchedLyric = metadata?.matchedLyric,
            originalLyric = metadata?.originalLyric,
            matchedTranslatedLyric = metadata?.matchedTranslatedLyric,
            originalTranslatedLyric = metadata?.originalTranslatedLyric,
            matchedRomanizedLyric = metadata?.matchedRomanizedLyric,
            originalRomanizedLyric = metadata?.originalRomanizedLyric,
            indexedLyric = lyricContent.indexedLyric,
            indexedTranslatedLyric = lyricContent.indexedTranslatedLyric,
            indexedRomanizedLyric = lyricContent.indexedRomanizedLyric
        )
        val localDetails by lazy(LazyThreadSafetyMode.NONE) {
            if (
                shouldInspectDownloadedAudioDetails(
                    allowSlowLocalInspection = allowSlowLocalInspection,
                    metadata = metadata,
                    coverReference = indexedCoverReference
                        ?: cachedCoverReference
                        ?: metadataCoverReference,
                    needsLocalLyricFallback = needsLocalLyricFallback
                )
            ) {
                inspectAudioDetails(context, storedAudio)
            } else {
                null
            }
        }
        val coverReference = indexedCoverReference
            ?: cachedCoverReference
            ?: metadataCoverReference
            ?: localDetails?.coverUri
        val matchedLyric = if (loadLyricContents) {
            resolveDownloadedLyricOverride(
                fileLyric = lyricContent.fileLyric,
                embeddedMatchedLyric = metadata?.matchedLyric,
                embeddedOriginalLyric = metadata?.originalLyric,
                localLyricContent = localDetails?.lyricContent,
                indexedLyricContent = lyricContent.indexedLyric
            )
        } else {
            metadata?.matchedLyric
        }
        val matchedTranslatedLyric = if (loadLyricContents) {
            resolveDownloadedLyricOverride(
                fileLyric = lyricContent.fileTranslatedLyric,
                embeddedMatchedLyric = metadata?.matchedTranslatedLyric,
                embeddedOriginalLyric = metadata?.originalTranslatedLyric,
                localLyricContent = null,
                indexedLyricContent = lyricContent.indexedTranslatedLyric.takeIf {
                    lyricContent.fileTranslatedLyric.isNullOrBlank() &&
                        metadata?.matchedTranslatedLyric == null &&
                        metadata?.originalTranslatedLyric == null
                }
            )
        } else {
            metadata?.matchedTranslatedLyric
        }
        val matchedRomanizedLyric = if (loadLyricContents) {
            resolveDownloadedLyricOverride(
                fileLyric = lyricContent.fileRomanizedLyric,
                embeddedMatchedLyric = metadata?.matchedRomanizedLyric,
                embeddedOriginalLyric = metadata?.originalRomanizedLyric,
                localLyricContent = null,
                indexedLyricContent = lyricContent.indexedRomanizedLyric.takeIf {
                    lyricContent.fileRomanizedLyric.isNullOrBlank() &&
                        metadata?.matchedRomanizedLyric == null &&
                        metadata?.originalRomanizedLyric == null
                }
            )
        } else {
            metadata?.matchedRomanizedLyric
        }

        return DownloadedSong(
            id = metadata?.songId ?: fallbackDownloadedSongId(storedAudio.reference),
            name = metadata?.name?.takeIf(String::isNotBlank)
                ?: localDetails?.title?.takeIf(String::isNotBlank)
                ?: parsedTitle,
            artist = metadata?.artist?.takeIf(String::isNotBlank)
                ?: localDetails?.artist?.takeIf(String::isNotBlank)
                ?: parsedArtist,
            album = metadata?.album?.takeIf(String::isNotBlank)
                ?: (if (metadata == null) {
                    localDetails?.album?.takeIf(String::isNotBlank)
                } else {
                    null
                })
                ?: metadata?.identityAlbum
                    ?.takeUnless { it == LocalSongSupport.LOCAL_ALBUM_IDENTITY }
                ?: context.getString(R.string.local_files),
            filePath = storedAudio.reference,
            fileSize = storedAudio.sizeBytes,
            downloadTime = existingDownloadTime
                ?: ManagedLibraryRebuilder.logicalTimeMs(metadata, storedAudio)
                ?: System.currentTimeMillis(),
            coverPath = coverReference,
            coverUrl = safeCoverUrl,
            matchedLyric = matchedLyric,
            matchedTranslatedLyric = matchedTranslatedLyric,
            matchedRomanizedLyric = matchedRomanizedLyric,
            matchedLyricSource = metadata?.matchedLyricSource,
            matchedSongId = metadata?.matchedSongId,
            userLyricOffsetMs = metadata?.userLyricOffsetMs ?: 0L,
            customCoverUrl = safeCustomCoverUrl,
            customName = metadata?.customName,
            customArtist = metadata?.customArtist,
            originalName = metadata?.originalName ?: localDetails?.originalTitle,
            originalArtist = metadata?.originalArtist ?: localDetails?.originalArtist,
            originalCoverUrl = safeOriginalCoverUrl,
            originalLyric = metadata?.originalLyric,
            originalTranslatedLyric = metadata?.originalTranslatedLyric,
            originalRomanizedLyric = metadata?.originalRomanizedLyric,
            mediaUri = ManagedDownloadStorage.resolveStoredEntryPlaybackUri(storedAudio)
                .orEmpty(),
            durationMs = metadata?.durationMs?.takeIf { it > 0L } ?: localDetails?.durationMs ?: 0L,
            stableKey = metadata?.stableKey ?: localDetails?.sourceStableKey,
            sourceIdentityAlbum = metadata?.identityAlbum,
            sourceMediaUri = metadata?.mediaUri,
            sourceChannelId = metadata?.channelId,
            sourceAudioId = metadata?.audioId,
            sourceSubAudioId = metadata?.subAudioId,
            sourcePlaylistContextId = metadata?.playlistContextId
        ).withRecoveredRemoteSourceStableKey()
    }

    fun inspectAudioDetails(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ) = ManagedDownloadStorage.resolveStoredEntryPlaybackUri(storedAudio)?.let { playbackUri ->
        runCatching {
            LocalMediaSupport.inspect(context, playbackUri.toUri())
        }.onFailure { error ->
        NPLogger.w(loggerTag, "读取已下载音频标签失败: ${storedAudio.name} - ${error.message}")
        }.getOrNull()
    }

    private fun resolveAudioMetadataCoverReference(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ): String? {
        val playbackUri = ManagedDownloadStorage.resolveStoredEntryPlaybackUri(storedAudio)
            ?: return null
        val candidate = runCatching {
            LocalMediaSupport.resolveCoverUri(
                context = context,
                uri = playbackUri.toUri()
            )
        }.onFailure { error ->
            NPLogger.d(
                loggerTag,
                "读取已下载音频元信息封面失败: ${storedAudio.name} - ${error.message}"
            )
        }.getOrNull()
        return candidate
            ?.takeIf(::isResolvableLocalReference)
            ?.takeIf { reference ->
                isAccessibleManagedReference(
                    ManagedDownloadReferenceIo.inspect(context, reference)
                )
            }
    }

    private fun resolveCachedAudioMetadataCoverReference(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ): String? {
        val sourceUri = ManagedDownloadStorage.resolveStoredEntryPlaybackUri(storedAudio)
            ?.let { reference -> runCatching { reference.toUri() }.getOrNull() }
            ?: return null
        val candidate = LocalMediaSupport.peekMediaStoreAlbumArtUri(context, sourceUri)
            ?: LocalMediaSupport.peekCachedEmbeddedCoverUri(context, sourceUri)
        return candidate
            ?.takeIf(::isResolvableLocalReference)
            ?.takeIf { reference ->
                isAccessibleManagedReference(
                    ManagedDownloadReferenceIo.inspect(context, reference)
                )
            }
    }

    private suspend fun resolveLyricContent(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        loadLyricContents: Boolean,
        resolveLyricFallbacks: Boolean
    ): DownloadedSongLyricContent {
        val lyricReference = indexedOrMetadataLyricReference(
            context = context,
            storedAudio = storedAudio,
            metadata = metadata,
            snapshot = snapshot,
            translated = false,
            loadLyricContents = loadLyricContents
        )
        val fileLyric = if (loadLyricContents) {
            readLyricText(context, lyricReference)
        } else {
            null
        }
        val indexedLyric = indexedFallbackLyricText(
            context = context,
            storedAudio = storedAudio,
            metadata = metadata,
            snapshot = snapshot,
            translated = false,
            resolvedReference = lyricReference.resolvedReference,
            indexedReference = lyricReference.indexedReference,
            fileLyric = fileLyric,
            loadLyricContents = loadLyricContents,
            resolveLyricFallbacks = resolveLyricFallbacks
        )
        val translatedLyricReference = indexedOrMetadataLyricReference(
            context = context,
            storedAudio = storedAudio,
            metadata = metadata,
            snapshot = snapshot,
            translated = true,
            loadLyricContents = loadLyricContents
        )
        val fileTranslatedLyric = if (loadLyricContents) {
            readLyricText(context, translatedLyricReference)
        } else {
            null
        }
        val indexedTranslatedLyric = indexedFallbackLyricText(
            context = context,
            storedAudio = storedAudio,
            metadata = metadata,
            snapshot = snapshot,
            translated = true,
            resolvedReference = translatedLyricReference.resolvedReference,
            indexedReference = translatedLyricReference.indexedReference,
            fileLyric = fileTranslatedLyric,
            loadLyricContents = loadLyricContents,
            resolveLyricFallbacks = resolveLyricFallbacks
        )
        val romanizedLyricReference = indexedOrMetadataRomanizedLyricReference(
            context = context,
            storedAudio = storedAudio,
            metadata = metadata,
            snapshot = snapshot,
            loadLyricContents = loadLyricContents
        )
        val fileRomanizedLyric = if (loadLyricContents) {
            readLyricText(context, romanizedLyricReference)
        } else {
            null
        }
        val indexedRomanizedLyric = indexedRomanizedFallbackLyricText(
            context = context,
            storedAudio = storedAudio,
            metadata = metadata,
            snapshot = snapshot,
            resolvedReference = romanizedLyricReference.resolvedReference,
            indexedReference = romanizedLyricReference.indexedReference,
            fileLyric = fileRomanizedLyric,
            loadLyricContents = loadLyricContents,
            resolveLyricFallbacks = resolveLyricFallbacks
        )
        return DownloadedSongLyricContent(
            fileLyric = fileLyric,
            indexedLyric = indexedLyric,
            fileTranslatedLyric = fileTranslatedLyric,
            indexedTranslatedLyric = indexedTranslatedLyric,
            fileRomanizedLyric = fileRomanizedLyric,
            indexedRomanizedLyric = indexedRomanizedLyric
        )
    }

    private suspend fun indexedOrMetadataLyricReference(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        translated: Boolean,
        loadLyricContents: Boolean
    ): DownloadedLyricReference {
        val indexedReference = ManagedDownloadArtifactPlanner.indexedLyricReference(
            audio = storedAudio,
            songId = metadata?.songId,
            translated = translated,
            snapshot = snapshot
        )
        if (!loadLyricContents) {
            return DownloadedLyricReference(
                resolvedReference = null,
                indexedReference = indexedReference,
                fallbackReference = null
            )
        }
        val metadataReference = ManagedDownloadArtifactPlanner.trustedMetadataReference(
            if (translated) {
            metadata?.translatedLyricPath
            } else {
                metadata?.lyricPath
            },
            snapshot
        )
        return selectDownloadedLyricReference(
            indexedReference = indexedReference,
            metadataReference = metadataReference,
            loadLyricContents = true,
            inspectReference = { reference ->
                ManagedDownloadReferenceIo.inspect(context, reference)
            }
        )
    }

    private suspend fun indexedOrMetadataRomanizedLyricReference(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        loadLyricContents: Boolean
    ): DownloadedLyricReference {
        val indexedReference = ManagedDownloadArtifactPlanner.indexedRomanizedLyricReference(
            audio = storedAudio,
            songId = metadata?.songId,
            snapshot = snapshot
        )
        return selectDownloadedLyricReference(
            indexedReference = indexedReference,
            metadataReference = ManagedDownloadArtifactPlanner.trustedMetadataReference(
                metadata?.romanizedLyricPath,
                snapshot
            ),
            loadLyricContents = loadLyricContents,
            inspectReference = { reference ->
                ManagedDownloadReferenceIo.inspect(context, reference)
            }
        )
    }

    private suspend fun readLyricText(
        context: Context,
        reference: DownloadedLyricReference
    ): String? {
        reference.resolvedReference
            ?.let { resolved -> ManagedDownloadStorage.readText(context, resolved) }
            ?.let { return it }
        return reference.fallbackReference
            ?.takeUnless { fallback -> fallback == reference.resolvedReference }
            ?.let { fallback -> ManagedDownloadStorage.readText(context, fallback) }
    }

    private suspend fun indexedFallbackLyricText(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        translated: Boolean,
        resolvedReference: String?,
        indexedReference: String?,
        fileLyric: String?,
        loadLyricContents: Boolean,
        resolveLyricFallbacks: Boolean
    ): String? {
        if (!loadLyricContents || !resolveLyricFallbacks || fileLyric != null) {
            return null
        }
        if (resolvedReference == indexedReference) {
            return null
        }
        return ManagedDownloadArtifactPlanner.indexedLyricText(
            context = context,
            audio = storedAudio,
            songId = metadata?.songId,
            translated = translated,
            snapshot = snapshot
        )
    }

    private suspend fun indexedRomanizedFallbackLyricText(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry,
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        resolvedReference: String?,
        indexedReference: String?,
        fileLyric: String?,
        loadLyricContents: Boolean,
        resolveLyricFallbacks: Boolean
    ): String? {
        if (!loadLyricContents || !resolveLyricFallbacks || fileLyric != null) {
            return null
        }
        if (resolvedReference == indexedReference) {
            return null
        }
        return ManagedDownloadArtifactPlanner.indexedRomanizedLyricText(
            context = context,
            audio = storedAudio,
            songId = metadata?.songId,
            snapshot = snapshot
        )
    }

    private fun parseDownloadedFileName(fileName: String): Pair<String, String> {
        val nameWithoutExt = fileName.substringBeforeLast('.', fileName)
        candidateManagedDownloadFileNameTemplates(ManagedDownloadStorage.currentDownloadFileNameTemplate())
            .asSequence()
            .mapNotNull { template -> parseManagedDownloadBaseName(nameWithoutExt, template) }
            .firstOrNull { parsed ->
                !parsed.title.isNullOrBlank() || !parsed.artist.isNullOrBlank()
            }
            ?.let { parsed ->
                return parsed.artist.orEmpty() to (parsed.title ?: nameWithoutExt)
            }
        val parts = nameWithoutExt.split(" - ", limit = 2)
        return if (parts.size >= 2) {
            parts[0].trim() to parts[1].trim()
        } else {
            "" to nameWithoutExt
        }
    }

    private fun resolveAccessibleManagedReference(
        context: Context,
        vararg references: String?
    ): String? {
        return references.firstNotNullOfOrNull { reference ->
            val candidate = reference?.takeIf(::isResolvableLocalReference)
                ?: return@firstNotNullOfOrNull null
            candidate.takeIf { reference ->
                isAccessibleManagedReference(
                    ManagedDownloadReferenceIo.inspect(context, reference)
                )
            }
        }
    }

    private data class DownloadedSongLyricContent(
        val fileLyric: String?,
        val indexedLyric: String?,
        val fileTranslatedLyric: String?,
        val indexedTranslatedLyric: String?,
        val fileRomanizedLyric: String?,
        val indexedRomanizedLyric: String?
    )

}

internal data class DownloadedLyricReference(
    val resolvedReference: String?,
    val indexedReference: String?,
    val fallbackReference: String?
)

internal fun selectDownloadedLyricReference(
    indexedReference: String?,
    metadataReference: String?,
    loadLyricContents: Boolean,
    inspectReference: (String) -> ManagedDownloadReferenceIo.AccessResult
): DownloadedLyricReference {
    if (!loadLyricContents) {
        return DownloadedLyricReference(
            resolvedReference = null,
            indexedReference = indexedReference,
            fallbackReference = null
        )
    }
    val accessByReference = mutableMapOf<String, Boolean>()
    fun accessible(reference: String?): String? {
        val candidate = reference?.takeIf(::isResolvableLocalReference) ?: return null
        return candidate.takeIf {
            accessByReference.getOrPut(candidate) {
                isAccessibleManagedReference(inspectReference(candidate))
            }
        }
    }
    val accessibleIndexedReference = accessible(indexedReference)
    if (accessibleIndexedReference != null) {
        return DownloadedLyricReference(
            resolvedReference = accessibleIndexedReference,
            indexedReference = indexedReference,
            fallbackReference = null
        )
    }
    val accessibleMetadataReference = accessible(metadataReference)
    return DownloadedLyricReference(
        resolvedReference = accessibleIndexedReference ?: accessibleMetadataReference,
        indexedReference = indexedReference,
        fallbackReference = accessibleMetadataReference
    )
}

internal fun resolveIndexedDownloadedCoverReference(
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
    storedAudio: ManagedDownloadStorage.StoredEntry,
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
): String? {
    if (!shouldUseIndexedDownloadedCoverFallback(metadata)) return null
    metadata?.restorableMetadata?.legacyCoverRecoveryReferences
        ?.asSequence()
        ?.mapNotNull(ManagedDownloadStorage::normalizeManagedAudioFileName)
        ?.mapNotNull(snapshot.coverEntriesByName::get)
        ?.firstOrNull()
        ?.let { entry -> return entry.reference }
    return ManagedDownloadCoverLookup.findCoverReference(snapshot, storedAudio)
}

internal fun sanitizeDownloadedCoverMetadataReference(
    reference: String?,
    snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
): String? {
    val normalized = reference?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (!isResolvableLocalReference(normalized)) return normalized
    return ManagedDownloadArtifactPlanner.trustedMetadataReference(normalized, snapshot)
}

internal fun shouldUseIndexedDownloadedCoverFallback(
    metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
): Boolean {
    if (metadata == null) return true
    val originalCoverIsRemote = metadata.originalCoverUrl
        ?.trim()
        ?.let { reference ->
            reference.startsWith("https://", ignoreCase = true) ||
                reference.startsWith("http://", ignoreCase = true)
        } == true
    val restoredBaseCover = metadata.customCoverUrl.isNullOrBlank() &&
        metadata.coverPath.isNullOrBlank() &&
        originalCoverIsRemote &&
        metadata.coverUrl == metadata.originalCoverUrl
    return !restoredBaseCover
}

internal fun shouldInspectDownloadedLocalLyrics(
    loadLyricContents: Boolean,
    fileLyric: String?,
    fileTranslatedLyric: String?,
    fileRomanizedLyric: String?,
    matchedLyric: String?,
    originalLyric: String?,
    matchedTranslatedLyric: String?,
    originalTranslatedLyric: String?,
    matchedRomanizedLyric: String?,
    originalRomanizedLyric: String?,
    indexedLyric: String?,
    indexedTranslatedLyric: String?,
    indexedRomanizedLyric: String?
): Boolean {
    if (!loadLyricContents) return false
    return fileLyric == null &&
        fileTranslatedLyric == null &&
        fileRomanizedLyric == null &&
        matchedLyric == null &&
        originalLyric == null &&
        matchedTranslatedLyric == null &&
        originalTranslatedLyric == null &&
        matchedRomanizedLyric == null &&
        originalRomanizedLyric == null &&
        indexedLyric == null &&
        indexedTranslatedLyric == null &&
        indexedRomanizedLyric == null
}
