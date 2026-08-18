package moe.ouom.neriplayer.core.download.storage.sidecar

import android.content.Context
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.candidateManagedDownloadBaseNames
import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadStorageLookup
import moe.ouom.neriplayer.core.download.storage.naming.ManagedDownloadStorageNaming
import moe.ouom.neriplayer.data.model.SongItem

internal object ManagedDownloadLyricStore {
    fun lyricFileName(baseName: String, translated: Boolean): String {
        return if (translated) "${baseName}_trans.lrc" else "$baseName.lrc"
    }

    fun romanizedLyricFileName(baseName: String): String = "${baseName}_roma.lrc"

    fun findLyricLocation(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        songId: Long,
        candidateBaseNames: List<String>,
        translated: Boolean
    ): String? {
        return findIndexedLyricReference(
            snapshot = snapshot,
            songId = songId.takeIf { it > 0L },
            candidateBaseNames = candidateBaseNames,
            translated = translated
        )
    }

    fun findRomanizedLyricLocation(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        songId: Long,
        candidateBaseNames: List<String>
    ): String? {
        return findIndexedLyricReference(
            snapshot = snapshot,
            songId = songId.takeIf { it > 0L },
            candidateBaseNames = candidateBaseNames,
            kind = ManagedDownloadStorageNaming.LyricKind.ROMANIZED
        )
    }

    fun resolveManagedRomanizedLyricReference(
        context: Context,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        song: SongItem,
        resolvedAudio: ManagedDownloadStorage.StoredEntry?,
        resolvedMetadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        fileNameTemplate: String?,
        exists: (Context, String?) -> Boolean
    ): String? {
        resolvedAudio?.let { audio ->
            findRomanizedLyricLocation(
                snapshot = snapshot,
                songId = resolvedMetadata?.songId ?: song.id,
                candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension)
            )?.let { return it }
        }
        findRomanizedLyricLocation(
            snapshot = snapshot,
            songId = song.id,
            candidateBaseNames = candidateManagedDownloadBaseNames(song, fileNameTemplate)
        )?.let { return it }
        return resolvedMetadata?.romanizedLyricPath
            ?.takeIf { exists(context, it) }
    }

    fun resolveManagedLyricReference(
        context: Context,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        song: SongItem,
        resolvedAudio: ManagedDownloadStorage.StoredEntry?,
        resolvedMetadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        translated: Boolean,
        fileNameTemplate: String?,
        exists: (Context, String?) -> Boolean
    ): String? {
        val metadataReference = if (translated) {
            resolvedMetadata?.translatedLyricPath
        } else {
            resolvedMetadata?.lyricPath
        }
        resolvedAudio?.let { audio ->
            findIndexedLyricReference(
                snapshot = snapshot,
                songId = resolvedMetadata?.songId ?: song.id.takeIf { it > 0L },
                candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension),
                kind = if (translated) {
                    ManagedDownloadStorageNaming.LyricKind.TRANSLATED
                } else {
                    ManagedDownloadStorageNaming.LyricKind.ORIGINAL
                }
            )?.let { return it }
        }

        findIndexedLyricReference(
            snapshot = snapshot,
            songId = song.id.takeIf { it > 0L },
            candidateBaseNames = candidateManagedDownloadBaseNames(song, fileNameTemplate),
            kind = if (translated) {
                ManagedDownloadStorageNaming.LyricKind.TRANSLATED
            } else {
                ManagedDownloadStorageNaming.LyricKind.ORIGINAL
            }
        )?.let { return it }
        return metadataReference?.takeIf { exists(context, it) }
    }

    fun fallbackEmbeddedLyric(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        translated: Boolean
    ): String? {
        return if (translated) {
            metadata?.matchedTranslatedLyric ?: metadata?.originalTranslatedLyric
        } else {
            metadata?.matchedLyric ?: metadata?.originalLyric
        }
    }

    fun selectedEmbeddedLyric(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?,
        translated: Boolean
    ): String? {
        return if (translated) {
            metadata?.matchedTranslatedLyric
        } else {
            metadata?.matchedLyric
        }
    }

    fun fallbackEmbeddedRomanizedLyric(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
    ): String? {
        return metadata?.matchedRomanizedLyric ?: metadata?.originalRomanizedLyric
    }

    fun selectedEmbeddedRomanizedLyric(
        metadata: ManagedDownloadStorage.DownloadedAudioMetadata?
    ): String? = metadata?.matchedRomanizedLyric

    private fun findIndexedLyricReference(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        songId: Long?,
        candidateBaseNames: List<String>,
        translated: Boolean
    ): String? {
        return findIndexedLyricReference(
            snapshot = snapshot,
            songId = songId,
            candidateBaseNames = candidateBaseNames,
            kind = if (translated) {
                ManagedDownloadStorageNaming.LyricKind.TRANSLATED
            } else {
                ManagedDownloadStorageNaming.LyricKind.ORIGINAL
            }
        )
    }

    private fun findIndexedLyricReference(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        songId: Long?,
        candidateBaseNames: List<String>,
        kind: ManagedDownloadStorageNaming.LyricKind
    ): String? {
        return ManagedDownloadStorageLookup.findIndexedEntryByNames(
            names = ManagedDownloadStorageNaming.buildLyricCandidateNames(
                songId = songId,
                candidateBaseNames = candidateBaseNames,
                kind = kind
            ),
            entriesByName = snapshot.lyricEntriesByName
        )?.reference
    }
}
