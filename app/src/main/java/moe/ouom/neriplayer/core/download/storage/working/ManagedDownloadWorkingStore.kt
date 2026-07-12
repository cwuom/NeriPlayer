package moe.ouom.neriplayer.core.download.storage.working

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_FILE_PREFIX
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_FILE_SUFFIX
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_HLS_CHECKPOINT_SUFFIX
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_MAX_AGE_MS
import moe.ouom.neriplayer.core.download.storage.DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.util.NPLogger
import java.io.File

internal object ManagedDownloadWorkingStore {
    private const val TAG = "ManagedDownloadStorage"

    fun buildWorkingFileName(songKey: String, fileName: String): String {
        val normalizedKey = buildWorkingSongKeyHash(songKey)
        val normalizedPrefix = fileName.substringBeforeLast('.', fileName)
            .ifBlank { "download" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeLast(48)
            .ifBlank { "download" }
        val extension = fileName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?.replace(Regex("[^A-Za-z0-9]"), "")
            ?.take(8)
            ?.lowercase()
        val fileBody = "${DOWNLOAD_STAGING_FILE_PREFIX}${normalizedKey}_$normalizedPrefix"
        return extension?.let { "$fileBody.$it$DOWNLOAD_STAGING_FILE_SUFFIX" }
            ?: "$fileBody$DOWNLOAD_STAGING_FILE_SUFFIX"
    }

    fun buildWorkingSongKeyHash(songKey: String): String {
        return java.lang.Long.toHexString(songKey.hashCode().toLong() and 0xffffffffL)
    }

    fun createWorkingFile(cacheDir: File, songKey: String, fileName: String): File {
        val stagingDir = File(cacheDir, DOWNLOAD_STAGING_DIR_NAME).apply { mkdirs() }
        return File(stagingDir, buildWorkingFileName(songKey, fileName))
    }

    fun buildWorkingHlsCheckpointFile(workingFile: File): File {
        return File(workingFile.parentFile, workingFile.name + DOWNLOAD_STAGING_HLS_CHECKPOINT_SUFFIX)
    }

    fun buildWorkingResumeMetadataFile(workingFile: File): File {
        return File(workingFile.parentFile, workingFile.name + DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX)
    }

    fun shouldPreserveWorkingFileForResume(
        entry: File,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!isFreshNamedNonEmptyWorkingFile(entry, nowMs)) {
            return false
        }
        return hasFreshValidWorkingResumeMetadata(entry, nowMs)
    }

