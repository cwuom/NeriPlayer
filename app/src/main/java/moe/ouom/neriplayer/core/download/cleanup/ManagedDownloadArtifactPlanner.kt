package moe.ouom.neriplayer.core.download.cleanup

import android.content.Context
import moe.ouom.neriplayer.core.download.storage.metadata.ManagedMetadataReadResult
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.catalog.resolveDownloadedSongPlaybackReference
import moe.ouom.neriplayer.core.download.naming.candidateManagedDownloadBaseNames
import moe.ouom.neriplayer.core.download.naming.sanitizeManagedDownloadFileName
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming

internal object ManagedDownloadArtifactPlanner {
    /**
     * builds a complete reference set for an explicit full-library delete
     * using only entries already proven to be managed by the current snapshot
     */
    fun collectFullLibraryArtifactReferences(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): Set<String> {
        return planOwnedFullLibraryDeletion(
            ManagedFullDeleteInventory(
                rootEntries = snapshot.audioEntries + snapshot.metadataEntriesByAudioName.values,
                coverEntries = snapshot.coverEntriesByName.values.toList(),
                lyricEntries = snapshot.lyricEntriesByName.values.toList(),
                temporaryEntries = emptyList(),
                metadataByReference = snapshot.metadataEntriesByAudioName.mapValues { (name, _) ->
                    snapshot.metadataByAudioName[name]?.let(ManagedMetadataReadResult::Found)
                        ?: ManagedMetadataReadResult.Malformed
                }.mapKeys { (name, _) -> snapshot.metadataEntriesByAudioName.getValue(name).reference },
                enumerationComplete = snapshot.rootEntriesComplete && snapshot.sidecarEntriesComplete
            )
        ).requestedReferences
    }

    fun collectArtifactReferences(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        storedAudio: ManagedDownloadStorage.StoredEntry?,
        explicitReferences: List<String> = emptyList(),
        deletingAudioNames: Set<String> = emptySet(),
        referenceIndex: ManagedDownloadDeleteReferenceIndex = ManagedDownloadDeleteReferenceIndex(
            snapshot.knownReferences, snapshot.artifactOwnerAudioNamesByReference
        ),
        uniqueAudioReferencesByName: Map<String, String> = emptyMap()
    ): Set<String> {
        val metadataEntry = storedAudio?.let {
            snapshot.metadataEntriesByAudioName[it.logicalName] ?: snapshot.metadataEntriesByAudioName[it.name]
        }
        val metadata = storedAudio?.let { ManagedDownloadStorage.metadataForAudioEntry(snapshot, it) }
        val audioReference = storedAudio?.reference?.let(referenceIndex::resolve)
        val declaredAudio = metadata?.mediaUri?.takeIf(String::isNotBlank)
        val uniqueAudio = audioReference != null && uniqueAudioReferencesByName[storedAudio.logicalName]
            ?.let(referenceIndex::resolve) == audioReference
        // owner 索引只存名称，同名多文档时不能证明侧载没有被另一文档共享
        val ownsMetadata = uniqueAudio && (declaredAudio == null ||
            referenceIndex.resolve(declaredAudio) == audioReference)
        val metadataReferences = listOfNotNull(metadataEntry?.reference, metadata?.coverPath,
            metadata?.lyricPath, metadata?.translatedLyricPath, metadata?.romanizedLyricPath)
            .mapNotNull(referenceIndex::resolve).toSet()
        return linkedSetOf<String>().apply {
            storedAudio?.reference?.let(::add)
            (explicitReferences + if (ownsMetadata) metadataReferences else emptySet())
                .mapNotNull(referenceIndex::resolve)
                .filter { uniqueAudio || it == audioReference }
                .filterNot { !ownsMetadata && it in metadataReferences }
                .filterNot { reference -> referenceIndex.ownersByReference[reference].orEmpty().any { owner ->
                    owner != storedAudio?.name && owner !in deletingAudioNames
                } }
                .forEach(::add)
        }
    }

    fun trustedMetadataReference(
        reference: String?,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        return reference
            ?.takeIf(String::isNotBlank)
            ?.let { it.takeIf(snapshot.knownReferences::contains) ?: snapshot.referenceIdentityIndex.resolve(it) }
    }

