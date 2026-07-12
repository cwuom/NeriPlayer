package moe.ouom.neriplayer.core.download

import android.content.Context
import moe.ouom.neriplayer.core.download.storage.CANCELLED_DOWNLOAD_KEYS_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.PENDING_DOWNLOAD_QUEUE_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.queue.ManagedDownloadQueueStore
import moe.ouom.neriplayer.core.download.storage.working.ManagedDownloadWorkingStore
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import java.io.File

internal object ManagedDownloadRecoveryFiles {
    private const val TAG = "ManagedDownloadStorage"

    fun buildWorkingFileName(songKey: String, fileName: String): String {
        return ManagedDownloadWorkingStore.buildWorkingFileName(songKey, fileName)
    }

    fun buildWorkingSongKeyHash(songKey: String): String {
        return ManagedDownloadWorkingStore.buildWorkingSongKeyHash(songKey)
    }

    fun createWorkingFile(context: Context, songKey: String, fileName: String): File {
        return ManagedDownloadWorkingStore.createWorkingFile(context.cacheDir, songKey, fileName)
    }

    fun buildWorkingHlsCheckpointFile(workingFile: File): File {
        return ManagedDownloadWorkingStore.buildWorkingHlsCheckpointFile(workingFile)
    }

    fun buildWorkingResumeMetadataFile(workingFile: File): File {
        return ManagedDownloadWorkingStore.buildWorkingResumeMetadataFile(workingFile)
    }

    fun shouldPreserveWorkingFileForResume(
        entry: File,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return ManagedDownloadWorkingStore.shouldPreserveWorkingFileForResume(entry, nowMs)
    }

    fun shouldPreserveWorkingCheckpointForResume(
        entry: File,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return ManagedDownloadWorkingStore.shouldPreserveWorkingCheckpointForResume(entry, nowMs)
    }

    fun shouldPreserveWorkingResumeMetadataForResume(
        entry: File,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        return ManagedDownloadWorkingStore.shouldPreserveWorkingResumeMetadataForResume(entry, nowMs)
    }

    fun saveWorkingResumeMetadata(workingFile: File, song: SongItem) {
        ManagedDownloadWorkingStore.saveWorkingResumeMetadata(workingFile, song)
    }

    fun deleteWorkingResumeMetadata(workingFile: File?) {
        ManagedDownloadWorkingStore.deleteWorkingResumeMetadata(workingFile)
    }

    fun deleteWorkingDownloadArtifacts(workingFile: File?) {
        ManagedDownloadWorkingStore.deleteWorkingDownloadArtifacts(workingFile)
    }

    fun deletePendingWorkingDownloadArtifacts(
        context: Context,
        songKeys: Collection<String>
    ): Set<String> {
        return deletePendingWorkingDownloadArtifactsInDirectory(
            stagingDir = stagingDir(context),
            songKeys = songKeys
        )
    }

    fun deletePendingWorkingDownloadArtifactsInDirectory(
        stagingDir: File,
        songKeys: Collection<String>
    ): Set<String> {
        return ManagedDownloadWorkingStore.deletePendingWorkingDownloadArtifactsInDirectory(stagingDir, songKeys)
    }

    fun listPendingResumableDownloads(context: Context): List<ManagedDownloadStorage.PendingResumableDownload> {
        return listPendingResumableDownloadsInDirectory(stagingDir(context))
    }

    fun listPendingResumableDownloadsInDirectory(
        stagingDir: File,
        nowMs: Long = System.currentTimeMillis()
    ): List<ManagedDownloadStorage.PendingResumableDownload> {
        return ManagedDownloadWorkingStore.listPendingResumableDownloadsInDirectory(stagingDir, nowMs)
    }

    fun cleanupStagingFiles(context: Context): ManagedDownloadStorage.StartupRecoveryResult {
        return cleanupStagingFilesInDirectory(stagingDir(context))
    }

    fun cleanupStagingFilesInDirectory(
        stagingDir: File,
        nowMs: Long = System.currentTimeMillis()
    ): ManagedDownloadStorage.StartupRecoveryResult {
        return ManagedDownloadWorkingStore.cleanupStagingFilesInDirectory(stagingDir, nowMs)
    }

