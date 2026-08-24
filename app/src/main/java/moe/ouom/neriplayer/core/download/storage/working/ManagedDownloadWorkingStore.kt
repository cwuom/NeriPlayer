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
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadAtomicFile
import java.io.File
import java.security.MessageDigest

internal object ManagedDownloadWorkingStore {
    private const val TAG = "ManagedDownloadStorage"
    private const val LEGACY_OPERATION_MANIFEST_NAME = "operation.json"
    private val legacyPreparedManifestName = Regex(
        "npdl_sidecar_manifest_[0-9a-f]{1,64}\\.json"
    )
    private val legacyPreparedSidecarName = Regex(
        "npdl_sidecar_[0-9a-f]{1,64}_[0-9a-fA-F-]{36}\\.[A-Za-z0-9._-]{1,24}"
    )
    private val legacyOperationDirectoryName = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )

    fun buildWorkingFileName(
        songKey: String,
        fileName: String,
        operationId: String? = null
    ): String {
        val normalizedKey = operationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::safeOperationId)
            ?: buildWorkingSongKeyHash(songKey)
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
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(songKey.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun createWorkingFile(
        cacheDir: File,
        songKey: String,
        fileName: String,
        operationId: String? = null
    ): File {
        val stagingDir = File(cacheDir, DOWNLOAD_STAGING_DIR_NAME).apply { mkdirs() }
        val operationDirectory = operationId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { File(stagingDir, safeOperationId(it)).apply { mkdirs() } }
            ?: stagingDir
        return File(
            operationDirectory,
            buildWorkingFileName(songKey, fileName, operationId)
        )
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
        song: SongItem,
        operationId: String? = null
    ) {
        val metadataFile = buildWorkingResumeMetadataFile(workingFile)
        runCatching {
            val existingFingerprint = readWorkingResumeFingerprintFile(metadataFile)
            val existingOperationId = readWorkingOperationIdFile(metadataFile)
            metadataFile.parentFile?.mkdirs()
            val metadataJson = ManagedDownloadStorageJsonCodec.workingResumeMetadataToJson(
                song = song,
                fingerprint = existingFingerprint,
                operationId = operationId ?: existingOperationId
            )
            val content = metadataJson.toString()
            assert(content.isNotBlank()) { "续传元数据序列化为空: ${workingFile.name}" }
            ManagedDownloadAtomicFile.writeTextAtomically(metadataFile, content)
        }.onFailure { error ->
            NPLogger.e(TAG, "写入下载恢复元数据失败: ${metadataFile.name}", error)
        }
    }

    fun readWorkingResumeFingerprint(
        workingFile: File
    ): ManagedDownloadStorage.WorkingResumeFingerprint? {
        return readWorkingResumeFingerprintFile(buildWorkingResumeMetadataFile(workingFile))
    }

    fun updateWorkingResumeFingerprint(
        workingFile: File,
        fingerprint: ManagedDownloadStorage.WorkingResumeFingerprint
    ) {
        val metadataFile = buildWorkingResumeMetadataFile(workingFile)
        runCatching {
            metadataFile.parentFile?.mkdirs()
            val metadataJson = ManagedDownloadStorageJsonCodec.mergeWorkingResumeFingerprint(
                rawJson = metadataFile.takeIf(File::isFile)?.readText(Charsets.UTF_8),
                fingerprint = fingerprint
            )
            val content = metadataJson.toString()
            assert(content.isNotBlank()) { "续传指纹序列化为空: ${workingFile.name}" }
            ManagedDownloadAtomicFile.writeTextAtomically(metadataFile, content)
        }.onFailure { error ->
            NPLogger.e(TAG, "写入下载恢复指纹失败: ${metadataFile.name}", error)
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
        workingFile.parentFile?.takeIf { parent ->
            parent.name != DOWNLOAD_STAGING_DIR_NAME &&
                parent.listFiles().orEmpty().isEmpty()
        }?.let { parent ->
            runCatching { parent.delete() }
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
        val keyHashes = keys.flatMapTo(linkedSetOf()) { key ->
            listOf(buildWorkingSongKeyHash(key), legacyWorkingSongKeyHash(key))
        }
        stagingDir.listFiles()
            .orEmpty()
            .filter { entry -> matchingWorkingArtifactSongKey(entry.name, keyHashes) != null }
            .forEach { entry ->
                val songKey = readWorkingSongKey(entry)
                    ?.takeIf { it in keys }
                    ?: return@forEach
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
        val metadataEntries = listResumeMetadataFiles(stagingDir)
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
                val workingFile = File(metadataFile.parentFile ?: stagingDir, workingFileName)
                val song = runCatching {
                    metadataFile.readText(Charsets.UTF_8)
                }.mapCatching(ManagedDownloadStorage::parseWorkingResumeMetadataSong)
                    .getOrNull()
                    ?: return@mapNotNull null
                val operationId = runCatching {
                    ManagedDownloadStorageJsonCodec.workingResumeOperationIdFromJson(
                        metadataFile.readText(Charsets.UTF_8)
                    )
                }.getOrNull()
                ManagedDownloadStorage.PendingResumableDownload(
                    song = song,
                    workingFile = workingFile,
                    operationId = operationId ?: metadataFile.parentFile
                        ?.takeIf { it != stagingDir }
                        ?.name
                )
            }
            .sortedBy { it.workingFile.lastModified() }
            .toList()
    }

    fun cleanupStagingFilesInDirectory(
        stagingDir: File,
        nowMs: Long = System.currentTimeMillis()
    ): ManagedDownloadStorage.StartupRecoveryResult {
        if (stagingDir.name != DOWNLOAD_STAGING_DIR_NAME) {
            return ManagedDownloadStorage.StartupRecoveryResult()
        }
        val stagingEntries = stagingDir.listFiles().orEmpty()
        if (stagingEntries.isEmpty()) {
            return ManagedDownloadStorage.StartupRecoveryResult()
        }

        var cleanedCount = 0
        var failedCount = 0
        var preservedCount = 0

        fun cleanupKnownFile(entry: File, allowOperationManifest: Boolean) {
            if (!entry.isFile) {
                return
            }
            if (isLegacyPreparedResidue(entry, allowOperationManifest)) {
                if (deleteWorkingArtifactEntry(entry)) {
                    cleanedCount++
                } else {
                    failedCount++
                }
                return
            }
            if (
                shouldPreserveWorkingFileForResume(entry, nowMs) ||
                shouldPreserveWorkingCheckpointForResume(entry, nowMs) ||
                shouldPreserveWorkingResumeMetadataForResume(entry, nowMs)
            ) {
                preservedCount++
                return
            }
            if (!isKnownManagedStagingFile(entry)) {
                preservedCount++
                return
            }
            val deleted = deleteWorkingArtifactEntry(entry)
            if (deleted) {
                cleanedCount++
            } else {
                failedCount++
            }
        }

        stagingEntries.forEach { entry ->
            if (entry.isFile) {
                cleanupKnownFile(entry, allowOperationManifest = false)
            }
        }
        stagingEntries
            .asSequence()
            .filter(File::isDirectory)
            .forEach { operationDirectory ->
                operationDirectory.listFiles()
                    .orEmpty()
                    .asSequence()
                    .filter(File::isFile)
                    .forEach { entry ->
                        cleanupKnownFile(
                            entry,
                            allowOperationManifest = legacyOperationDirectoryName.matches(
                                operationDirectory.name
                            )
                        )
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

    private fun isLegacyPreparedResidue(
        entry: File,
        allowOperationManifest: Boolean
    ): Boolean {
        return (allowOperationManifest && entry.name == LEGACY_OPERATION_MANIFEST_NAME) ||
            legacyPreparedManifestName.matches(entry.name) ||
            legacyPreparedSidecarName.matches(entry.name)
    }

    private fun isKnownManagedStagingFile(entry: File): Boolean {
        if (!entry.isFile) {
            return false
        }
        return entry.name.startsWith(DOWNLOAD_STAGING_FILE_PREFIX) && (
            entry.name.endsWith(DOWNLOAD_STAGING_FILE_SUFFIX) ||
                entry.name.endsWith(DOWNLOAD_STAGING_HLS_CHECKPOINT_SUFFIX) ||
                entry.name.endsWith(DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX)
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

    private fun readWorkingResumeFingerprintFile(
        metadataFile: File
    ): ManagedDownloadStorage.WorkingResumeFingerprint? {
        if (!metadataFile.isFile) {
            return null
        }
        return runCatching {
            ManagedDownloadStorageJsonCodec.workingResumeFingerprintFromJson(
                metadataFile.readText(Charsets.UTF_8)
            )
        }.getOrNull()
    }

    private fun readWorkingOperationIdFile(metadataFile: File): String? {
        if (!metadataFile.isFile) return null
        return runCatching {
            ManagedDownloadStorageJsonCodec.workingResumeOperationIdFromJson(
                metadataFile.readText(Charsets.UTF_8)
            )
        }.getOrNull()
    }

    private fun listResumeMetadataFiles(stagingDir: File): List<File> {
        return stagingDir.listFiles().orEmpty().flatMap { entry ->
            when {
                entry.isFile && entry.name.endsWith(DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX) -> {
                    listOf(entry)
                }
                entry.isDirectory -> entry.listFiles { _, name ->
                    name.endsWith(DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX)
                }?.toList().orEmpty()
                else -> emptyList()
            }
        }
    }

    private fun safeOperationId(operationId: String): String {
        return operationId
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(64)
            .ifBlank { "operation" }
    }

    private fun matchingWorkingArtifactSongKey(
        fileName: String,
        songKeyHashes: Set<String>
    ): String? {
        if (!fileName.startsWith(DOWNLOAD_STAGING_FILE_PREFIX)) {
            return null
        }
        val keyHash = fileName
            .removePrefix(DOWNLOAD_STAGING_FILE_PREFIX)
            .substringBefore('_', missingDelimiterValue = "")
            .takeIf(String::isNotBlank)
            ?: return null
        return keyHash.takeIf { it in songKeyHashes }
    }

    private fun readWorkingSongKey(entry: File): String? {
        val workingFile = when {
            entry.name.endsWith(DOWNLOAD_STAGING_HLS_CHECKPOINT_SUFFIX) ->
                File(entry.parentFile ?: return null, entry.name.removeSuffix(DOWNLOAD_STAGING_HLS_CHECKPOINT_SUFFIX))
            entry.name.endsWith(DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX) ->
                File(entry.parentFile ?: return null, entry.name.removeSuffix(DOWNLOAD_STAGING_RESUME_METADATA_SUFFIX))
            else -> entry
        }
        val metadata = buildWorkingResumeMetadataFile(workingFile)
        return runCatching {
            ManagedDownloadStorage.parseWorkingResumeMetadataSong(
                metadata.readText(Charsets.UTF_8)
            )?.stableKey()
        }.getOrNull()
    }

    private fun legacyWorkingSongKeyHash(songKey: String): String {
        return java.lang.Long.toHexString(songKey.hashCode().toLong() and 0xffffffffL)
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
