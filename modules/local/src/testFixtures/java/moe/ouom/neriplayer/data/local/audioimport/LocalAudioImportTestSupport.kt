package moe.ouom.neriplayer.data.local.audioimport

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.local.LocalAudioImportResult
import moe.ouom.neriplayer.data.model.local.LocalAudioScanProgress

data class DocumentFileScanResult(
    val failedCount: Int,
    val candidateDisplayNames: List<String?>
)

object LocalAudioImportTestSupport {
    fun buildQuickImportedSong(sourceRef: String, displayName: String): SongItem {
        return LocalAudioImportManager.buildQuickImportedSong(
            seed = QuickImportedSongSeed(
                sourceRef = sourceRef,
                displayName = displayName,
                title = null,
                artist = null,
                album = null,
                durationMs = null
            ),
            unknownArtistLabel = "Unknown Artist"
        )
    }

    suspend fun scanWithMediaStoreResult(
        context: Context,
        folderUri: Uri,
        mediaStoreResult: LocalAudioImportResult?,
        onProgress: (LocalAudioScanProgress) -> Unit = {}
    ): LocalAudioImportResult {
        return LocalAudioImportManager.scanFolderSongsWithMediaStoreResultForTest(
            context = context,
            folderUri = folderUri,
            mediaStoreResult = mediaStoreResult,
            onProgress = onProgress
        )
    }

    suspend fun collectDocumentFileCandidates(
        context: Context,
        root: DocumentFile,
        managedDownloadGate: ManagedDownloadCandidatePublicationGate
    ): DocumentFileScanResult {
        val result = LocalAudioImportManager.collectFolderCandidatesWithDocumentFile(
            context = context,
            root = root,
            progress = LocalAudioScanProgressEmitter(
                scanId = 1L,
                startedAt = SystemClock.elapsedRealtime(),
                onProgress = {}
            ),
            managedDownloadGate = managedDownloadGate
        )
        return DocumentFileScanResult(result.failedCount, result.candidates.map { it.displayName })
    }
}