    fun upsertPendingDownloadQueue(context: Context, songs: List<SongItem>) {
        upsertPendingDownloadQueueInFile(
            queueFile = pendingDownloadQueueFile(context),
            songs = songs
        )
    }

    fun listPendingQueuedDownloads(context: Context): List<ManagedDownloadStorage.PendingDownloadQueueEntry> {
        return listPendingQueuedDownloadsFromFile(pendingDownloadQueueFile(context))
    }

    fun removePendingDownloadQueueEntries(context: Context, songKeys: Collection<String>) {
        removePendingDownloadQueueEntriesFromFile(
            queueFile = pendingDownloadQueueFile(context),
            songKeys = songKeys
        )
    }

    fun clearPendingDownloadQueue(context: Context) {
        clearPendingDownloadQueueFile(pendingDownloadQueueFile(context))
    }

    fun markCancelledDownloadKeys(context: Context, songKeys: Collection<String>) {
        markCancelledDownloadKeysInFile(
            keysFile = cancelledDownloadKeysFile(context),
            songKeys = songKeys
        )
    }

    fun listCancelledDownloadKeys(context: Context): Set<String> {
        return listCancelledDownloadKeysFromFile(cancelledDownloadKeysFile(context))
    }

    fun removeCancelledDownloadKeys(context: Context, songKeys: Collection<String>) {
        removeCancelledDownloadKeysFromFile(
            keysFile = cancelledDownloadKeysFile(context),
            songKeys = songKeys
        )
    }

    fun clearCancelledDownloadKeys(context: Context) {
        clearCancelledDownloadKeysFile(cancelledDownloadKeysFile(context))
    }

    fun upsertPendingDownloadQueueInFile(
        queueFile: File,
        songs: List<SongItem>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        ManagedDownloadQueueStore.upsertPendingDownloadQueueInFile(queueFile, songs, nowMs)
    }

    fun listPendingQueuedDownloadsFromFile(
        queueFile: File
    ): List<ManagedDownloadStorage.PendingDownloadQueueEntry> {
        return ManagedDownloadQueueStore.listPendingQueuedDownloadsFromFile(queueFile)
    }

    fun removePendingDownloadQueueEntriesFromFile(
        queueFile: File,
        songKeys: Collection<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        ManagedDownloadQueueStore.removePendingDownloadQueueEntriesFromFile(queueFile, songKeys, nowMs)
    }

    fun clearPendingDownloadQueueFile(
        queueFile: File,
        nowMs: Long = System.currentTimeMillis()
    ) {
        ManagedDownloadQueueStore.clearPendingDownloadQueueFile(queueFile, nowMs)
    }

    fun markCancelledDownloadKeysInFile(
        keysFile: File,
        songKeys: Collection<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        ManagedDownloadQueueStore.markCancelledDownloadKeysInFile(keysFile, songKeys, nowMs)
    }

    fun listCancelledDownloadKeysFromFile(keysFile: File): Set<String> {
        return ManagedDownloadQueueStore.listCancelledDownloadKeysFromFile(keysFile)
    }

    fun removeCancelledDownloadKeysFromFile(
        keysFile: File,
        songKeys: Collection<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        ManagedDownloadQueueStore.removeCancelledDownloadKeysFromFile(keysFile, songKeys, nowMs)
    }

    fun clearCancelledDownloadKeysFile(
        keysFile: File,
        nowMs: Long = System.currentTimeMillis()
    ) {
        ManagedDownloadQueueStore.clearCancelledDownloadKeysFile(keysFile, nowMs)
    }

    fun parseWorkingResumeMetadataSong(rawJson: String): SongItem? {
        return runCatching {
            ManagedDownloadStorageJsonCodec.workingResumeMetadataSongFromJson(rawJson)
        }.onFailure {
            NPLogger.w(TAG, "解析下载恢复元数据失败: ${it.message}")
        }.getOrNull()
    }

    private fun stagingDir(context: Context): File {
        return File(context.cacheDir, DOWNLOAD_STAGING_DIR_NAME)
    }

    private fun pendingDownloadQueueFile(context: Context): File {
        return File(context.filesDir, PENDING_DOWNLOAD_QUEUE_FILE_NAME)
    }

    private fun cancelledDownloadKeysFile(context: Context): File {
        return File(context.filesDir, CANCELLED_DOWNLOAD_KEYS_FILE_NAME)
    }
}
