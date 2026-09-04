package moe.ouom.neriplayer.core.download.cleanup

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.File
import moe.ouom.neriplayer.core.download.DownloadedSong
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
        val deletingAudioNames = snapshot.audioEntries
            .mapTo(linkedSetOf(), ManagedDownloadStorage.StoredEntry::name)
        return linkedSetOf<String>().apply {
            // 全库删除不能只依赖歌曲 catalog 关联的音频。完整目录快照中的
            // metadata、封面、歌词和孤儿条目同样属于托管下载产物
            addAll(snapshot.knownReferences)
            snapshot.audioEntries.forEach { audio ->
                val metadata = ManagedDownloadStorage.metadataForAudioEntry(snapshot, audio)
                addAll(
                    collectArtifactReferences(
                        snapshot = snapshot,
                        storedAudio = audio,
                        songId = metadata?.songId ?: 0L,
                        candidateBaseNames = candidateManagedDownloadBaseNames(
                            audio.nameWithoutExtension
                        ),
                        deletingAudioNames = deletingAudioNames
                    )
                )
            }
        }
    }

    fun collectArtifactReferences(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        storedAudio: ManagedDownloadStorage.StoredEntry?,
        songId: Long,
        candidateBaseNames: List<String>,
        explicitReferences: List<String> = emptyList(),
        deletingAudioNames: Set<String> = emptySet()
    ): Set<String> {
        val metadataReference = storedAudio?.let {
            snapshot.metadataEntriesByAudioName[it.logicalName]?.reference
                ?: snapshot.metadataEntriesByAudioName[it.name]?.reference
        }
        val metadata = storedAudio?.let { ManagedDownloadStorage.metadataForAudioEntry(snapshot, it) }
        val resolvedSongId = metadata?.songId ?: songId.takeIf { it > 0L }
        val currentAudioName = storedAudio?.name
        val lyricReferences = buildList {
            trustedMetadataReference(metadata?.lyricPath, snapshot)?.let(::add)
            trustedMetadataReference(metadata?.translatedLyricPath, snapshot)?.let(::add)
            trustedMetadataReference(metadata?.romanizedLyricPath, snapshot)?.let(::add)
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
            addAll(
                allIndexedLyricReferences(
                    candidateBaseNames = candidateBaseNames,
                    songId = resolvedSongId,
                    kind = ManagedDownloadStorageNaming.LyricKind.ROMANIZED,
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
            val stableKey = metadata?.stableKey?.takeIf(String::isNotBlank)
            if (stableKey != null) {
                indexedCoverBaseNames.forEach { baseName ->
                    ManagedDownloadStorageNaming
                        .buildStableCoverCandidateNames(baseName, stableKey)
                        .mapNotNull { name -> snapshot.coverEntriesByName[name]?.reference }
                        .forEach(::add)
                }
            }
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
        return allIndexedLyricReferences(
            candidateBaseNames = candidateBaseNames,
            songId = songId,
            kind = if (translated) {
                ManagedDownloadStorageNaming.LyricKind.TRANSLATED
            } else {
                ManagedDownloadStorageNaming.LyricKind.ORIGINAL
            },
            snapshot = snapshot
        )
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
                    metadata.translatedLyricPath,
                    metadata.romanizedLyricPath
                ).contains(reference)
        }
    }
}