    fun indexedLyricReference(
        audio: ManagedDownloadStorage.StoredEntry,
        songId: Long?,
        translated: Boolean,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        return indexedLyricReference(
            candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension),
            songId = songId,
            translated = translated,
            snapshot = snapshot
        )
    }

    fun indexedRomanizedLyricReference(
        audio: ManagedDownloadStorage.StoredEntry,
        songId: Long?,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        return allIndexedLyricReferences(
            candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension),
            songId = songId,
            kind = ManagedDownloadStorageNaming.LyricKind.ROMANIZED,
            snapshot = snapshot
        ).firstOrNull()
    }

    suspend fun indexedLyricText(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        songId: Long?,
        translated: Boolean,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        val reference = indexedLyricReference(
            audio = audio,
            songId = songId,
            translated = translated,
            snapshot = snapshot
        ) ?: return null
        return ManagedDownloadStorage.readText(context, reference)
    }

    suspend fun indexedRomanizedLyricText(
        context: Context,
        audio: ManagedDownloadStorage.StoredEntry,
        songId: Long?,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        val reference = indexedRomanizedLyricReference(
            audio = audio,
            songId = songId,
            snapshot = snapshot
        ) ?: return null
        return ManagedDownloadStorage.readText(context, reference)
    }

    fun indexedCoverReference(
        audio: ManagedDownloadStorage.StoredEntry,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        return indexedCoverReference(
            candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension),
            snapshot = snapshot
        )
    }

    fun indexedCoverReference(
        candidateBaseNames: List<String>,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        return candidateBaseNames
            .firstNotNullOfOrNull { baseName ->
                sequenceOf("jpg", "jpeg", "png", "webp").firstNotNullOfOrNull { extension ->
                    snapshot.coverEntriesByName["$baseName.$extension"]?.reference
                }
            }
    }

    private fun indexedLyricReference(
        candidateBaseNames: List<String>,
        songId: Long?,
        translated: Boolean,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        val candidates = ManagedDownloadStorage.buildLyricCandidateNames(
            songId = songId,
            candidateBaseNames = candidateBaseNames,
            translated = translated
        )
        return candidates.firstNotNullOfOrNull { candidate ->
            snapshot.lyricEntriesByName[candidate]?.reference
        }
    }

    private fun allIndexedLyricReferences(
        candidateBaseNames: List<String>,
        songId: Long?,
        kind: ManagedDownloadStorageNaming.LyricKind,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): List<String> {
        val candidates = ManagedDownloadStorage.buildLyricCandidateNames(
            songId = songId,
            candidateBaseNames = candidateBaseNames,
            kind = when (kind) {
                ManagedDownloadStorageNaming.LyricKind.ORIGINAL -> ManagedDownloadStorage.LyricKind.ORIGINAL
                ManagedDownloadStorageNaming.LyricKind.TRANSLATED -> ManagedDownloadStorage.LyricKind.TRANSLATED
                ManagedDownloadStorageNaming.LyricKind.ROMANIZED -> ManagedDownloadStorage.LyricKind.ROMANIZED
            }
        )
        return candidates
            .mapNotNull { candidate -> snapshot.lyricEntriesByName[candidate]?.reference }
            .distinct()
    }

    private fun buildSongDeleteContext(
        song: DownloadedSong,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): ManagedDownloadSongDeleteContext {
        val locationReference = resolveDeleteReference(resolveDownloadedSongPlaybackReference(song))
        val snapshotStoredAudio = locationReference?.let(snapshot.audioEntriesByLookupKey::get)
        val storedAudio = snapshotStoredAudio ?: buildFastStoredAudioForDelete(song, locationReference)
        val requiredReferences = listOfNotNull(
            snapshotStoredAudio?.reference ?: locationReference
        )
            .filter(String::isNotBlank)
            .toSet()
        return ManagedDownloadSongDeleteContext(
            song = song,
            storedAudio = storedAudio,
            candidateBaseNames = candidateBaseNames(song, storedAudio?.nameWithoutExtension),
            // catalog 封面只是显示缓存，不能绕过当前 metadata 授权删除另一个文档
            explicitReferences = listOfNotNull(
                locationReference.takeIf { storedAudio == null }
            ),
            requiredReferences = requiredReferences
        )
    }

    fun buildDeleteContext(
        song: DownloadedSong,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): ManagedDownloadSongDeleteContext {
        return buildSongDeleteContext(
            song = song,
            snapshot = snapshot
        )
    }

    private fun buildFastStoredAudioForDelete(
        song: DownloadedSong,
        reference: String?
    ): ManagedDownloadStorage.StoredEntry? {
        val normalizedReference = reference?.takeIf(String::isNotBlank) ?: return null
        val fileName = resolveFastStoredAudioName(normalizedReference)
            ?: sanitizeManagedDownloadFileName("${song.displayArtist()} - ${song.displayName()}")
        return ManagedDownloadStorage.StoredEntry(
            name = fileName,
            reference = normalizedReference,
            mediaUri = song.mediaUri?.takeIf(String::isNotBlank)
                ?: ManagedDownloadStorage.toPlayableUri(normalizedReference)
                ?: normalizedReference,
            localFilePath = normalizedReference.takeIf { it.startsWith("/") },
            sizeBytes = song.fileSize.coerceAtLeast(0L),
            lastModifiedMs = song.downloadTime.coerceAtLeast(0L)
        )
    }

    private fun resolveDeleteReference(reference: String?): String? {
        val normalizedReference = reference?.takeIf(String::isNotBlank) ?: return null
        if (!normalizedReference.startsWith("file://")) {
            return normalizedReference
        }
        return runCatching {
            normalizedReference.toUri().path
        }.getOrNull()?.takeIf(String::isNotBlank) ?: normalizedReference
    }

    private fun resolveFastStoredAudioName(reference: String): String? {
        val rawName = if (reference.startsWith("/")) {
            File(reference).name
        } else {
            runCatching { reference.toUri().lastPathSegment }
                .getOrNull()
                ?.let(Uri::decode)
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
        }
        return rawName?.takeIf(String::isNotBlank)
    }

    private fun candidateBaseNames(
        song: DownloadedSong,
        actualAudioBaseName: String? = null
    ): List<String> {
        val baseNames = linkedSetOf<String>()
        actualAudioBaseName?.takeIf { it.isNotBlank() }?.let(baseNames::add)
        baseNames += sanitizeManagedDownloadFileName("${song.displayArtist()} - ${song.displayName()}")
        baseNames += sanitizeManagedDownloadFileName("${song.artist} - ${song.name}")

        val originalName = song.originalName?.takeIf { it.isNotBlank() } ?: song.name
        val originalArtist = song.originalArtist?.takeIf { it.isNotBlank() } ?: song.artist
        baseNames += sanitizeManagedDownloadFileName("$originalArtist - $originalName")
        return baseNames.toList()
    }

}
