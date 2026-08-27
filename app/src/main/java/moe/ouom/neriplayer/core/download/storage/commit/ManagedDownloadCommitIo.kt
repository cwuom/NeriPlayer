package moe.ouom.neriplayer.core.download.storage.commit

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.MessageDigest
import moe.ouom.neriplayer.core.download.storage.backend.ManagedTemporaryWriteArtifacts
import moe.ouom.neriplayer.core.download.storage.backend.StorageTarget
import moe.ouom.neriplayer.core.download.storage.migration.ManagedDownloadMigrationException

internal object ManagedDownloadCommitIo {
    data class ReplacementFileWrite(
        val target: File,
        val backup: File?,
        val copiedBytes: Long
    )

    /**
     * Moves the old target to a deterministic backup before writing the new
     * bytes. The backup intentionally remains until the migration journal marks
     * the whole group committed.
     */
    fun copyFileReplacementAtomically(
        parent: File,
        targetName: String,
        backupName: String,
        input: InputStream,
        bufferSizeBytes: Int,
        onProgress: ((Long) -> Unit)? = null
    ): ReplacementFileWrite {
        val target = File(parent, targetName)
        val backup = File(parent, backupName)
        if (target.exists() && backup.exists()) {
            if (!target.isFile || !backup.isFile) {
                throw ManagedDownloadMigrationException.targetChanged(
                    "迁移替换目标或备份不是普通文件: $targetName"
                )
            }
            return ReplacementFileWrite(target = target, backup = backup, copiedBytes = -1L)
        }
        var backupCreated = false
        if (target.exists()) {
            if (!target.isFile || backup.exists()) {
                throw ManagedDownloadMigrationException.targetChanged(
                    "迁移替换目标状态已变化: $targetName"
                )
            }
            try {
                Files.move(target.toPath(), backup.toPath())
                backupCreated = true
            } catch (error: IOException) {
                throw ManagedDownloadMigrationException.transient(
                    "无法保留迁移替换目标备份: $targetName",
                    error
                )
            }
        }
        return try {
            val copiedBytes = copyFileAtomically(
                parent = parent,
                targetName = targetName,
                input = input,
                bufferSizeBytes = bufferSizeBytes,
                onProgress = onProgress
            )
            ReplacementFileWrite(
                target = target,
                backup = backup.takeIf { backupCreated || it.exists() },
                copiedBytes = copiedBytes
            )
        } catch (error: Throwable) {
            if (backupCreated && !target.exists() && backup.exists()) {
                runCatching { Files.move(backup.toPath(), target.toPath()) }
            }
            throw error
        }
    }

    fun copyFileAtomically(
        parent: File,
        targetName: String,
        input: InputStream,
        bufferSizeBytes: Int,
        onProgress: ((Long) -> Unit)? = null,
        outputDigest: MessageDigest? = null
    ): Long {
        val target = File(parent, targetName)
        if (target.exists()) {
            throw ManagedDownloadMigrationException.targetChanged(
                "迁移目标在提交前已出现: $targetName"
            )
        }
        val storageTarget = StorageTarget.FileTarget(target.absolutePath)
        val temporaryLease = ManagedTemporaryWriteArtifacts.acquire(
            target = storageTarget,
            nonce = MIGRATION_TEMPORARY_WRITE_NONCE
        ) ?: throw ManagedDownloadMigrationException.targetChanged(
            "迁移目标正在被其他任务提交: $targetName"
        )
        val partial = File(parent, temporaryLease.displayName)
        var committed = false
        var failure: Throwable? = null
        try {
            deleteOwnedStalePartialOrThrow(partial)
            try {
                Files.createFile(partial.toPath())
            } catch (error: FileAlreadyExistsException) {
                throw ManagedDownloadMigrationException.targetChanged(
                    "迁移临时目标正在被其他任务使用: $targetName",
                    error
                )
            }
            val copiedBytes = FileOutputStream(partial).use { output ->
                val copied = copyStreamWithProgress(
                    input = input,
                    output = output,
                    bufferSizeBytes = bufferSizeBytes,
                    onProgress = onProgress,
                    outputDigest = outputDigest
                )
                output.fd.sync()
                copied
            }
            try {
                Files.move(partial.toPath(), target.toPath())
            } catch (error: FileAlreadyExistsException) {
                throw ManagedDownloadMigrationException.targetChanged(
                    "迁移目标在最终提交时已出现: $targetName",
                    error
                )
            } catch (error: IOException) {
                if (target.exists()) {
                    throw ManagedDownloadMigrationException.targetChanged(
                        "迁移目标在最终提交时发生变化: $targetName",
                        error
                    )
                }
                throw error
            }
            committed = true
            return copiedBytes
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanupError = if (committed) null else cleanupOwnedPartial(partial)
            temporaryLease.close()
            cleanupError?.let { error ->
                failure?.addSuppressed(error) ?: throw error
            }
        }
    }

    private fun deleteOwnedStalePartialOrThrow(partial: File) {
        if (!partial.exists()) return
        cleanupOwnedPartial(partial)?.let { error -> throw error }
    }

