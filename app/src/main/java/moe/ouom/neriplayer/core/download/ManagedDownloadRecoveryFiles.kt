package moe.ouom.neriplayer.core.download

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.queue.DownloadRecoveryRoomStore
import moe.ouom.neriplayer.core.download.storage.working.ManagedDownloadWorkingStore
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import java.io.File
import java.util.UUID

internal object ManagedDownloadRecoveryFiles {
    private const val TAG = "ManagedDownloadStorage"

    fun buildWorkingFileName(
        songKey: String,
        fileName: String,
        operationId: String? = null
    ): String {
        return ManagedDownloadWorkingStore.buildWorkingFileName(songKey, fileName, operationId)
    }

    fun buildWorkingSongKeyHash(songKey: String): String {
        return ManagedDownloadWorkingStore.buildWorkingSongKeyHash(songKey)
    }

    fun createWorkingFile(
        context: Context,
        songKey: String,
        fileName: String,
        operationId: String? = null
    ): File {
        return ManagedDownloadWorkingStore.createWorkingFile(
            context.filesDir,
            songKey,
            fileName,
            operationId
        )
    }

    fun createSidecarStagingFile(
        context: Context,
        songKey: String,
        suffix: String,
        operationId: String? = null
    ): File {
        val directory = operationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { operation ->
                val safeId = operation.replace(Regex("[^A-Za-z0-9._-]"), "_")
                File(stagingDir(context), safeId)
            }
            ?: stagingDir(context)
        if (!directory.exists()) {
            directory.mkdirs()
        }
        val safeSuffix = suffix
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(24)
            .ifBlank { "artifact" }
        return File(
            directory,
            "npdl_sidecar_${buildWorkingSongKeyHash(songKey)}_" +
                "${UUID.randomUUID()}.$safeSuffix"
        )
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

    fun saveWorkingResumeMetadata(
        workingFile: File,
        song: SongItem,
        operationId: String? = null
    ) {
        ManagedDownloadWorkingStore.saveWorkingResumeMetadata(workingFile, song, operationId)
    }

    fun readWorkingResumeFingerprint(workingFile: File): ManagedDownloadStorage.WorkingResumeFingerprint? {
        return ManagedDownloadWorkingStore.readWorkingResumeFingerprint(workingFile)
    }

    fun updateWorkingResumeFingerprint(
        workingFile: File,
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint
    ) {
        ManagedDownloadWorkingStore.updateWorkingResumeFingerprint(workingFile, fingerprint)
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
        runBlocking(Dispatchers.IO) {
            roomStore(context).upsertPendingDownloadQueue(songs)
        }
    }

    fun listPendingQueuedDownloads(context: Context): List<ManagedDownloadStorage.PendingDownloadQueueEntry> {
        return runBlocking(Dispatchers.IO) {
            roomStore(context).listPendingQueuedDownloads()
        }
    }

    fun findQueuedOperationIdForSong(context: Context, songKey: String): String? {
        return runBlocking(Dispatchers.IO) {
            roomStore(context).findQueuedOperationIdForSong(songKey)
        }
    }

    fun removePendingDownloadQueueEntries(context: Context, songKeys: Collection<String>) {
        runBlocking(Dispatchers.IO) {
            roomStore(context).removePendingDownloadQueueEntries(songKeys)
        }
    }

    fun clearPendingDownloadQueue(context: Context) {
        runBlocking(Dispatchers.IO) {
            roomStore(context).clearPendingDownloadQueue()
        }
    }

    fun markCancelledDownloadKeys(context: Context, songKeys: Collection<String>) {
        runBlocking(Dispatchers.IO) {
            roomStore(context).markCancelledDownloadKeys(songKeys)
        }
    }

    fun listCancelledDownloadKeys(context: Context): Set<String> {
        return runBlocking(Dispatchers.IO) {
            roomStore(context).listCancelledDownloadKeys()
        }
    }

    fun removeCancelledDownloadKeys(context: Context, songKeys: Collection<String>) {
        runBlocking(Dispatchers.IO) {
            roomStore(context).removeCancelledDownloadKeys(songKeys)
        }
    }

    fun discardCancelledDownloadKeys(context: Context, songKeys: Collection<String>) {
        runBlocking(Dispatchers.IO) {
            roomStore(context).discardCancelledDownloadKeys(songKeys)
        }
    }

    fun clearCancelledDownloadKeys(context: Context) {
        runBlocking(Dispatchers.IO) {
            roomStore(context).clearCancelledDownloadKeys()
        }
    }

    fun parseWorkingResumeMetadataSong(rawJson: String): SongItem? {
        return runCatching {
            ManagedDownloadStorageJsonCodec.workingResumeMetadataSongFromJson(rawJson)
        }.onFailure {
            NPLogger.w(TAG, "解析下载恢复元数据失败: ${it.message}")
        }.getOrNull()
    }

    private fun stagingDir(context: Context): File {
        val dir = File(context.filesDir, DOWNLOAD_STAGING_DIR_NAME)
        migrateLegacyStagingDir(context, dir)
        return dir
    }

    private fun migrateLegacyStagingDir(context: Context, targetDir: File) {
        val legacyDir = File(context.cacheDir, DOWNLOAD_STAGING_DIR_NAME)
        if (!legacyDir.isDirectory || legacyDir.absolutePath == targetDir.absolutePath) {
            return
        }
        val legacyEntries = legacyDir.listFiles().orEmpty()
        if (legacyEntries.isEmpty()) {
            return
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            NPLogger.w(TAG, "创建下载暂存目录失败，跳过旧断点迁移: ${targetDir.absolutePath}")
            return
        }

        var movedCount = 0
        var skippedCount = 0
        var failedCount = 0
        legacyEntries.forEach { entry ->
            val target = File(targetDir, entry.name)
            if (target.exists()) {
                skippedCount++
                return@forEach
            }
            if (entry.renameTo(target)) {
                movedCount++
            } else {
                failedCount++
            }
        }
        if (movedCount > 0 || skippedCount > 0 || failedCount > 0) {
            NPLogger.d(
                TAG,
                "迁移下载暂存目录完成: moved=$movedCount, skipped=$skippedCount, failed=$failedCount"
            )
        }
    }

    private fun roomStore(context: Context): DownloadRecoveryRoomStore {
        return DownloadRecoveryRoomStore(
            context = context.applicationContext
        )
    }
}
