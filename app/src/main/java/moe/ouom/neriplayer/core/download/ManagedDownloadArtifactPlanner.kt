package moe.ouom.neriplayer.core.download

import android.content.Context
import android.net.Uri
import java.io.File

internal object ManagedDownloadArtifactPlanner {
    fun collectArtifactReferences(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        storedAudio: ManagedDownloadStorage.StoredEntry?,
        songId: Long,
        candidateBaseNames: List<String>,
        explicitReferences: List<String> = emptyList(),
        deletingAudioNames: Set<String> = emptySet()
    ): Set<String> {
        val metadataReference = storedAudio?.let { snapshot.metadataEntriesByAudioName[it.name]?.reference }
        val metadata = storedAudio?.let { snapshot.metadataByAudioName[it.name] }
        val resolvedSongId = metadata?.songId ?: songId.takeIf { it > 0L }
        val currentAudioName = storedAudio?.name
        val lyricReferences = buildList {
            trustedMetadataReference(metadata?.lyricPath, snapshot)?.let(::add)
            trustedMetadataReference(metadata?.translatedLyricPath, snapshot)?.let(::add)
            addAll(
                allIndexedLyricReferences(
                    candidateBaseNames = candidateBaseNames,
                    songId = resolvedSongId,
                    translated = false,
                    snapshot = snapshot
                )
            )
            addAll(
                allIndexedLyricReferences(
                    candidateBaseNames = candidateBaseNames,
                    songId = resolvedSongId,
                    translated = true,
                    snapshot = snapshot
                )
            )
        }
        val coverReferences = buildList {
            trustedMetadataReference(metadata?.coverPath, snapshot)?.let(::add)
            val indexedCoverBaseNames = storedAudio
                ?.let { candidateManagedDownloadBaseNames(it.nameWithoutExtension) }
                ?: candidateBaseNames
            addAll(allIndexedCoverReferences(indexedCoverBaseNames, snapshot))
        }

        return linkedSetOf<String>().apply {
            storedAudio?.reference?.let(::add)
            explicitReferences
                .plus(listOfNotNull(metadataReference))
                .plus(coverReferences)
                .plus(lyricReferences)
                .distinct()
                .forEach { reference ->
                    if (
                        metadataReference == null ||
                        reference == metadataReference ||
                        !isReferenceOwnedByOtherDownload(
                            snapshot = snapshot,
                            currentAudioName = currentAudioName,
                            reference = reference,
                            deletingAudioNames = deletingAudioNames
                        )
                    ) {
                        add(reference)
                    }
                }
        }
    }

    fun trustedMetadataReference(
        reference: String?,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): String? {
        return reference
            ?.takeIf(String::isNotBlank)
            ?.takeIf(snapshot.knownReferences::contains)
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

    private fun allIndexedCoverReferences(
        candidateBaseNames: List<String>,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): List<String> {
        return candidateBaseNames
            .flatMap { baseName ->
                sequenceOf("jpg", "jpeg", "png", "webp")
                    .mapNotNull { extension ->
                        snapshot.coverEntriesByName["$baseName.$extension"]?.reference
                    }
            }
            .distinct()
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
        translated: Boolean,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ): List<String> {
        val candidates = ManagedDownloadStorage.buildLyricCandidateNames(
            songId = songId,
            candidateBaseNames = candidateBaseNames,
            translated = translated
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
        val metadataReference = storedAudio?.let(ManagedDownloadStorage::metadataReferenceForAudio)
        val requiredReferences = listOfNotNull(
            snapshotStoredAudio?.reference ?: locationReference
        )
            .filter(String::isNotBlank)
            .toSet()
        return ManagedDownloadSongDeleteContext(
            song = song,
            storedAudio = storedAudio,
            candidateBaseNames = candidateBaseNames(song, storedAudio?.nameWithoutExtension),
            explicitReferences = listOfNotNull(
                metadataReference,
                song.coverPath,
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
            Uri.parse(normalizedReference).path
        }.getOrNull()?.takeIf(String::isNotBlank) ?: normalizedReference
    }

    private fun resolveFastStoredAudioName(reference: String): String? {
        val rawName = if (reference.startsWith("/")) {
            File(reference).name
        } else {
            runCatching { Uri.parse(reference).lastPathSegment }
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

    private fun isReferenceOwnedByOtherDownload(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        currentAudioName: String?,
        reference: String,
        deletingAudioNames: Set<String> = emptySet()
    ): Boolean {
        return snapshot.metadataByAudioName.any { (audioName, metadata) ->
            audioName != currentAudioName &&
                audioName !in deletingAudioNames &&
                listOfNotNull(
                    metadata.coverPath,
                    metadata.lyricPath,
                    metadata.translatedLyricPath
                ).contains(reference)
        }
    }
}
