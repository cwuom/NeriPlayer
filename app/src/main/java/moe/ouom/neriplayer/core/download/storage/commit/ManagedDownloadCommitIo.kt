package moe.ouom.neriplayer.core.download.storage.commit

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal object ManagedDownloadCommitIo {
    fun copyStreamWithProgress(
        input: InputStream,
        output: OutputStream,
        bufferSizeBytes: Int,
        onProgress: ((Long) -> Unit)? = null
    ): Long {
        if (onProgress == null) {
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
            copiedBytes += readCount
            onProgress(copiedBytes)
        }
        return copiedBytes
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

    private fun countInputStreamBytes(input: InputStream, bufferSizeBytes: Int): Long {
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
}
