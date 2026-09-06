package moe.ouom.neriplayer.core.download.storage.backend

import android.net.Uri
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException

/** 受管存储的稳定引用，不把展示 URI 当作唯一身份 */
sealed interface StorageReference {
    data class FileRef(val logicalPath: String) : StorageReference
    data class SafRef(val uri: Uri) : StorageReference
}

/** 标记身份来自受管根目录 operation 的引用 */
class TrustedManagedRef internal constructor(
    val reference: StorageReference,
    val externalReference: String = reference.externalReference()
) {
    override fun equals(other: Any?): Boolean {
        return other is TrustedManagedRef &&
            reference == other.reference &&
            externalReference == other.externalReference
    }

    override fun hashCode(): Int {
        return 31 * reference.hashCode() + externalReference.hashCode()
    }

    override fun toString(): String {
        return "TrustedManagedRef(reference=$reference, externalReference=$externalReference)"
    }
}

internal fun StorageStat.asTrustedManagedRef(
    externalReference: String = reference.externalReference()
): TrustedManagedRef {
    return TrustedManagedRef(
        reference = reference,
        externalReference = externalReference
    )
}

private fun StorageReference.externalReference(): String {
    return when (this) {
        is StorageReference.FileRef -> logicalPath
        is StorageReference.SafRef -> uri.toString()
    }
}

sealed interface StorageTarget {
    val temporaryWriteOwnerName: String?

    data class FileTarget(
        val logicalPath: String,
        override val temporaryWriteOwnerName: String? = null
    ) : StorageTarget

    data class SafTarget(
        val parent: StorageReference.SafRef,
        val displayName: String,
        val mimeType: String,
        override val temporaryWriteOwnerName: String? = null
    ) : StorageTarget
}

data class StorageStat(
    val reference: StorageReference,
    val displayName: String,
    val sizeBytes: Long?,
    val lastModifiedMs: Long?,
    val isDirectory: Boolean
)

data class StorageDirectorySnapshot(
    val entries: List<StorageStat>,
    val confidence: StorageConfidence
)

sealed interface StorageConfidence {
    data object Complete : StorageConfidence
    data object Missing : StorageConfidence
    data object OutOfScope : StorageConfidence
    data object PermissionLost : StorageConfidence
    data class ProviderFailure(val error: Throwable) : StorageConfidence
}

/** 查询结果保留缺失、权限和 Provider 不确定性，避免调用方靠字符串猜测 */
sealed interface StorageLookupResult<out T> {
    data class Found<T>(val value: T) : StorageLookupResult<T>
    data object Missing : StorageLookupResult<Nothing>
    data object OutOfScope : StorageLookupResult<Nothing>
    data object PermissionLost : StorageLookupResult<Nothing>
    data class ProviderFailure(val error: Throwable) : StorageLookupResult<Nothing>
    data class Unsupported(val operation: String) : StorageLookupResult<Nothing>
}

sealed interface StorageWriteResult {
    data class Written(val stat: StorageStat) : StorageWriteResult
    data object Missing : StorageWriteResult
    data object OutOfScope : StorageWriteResult
    data object PermissionLost : StorageWriteResult
    data class ProviderFailure(val error: Throwable) : StorageWriteResult
    data class Unsupported(val operation: String) : StorageWriteResult
}

internal class StorageTargetChangedException(message: String) : IOException(message)

internal enum class SafWriteCommitMode {
    AtomicRename,
    DirectCreate,
    Unsupported
}

internal fun chooseSafWriteCommitMode(
    canRename: Boolean,
    targetExists: Boolean
): SafWriteCommitMode = when {
    canRename -> SafWriteCommitMode.AtomicRename
    targetExists -> SafWriteCommitMode.Unsupported
    else -> SafWriteCommitMode.DirectCreate
}

sealed interface StorageMutationResult {
    data object Deleted : StorageMutationResult
    data object Missing : StorageMutationResult
    data object OutOfScope : StorageMutationResult
    data object PermissionLost : StorageMutationResult
    data class ProviderFailure(val error: Throwable) : StorageMutationResult
    data class Unsupported(val operation: String) : StorageMutationResult
}