    fun shouldPreserveWorkingCheckpointForResume(
        entry: File,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!entry.isFile) {
            return false
        }
        if (!entry.name.endsWith(DOWNLOAD_STAGING_HLS_CHECKPOINT_SUFFIX)) {
            return false
        }
        val workingFileName = entry.name.removeSuffix(DOWNLOAD_STAGING_HLS_CHECKPOINT_SUFFIX)
        if (workingFileName.isBlank()) {
            return false
        }
        val ageMs = (nowMs - entry.lastModified().coerceAtLeast(0L)).coerceAtLeast(0L)
        if (ageMs > DOWNLOAD_STAGING_MAX_AGE_MS) {
            return false
        }
        val pairedWorkingFile = File(entry.parentFile, workingFileName)
        return shouldPreserveWorkingFileForResume(pairedWorkingFile, nowMs)
    }

    fun shouldPreserveWorkingResumeMetadataForResume(
        entry: File,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!entry.isFile) {
            return false
        }
        if (!entry.name.endsWith(DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX)) {
            return false
        }
        val workingFileName = entry.name.removeSuffix(DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX)
        if (workingFileName.isBlank()) {
            return false
        }
        val ageMs = (nowMs - entry.lastModified().coerceAtLeast(0L)).coerceAtLeast(0L)
        if (ageMs > DOWNLOAD_STAGING_MAX_AGE_MS) {
            return false
        }
        val pairedWorkingFile = File(entry.parentFile, workingFileName)
        if (!isFreshNamedNonEmptyWorkingFile(pairedWorkingFile, nowMs)) {
            return false
        }
        return hasValidWorkingResumeMetadataFile(entry)
    }

    fun saveWorkingResumeMetadata(
        workingFile: File,
        song: SongItem
    ) {
        val metadataFile = buildWorkingResumeMetadataFile(workingFile)
        runCatching {
            metadataFile.parentFile?.mkdirs()
            metadataFile.writeText(
                ManagedDownloadStorageJsonCodec.workingResumeMetadataToJson(song).toString(),
                Charsets.UTF_8
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "写入下载恢复元数据失败: ${metadataFile.name}, ${error.message}")
        }
    }

    fun deleteWorkingResumeMetadata(workingFile: File?) {
        workingFile ?: return
        val metadataFile = buildWorkingResumeMetadataFile(workingFile)
        if (metadataFile.exists()) {
            runCatching {
                metadataFile.delete()
            }
        }
    }

    fun deleteWorkingDownloadArtifacts(workingFile: File?) {
        workingFile ?: return
        deleteWorkingResumeMetadata(workingFile)
        val checkpointFile = buildWorkingHlsCheckpointFile(workingFile)
        if (checkpointFile.exists()) {
            runCatching {
                checkpointFile.delete()
            }
        }
        if (workingFile.exists()) {
            runCatching {
                workingFile.delete()
            }
        }
    }

    fun deletePendingWorkingDownloadArtifactsInDirectory(
        stagingDir: File,
        songKeys: Collection<String>
    ): Set<String> {
        val keys = songKeys.filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) {
            return emptySet()
        }
        val deletedKeys = listPendingResumableDownloadsInDirectory(stagingDir)
            .filter { pendingDownload -> pendingDownload.song.stableKey() in keys }
            .mapNotNullTo(linkedSetOf()) { pendingDownload ->
                val songKey = pendingDownload.song.stableKey()
                deleteWorkingDownloadArtifacts(pendingDownload.workingFile)
                songKey
            }
        val keyHashes = keys.associateBy(::buildWorkingSongKeyHash)
        stagingDir.listFiles()
            .orEmpty()
            .filter { entry -> matchingWorkingArtifactSongKey(entry.name, keyHashes) != null }
            .forEach { entry ->
                val songKey = matchingWorkingArtifactSongKey(entry.name, keyHashes) ?: return@forEach
                if (deleteWorkingArtifactEntry(entry)) {
                    deletedKeys += songKey
                }
            }
        return deletedKeys
    }

    fun listPendingResumableDownloadsInDirectory(
        stagingDir: File,
        nowMs: Long = System.currentTimeMillis()
    ): List<ManagedDownloadStorage.PendingResumableDownload> {
        val metadataEntries = stagingDir.listFiles { _, name ->
            name.endsWith(DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX)
        }.orEmpty()
        if (metadataEntries.isEmpty()) {
            return emptyList()
        }
        return metadataEntries
            .asSequence()
            .filter { metadataFile ->
                shouldPreserveWorkingResumeMetadataForResume(metadataFile, nowMs)
            }
            .mapNotNull { metadataFile ->
                val workingFileName = metadataFile.name.removeSuffix(DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX)
                val workingFile = File(stagingDir, workingFileName)
                val song = runCatching {
                    metadataFile.readText(Charsets.UTF_8)
                }.mapCatching(ManagedDownloadStorage::parseWorkingResumeMetadataSong)
                    .getOrNull()
                    ?: return@mapNotNull null
                ManagedDownloadStorage.PendingResumableDownload(
                    song = song,
                    workingFile = workingFile
                )
            }
            .sortedBy { it.workingFile.lastModified() }
            .distinctBy { it.song.stableKey() }
            .toList()
    }

    fun cleanupStagingFilesInDirectory(
        stagingDir: File,
        nowMs: Long = System.currentTimeMillis()
    ): ManagedDownloadStorage.StartupRecoveryResult {
        val stagingEntries = stagingDir.listFiles().orEmpty()
        if (stagingEntries.isEmpty()) {
            return ManagedDownloadStorage.StartupRecoveryResult()
        }

        var cleanedCount = 0
        var failedCount = 0
        var preservedCount = 0
        stagingEntries.forEach { entry ->
            if (
                shouldPreserveWorkingFileForResume(entry, nowMs) ||
                shouldPreserveWorkingCheckpointForResume(entry, nowMs) ||
                shouldPreserveWorkingResumeMetadataForResume(entry, nowMs)
            ) {
                preservedCount++
                return@forEach
            }
            val deleted = deleteWorkingArtifactEntry(entry)
            if (deleted) {
                cleanedCount++
            } else {
                failedCount++
            }
        }
        if (cleanedCount > 0 || failedCount > 0 || preservedCount > 0) {
            NPLogger.d(
                TAG,
                "清理下载临时区完成: cleaned=$cleanedCount, failed=$failedCount, preserved=$preservedCount"
            )
        }
        return ManagedDownloadStorage.StartupRecoveryResult(
            cleanedCount = cleanedCount,
            failedCount = failedCount
        )
    }

    private fun isFreshNamedNonEmptyWorkingFile(
        entry: File,
        nowMs: Long
    ): Boolean {
        if (!entry.isFile) {
            return false
        }
        if (!entry.name.startsWith(DOWNLOAD_STAGING_FILE_PREFIX)) {
            return false
        }
        if (!entry.name.endsWith(DOWNLOAD_STAGING_FILE_SUFFIX)) {
            return false
        }
        if (entry.length() <= 0L) {
            return false
        }
        val ageMs = (nowMs - entry.lastModified().coerceAtLeast(0L)).coerceAtLeast(0L)
        return ageMs <= DOWNLOAD_STAGING_MAX_AGE_MS
    }

    private fun hasFreshValidWorkingResumeMetadata(
        workingFile: File,
        nowMs: Long
    ): Boolean {
        val metadataFile = buildWorkingResumeMetadataFile(workingFile)
        if (!metadataFile.isFile) {
            return false
        }
        val ageMs = (nowMs - metadataFile.lastModified().coerceAtLeast(0L)).coerceAtLeast(0L)
        if (ageMs > DOWNLOAD_STAGING_MAX_AGE_MS) {
            return false
        }
        return hasValidWorkingResumeMetadataFile(metadataFile)
    }

    private fun hasValidWorkingResumeMetadataFile(metadataFile: File): Boolean {
        if (!metadataFile.isFile) {
            return false
        }
        return runCatching {
            ManagedDownloadStorage.parseWorkingResumeMetadataSong(metadataFile.readText(Charsets.UTF_8)) != null
        }.getOrDefault(false)
    }

    private fun matchingWorkingArtifactSongKey(
        fileName: String,
        songKeyByHash: Map<String, String>
    ): String? {
        if (!fileName.startsWith(DOWNLOAD_STAGING_FILE_PREFIX)) {
            return null
        }
        val keyHash = fileName
            .removePrefix(DOWNLOAD_STAGING_FILE_PREFIX)
            .substringBefore('_', missingDelimiterValue = "")
            .takeIf(String::isNotBlank)
            ?: return null
        return songKeyByHash[keyHash]
    }

    private fun deleteWorkingArtifactEntry(entry: File): Boolean {
        return runCatching {
            if (entry.isDirectory) {
                entry.deleteRecursively()
            } else {
                !entry.exists() || entry.delete()
            }
        }.getOrDefault(false)
    }
}
