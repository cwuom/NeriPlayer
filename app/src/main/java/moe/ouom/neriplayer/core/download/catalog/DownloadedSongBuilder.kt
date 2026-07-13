package moe.ouom.neriplayer.core.download.catalog

import android.content.Context
import androidx.core.net.toUri
import kotlin.LazyThreadSafetyMode
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.cleanup.ManagedDownloadArtifactPlanner
import moe.ouom.neriplayer.core.download.naming.candidateManagedDownloadFileNameTemplates
import moe.ouom.neriplayer.core.download.naming.parseManagedDownloadBaseName
import moe.ouom.neriplayer.core.download.policy.resolveDownloadedLyricOverride
import moe.ouom.neriplayer.core.download.policy.shouldInspectDownloadedAudioDetails
import moe.ouom.neriplayer.core.download.metadata.DownloadedAudioMetadataStore
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport

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
        val metadataEntry = effectiveSnapshot.metadataEntriesByAudioName[storedAudio.name]
        val snapshotMetadata = effectiveSnapshot.metadataByAudioName[storedAudio.name]
        val metadata = if (loadLyricContents || resolveLyricFallbacks) {
            metadataStore.read(
                context = context,
                audio = storedAudio,
                metadataEntry = metadataEntry
            ) ?: snapshotMetadata
        } else {
            snapshotMetadata ?: metadataStore.read(
                context = context,
                audio = storedAudio,
                metadataEntry = metadataEntry
            )
        }
        val (parsedArtist, parsedTitle) = parseDownloadedFileName(storedAudio.name)
        val cachedCoverReference = resolveAccessibleManagedReference(
            context = context,
            snapshot = effectiveSnapshot,
            metadata?.coverPath,
            metadata?.coverUrl,
            metadata?.originalCoverUrl
        )
            ?: ManagedDownloadArtifactPlanner.indexedCoverReference(storedAudio, effectiveSnapshot)
        val lyricContent = resolveLyricContent(
            context = context,
            storedAudio = storedAudio,
            metadata = metadata,
            snapshot = effectiveSnapshot,
            loadLyricContents = loadLyricContents,
            resolveLyricFallbacks = resolveLyricFallbacks
        )
        val needsLocalLyricFallback = loadLyricContents &&
            lyricContent.fileLyric.isNullOrBlank() &&
            metadata?.matchedLyric == null &&
            metadata?.originalLyric == null &&
            lyricContent.indexedLyric.isNullOrBlank()
        val localDetails by lazy(LazyThreadSafetyMode.NONE) {
            if (
                shouldInspectDownloadedAudioDetails(
                    allowSlowLocalInspection = allowSlowLocalInspection,
                    metadata = metadata,
                    coverReference = cachedCoverReference,
                    needsLocalLyricFallback = needsLocalLyricFallback
                )
            ) {
                inspectAudioDetails(context, storedAudio)
            } else {
                null
            }
        }
        val coverReference = cachedCoverReference ?: localDetails?.coverUri
        val matchedLyric = if (loadLyricContents) {
            resolveDownloadedLyricOverride(
                fileLyric = lyricContent.fileLyric,
                embeddedMatchedLyric = metadata?.matchedLyric,
                embeddedOriginalLyric = metadata?.originalLyric,
                localLyricContent = localDetails?.lyricContent,
                indexedLyricContent = lyricContent.indexedLyric
            )
        } else {
            null
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
            null
        }

        return DownloadedSong(
            id = metadata?.songId ?: storedAudio.reference.hashCode().toLong(),
            name = metadata?.name?.takeIf(String::isNotBlank)
                ?: localDetails?.title?.takeIf(String::isNotBlank)
                ?: parsedTitle,
            artist = metadata?.artist?.takeIf(String::isNotBlank)
                ?: localDetails?.artist?.takeIf(String::isNotBlank)
                ?: parsedArtist,
            album = (if (metadata == null) localDetails?.album?.takeIf(String::isNotBlank) else null)
                ?: context.getString(R.string.local_files),
            filePath = storedAudio.reference,
            fileSize = storedAudio.sizeBytes,
            downloadTime = existingDownloadTime ?: storedAudio.lastModifiedMs,
            coverPath = coverReference,
            coverUrl = metadata?.coverUrl,
            matchedLyric = matchedLyric,
            matchedTranslatedLyric = matchedTranslatedLyric,
            matchedLyricSource = metadata?.matchedLyricSource,
            matchedSongId = metadata?.matchedSongId,
            userLyricOffsetMs = metadata?.userLyricOffsetMs ?: 0L,
            customCoverUrl = metadata?.customCoverUrl,
            customName = metadata?.customName,
            customArtist = metadata?.customArtist,
            originalName = metadata?.originalName ?: localDetails?.originalTitle,
            originalArtist = metadata?.originalArtist ?: localDetails?.originalArtist,
            originalCoverUrl = metadata?.originalCoverUrl,
            originalLyric = metadata?.originalLyric,
            originalTranslatedLyric = metadata?.originalTranslatedLyric,
            mediaUri = storedAudio.playbackUri,
            durationMs = metadata?.durationMs?.takeIf { it > 0L } ?: localDetails?.durationMs ?: 0L,
            stableKey = metadata?.stableKey
        )
    }

    fun inspectAudioDetails(
        context: Context,
        storedAudio: ManagedDownloadStorage.StoredEntry
    ) = runCatching {
        LocalMediaSupport.inspect(context, storedAudio.playbackUri.toUri())
    }.onFailure { error ->
        NPLogger.w(loggerTag, "读取已下载音频标签失败: ${storedAudio.name} - ${error.message}")
    }.getOrNull()

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
            lyricReference.resolvedReference?.let { ManagedDownloadStorage.readText(context, it) }
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
            translatedLyricReference.resolvedReference?.let { ManagedDownloadStorage.readText(context, it) }
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
        return DownloadedSongLyricContent(
            fileLyric = fileLyric,
            indexedLyric = indexedLyric,
            fileTranslatedLyric = fileTranslatedLyric,
            indexedTranslatedLyric = indexedTranslatedLyric
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
                indexedReference = indexedReference
            )
        }
        val metadataReference = if (translated) {
            metadata?.translatedLyricPath
        } else {
            metadata?.lyricPath
        }
        return DownloadedLyricReference(
            resolvedReference = resolveAccessibleManagedReference(
                context = context,
                snapshot = snapshot,
                metadataReference,
                indexedReference
            ),
            indexedReference = indexedReference
        )
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
        if (!loadLyricContents || !resolveLyricFallbacks || !fileLyric.isNullOrBlank()) {
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

    private suspend fun resolveAccessibleManagedReference(
        context: Context,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        vararg references: String?
    ): String? {
        return references.firstNotNullOfOrNull { reference ->
            val candidate = reference?.takeIf(::isResolvableLocalReference)
                ?: return@firstNotNullOfOrNull null
            candidate.takeIf {
                candidate in snapshot.knownReferences || ManagedDownloadStorage.exists(context, candidate)
            }
        }
    }

    private data class DownloadedSongLyricContent(
        val fileLyric: String?,
        val indexedLyric: String?,
        val fileTranslatedLyric: String?,
        val indexedTranslatedLyric: String?
    )

    private data class DownloadedLyricReference(
        val resolvedReference: String?,
        val indexedReference: String?
    )
}
