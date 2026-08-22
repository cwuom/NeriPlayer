package moe.ouom.neriplayer.core.download

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.queue.ManagedDownloadQueueStore
import moe.ouom.neriplayer.core.download.storage.queue.DownloadRecoveryRoomStore
import moe.ouom.neriplayer.core.download.storage.working.ManagedDownloadWorkingStore
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import java.io.File
import java.util.UUID

internal object ManagedDownloadRecoveryFiles {
    private const val TAG = "ManagedDownloadStorage"

    fun buildWorkingFileName(songKey: String, fileName: String): String {
        return ManagedDownloadWorkingStore.buildWorkingFileName(songKey, fileName)
    }

    fun buildWorkingSongKeyHash(songKey: String): String {
        return ManagedDownloadWorkingStore.buildWorkingSongKeyHash(songKey)
    }

    fun createWorkingFile(context: Context, songKey: String, fileName: String): File {
        return ManagedDownloadWorkingStore.createWorkingFile(context.filesDir, songKey, fileName)
    }

    fun createSidecarStagingFile(
        context: Context,
        songKey: String,
        suffix: String
    ): File {
        val directory = stagingDir(context)
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

    fun saveWorkingResumeMetadata(workingFile: File, song: SongItem) {
        ManagedDownloadWorkingStore.saveWorkingResumeMetadata(workingFile, song)
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

    fun clearCancelledDownloadKeys(context: Context) {
        runBlocking(Dispatchers.IO) {
            roomStore(context).clearCancelledDownloadKeys()
        }
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
            context = context.applicationContext,
            database = NeriUserDataDatabase.getInstance(context.applicationContext)
        )
    }
}
