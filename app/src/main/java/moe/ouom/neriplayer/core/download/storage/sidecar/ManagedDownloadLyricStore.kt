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
        if (exists(context, metadataReference)) {
            return metadataReference
        }

        resolvedAudio?.let { audio ->
            findIndexedLyricReference(
                snapshot = snapshot,
                songId = resolvedMetadata?.songId ?: song.id.takeIf { it > 0L },
                candidateBaseNames = candidateManagedDownloadBaseNames(audio.nameWithoutExtension),
                translated = translated
            )?.let { return it }
        }

        return findIndexedLyricReference(
            snapshot = snapshot,
            songId = song.id.takeIf { it > 0L },
            candidateBaseNames = candidateManagedDownloadBaseNames(song, fileNameTemplate),
            translated = translated
        )
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

    private fun findIndexedLyricReference(
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot,
        songId: Long?,
        candidateBaseNames: List<String>,
        translated: Boolean
    ): String? {
        return ManagedDownloadStorageLookup.findIndexedEntryByNames(
            names = ManagedDownloadStorageNaming.buildLyricCandidateNames(
                songId = songId,
                candidateBaseNames = candidateBaseNames,
                translated = translated
            ),
            entriesByName = snapshot.lyricEntriesByName
        )?.reference
    }
}