sealed interface StorageRenameResult {
    data class Renamed(val stat: StorageStat) : StorageRenameResult
    data object Missing : StorageRenameResult
    data object OutOfScope : StorageRenameResult
    data object PermissionLost : StorageRenameResult
    data class ProviderFailure(val error: Throwable) : StorageRenameResult
    data class Unsupported(val operation: String) : StorageRenameResult
}

data class StorageCapabilities(
    val canRead: Boolean,
    val canWrite: Boolean,
    val canCreate: Boolean,
    val canDelete: Boolean,
    val canRename: Boolean,
    val canMove: Boolean,
    val canCopy: Boolean,
    val hasReliableSize: Boolean,
    val hasReliableLastModified: Boolean
)

/** File 和 SAF 实现共用的可恢复存储边界 */
interface StorageBackend {
    suspend fun list(directory: StorageReference): StorageDirectorySnapshot

    suspend fun stat(reference: StorageReference): StorageLookupResult<StorageStat>

    suspend fun <T> read(
        reference: StorageReference,
        block: suspend (InputStream) -> T
    ): StorageLookupResult<T>

    suspend fun writeRecoverable(
        target: StorageTarget,
        writer: suspend (OutputStream) -> Unit
    ): StorageWriteResult

    suspend fun delete(reference: TrustedManagedRef): StorageMutationResult

    suspend fun rename(
        reference: TrustedManagedRef,
        displayName: String
    ): StorageRenameResult

    suspend fun capabilities(reference: StorageReference): StorageCapabilities
}

internal class StorageReadBlockFailure(
    val blockFailure: Throwable
) : RuntimeException(blockFailure)

internal class StorageReadLimitExceededException(
    val actualBytes: Long,
    val maxBytes: Long
) : IOException("stream exceeds limit: $actualBytes > $maxBytes")

/** 在不改变 StorageBackend 对外契约的前提下限制一次 source read 的字节数 */
internal suspend fun <T> StorageBackend.readBounded(
    reference: StorageReference,
    maxBytes: Long,
    block: suspend (InputStream) -> T
): StorageLookupResult<T> {
    require(maxBytes >= 0L) { "maxBytes must be non-negative" }
    return try {
        read(reference) { input ->
            try {
                block(BoundedInputStream(input, maxBytes))
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw StorageReadBlockFailure(error)
            }
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: StorageReadBlockFailure) {
        StorageLookupResult.ProviderFailure(error.blockFailure)
    }
}

private class BoundedInputStream(
    input: InputStream,
    private val maxBytes: Long
) : FilterInputStream(input) {
    private var consumedBytes = 0L

    override fun read(): Int {
        if (consumedBytes >= maxBytes) {
            return readPastLimitOrEof()
        }
        val value = super.read()
        if (value >= 0) consumedBytes++
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length) {
            "invalid read range"
        }
        if (consumedBytes >= maxBytes) {
            return readPastLimitOrEof()
        }
        val remaining = maxBytes - consumedBytes
        val boundedLength = minOf(length.toLong(), remaining).toInt()
        val count = super.read(buffer, offset, boundedLength)
        if (count > 0) consumedBytes += count.toLong()
        return count
    }

    private fun readPastLimitOrEof(): Int {
        // 读取一个额外字节来区分“恰好达到上限”和“超过上限”
        val extra = super.read()
        if (extra >= 0) {
            throw StorageReadLimitExceededException(actualBytes = maxBytes + 1L, maxBytes = maxBytes)
        }
        return extra
    }
}

/** 将业务读取异常包在结果中，同时保持协程取消语义 */
internal suspend fun <T> StorageBackend.readPreservingBlockFailure(
    reference: StorageReference,
    block: suspend (InputStream) -> T
): StorageLookupResult<Result<T>> {
    return try {
        read(reference) { input ->
            try {
                Result.success(block(input))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw StorageReadBlockFailure(error)
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: StorageReadBlockFailure) {
        error.suppressed.forEach(error.blockFailure::addSuppressed)
        StorageLookupResult.Found(Result.failure(error.blockFailure))
    }
}