    private fun cleanupOwnedPartial(partial: File): IOException? {
        return when {
            !partial.exists() -> null
            !partial.isFile -> IOException("迁移临时目标不是普通文件: ${partial.name}")
            !partial.delete() && partial.exists() -> IOException(
                "无法删除迁移临时文件: ${partial.name}"
            )
            partial.exists() -> IOException("迁移临时文件删除后仍存在: ${partial.name}")
            else -> null
        }
    }

    fun copyStreamWithProgress(
        input: InputStream,
        output: OutputStream,
        bufferSizeBytes: Int,
        onProgress: ((Long) -> Unit)? = null,
        outputDigest: MessageDigest? = null
    ): Long {
        if (onProgress == null && outputDigest == null) {
            return input.copyTo(output, bufferSizeBytes)
        }
        val buffer = ByteArray(bufferSizeBytes)
        var copiedBytes = 0L
        while (true) {
            val readCount = input.read(buffer)
            if (readCount < 0) {
                break
            }
            if (readCount == 0) {
                continue
            }
            output.write(buffer, 0, readCount)
            outputDigest?.update(buffer, 0, readCount)
            copiedBytes += readCount
            onProgress?.invoke(copiedBytes)
        }
        return copiedBytes
    }

    fun digestHex(digest: MessageDigest): String {
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    fun requireVerifiedCommittedByteCount(
        expectedSizeBytes: Long,
        reportedSizeBytes: Long?,
        countedSizeBytes: Long?,
        toleranceBytes: Long = 0L,
        description: String
    ): Long {
        return ManagedDownloadCommitVerifier.verifiedCommittedByteCount(
            expectedSizeBytes = expectedSizeBytes,
            reportedSizeBytes = reportedSizeBytes,
            countedSizeBytes = countedSizeBytes,
            toleranceBytes = toleranceBytes
        ) ?: throw IOException(
            "提交后的目标大小不匹配: $description, expected=${expectedSizeBytes.coerceAtLeast(0L)}, " +
                "reported=${reportedSizeBytes ?: "unavailable"}, counted=${countedSizeBytes ?: "unavailable"}"
        )
    }

    fun verifyFileCommittedLength(
        target: File,
        expectedSizeBytes: Long,
        description: String
    ): Long {
        val reportedSize = target.takeIf { it.exists() && it.isFile }?.length()
        return requireVerifiedCommittedByteCount(
            expectedSizeBytes = expectedSizeBytes,
            reportedSizeBytes = reportedSize,
            countedSizeBytes = null,
            description = description
        )
    }

    fun verifyDocumentCommittedLength(
        contentResolver: ContentResolver,
        uri: Uri,
        expectedSizeBytes: Long,
        toleranceBytes: Long,
        bufferSizeBytes: Int,
        description: String,
        onQueryFailure: (Throwable) -> Unit,
        onCountFailure: (Throwable) -> Unit
    ): Long {
        val expectedSize = expectedSizeBytes.coerceAtLeast(0L)
        val reportedSize = queryDocumentSizeBytes(
            contentResolver = contentResolver,
            uri = uri,
            onFailure = onQueryFailure
        )
        val countedSize = when {
            reportedSize == null -> countDocumentBytes(
                contentResolver = contentResolver,
                uri = uri,
                bufferSizeBytes = bufferSizeBytes,
                onFailure = onCountFailure
            )

            ManagedDownloadCommitVerifier.isSizeWithinTolerance(reportedSize, expectedSize, toleranceBytes) -> null
            else -> countDocumentBytes(
                contentResolver = contentResolver,
                uri = uri,
                bufferSizeBytes = bufferSizeBytes,
                onFailure = onCountFailure
            )
        }
        return requireVerifiedCommittedByteCount(
            expectedSizeBytes = expectedSize,
            reportedSizeBytes = reportedSize,
            countedSizeBytes = countedSize,
            toleranceBytes = toleranceBytes,
            description = description
        )
    }

    private fun queryDocumentSizeBytes(
        contentResolver: ContentResolver,
        uri: Uri,
        onFailure: (Throwable) -> Unit
    ): Long? {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (sizeIndex < 0 || !cursor.moveToFirst() || cursor.isNull(sizeIndex)) {
                    null
                } else {
                    cursor.getLong(sizeIndex).takeIf { it >= 0L }
                }
            }
        }.onFailure(onFailure).getOrNull()
    }

    private fun countDocumentBytes(
        contentResolver: ContentResolver,
        uri: Uri,
        bufferSizeBytes: Int,
        onFailure: (Throwable) -> Unit
    ): Long? {
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                countInputStreamBytes(input, bufferSizeBytes)
            }
        }.onFailure(onFailure).getOrNull()
    }

    internal fun countInputStreamBytes(input: InputStream, bufferSizeBytes: Int): Long {
        val buffer = ByteArray(bufferSizeBytes)
        var countedBytes = 0L
        while (true) {
            val readCount = input.read(buffer)
            if (readCount < 0) {
                return countedBytes
            }
            if (readCount == 0) {
                continue
            }
            countedBytes += readCount
        }
    }

    internal const val MIGRATION_TEMPORARY_WRITE_NONCE = "0000000000000000"
}
