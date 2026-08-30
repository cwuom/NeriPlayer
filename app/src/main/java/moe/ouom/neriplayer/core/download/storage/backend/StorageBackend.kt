package moe.ouom.neriplayer.core.download.storage.backend

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.database.Cursor
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.WeakHashMap
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.download.storage.SAF_PARENT_DOCUMENT_CACHE_VALIDATE_INTERVAL_MS
import moe.ouom.neriplayer.core.download.storage.STREAM_COPY_BUFFER_SIZE_BYTES
import moe.ouom.neriplayer.core.download.storage.reference.ManagedDownloadReferenceIo
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeMutationLocks

sealed interface StorageReference {
    data class FileRef(val logicalPath: String) : StorageReference
    data class SafRef(val uri: Uri) : StorageReference
}

/** marks a reference whose identity came from a managed root operation */
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

private class StorageReadBlockFailure(
    val blockFailure: Throwable
) : RuntimeException(blockFailure)

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

internal class FileStorageBackend(
    private val root: File
) : StorageBackend {
    override suspend fun list(directory: StorageReference): StorageDirectorySnapshot = withContext(Dispatchers.IO) {
        val resolved = resolve(directory) ?: return@withContext StorageDirectorySnapshot(
            entries = emptyList(),
            confidence = if (directory is StorageReference.FileRef) {
                StorageConfidence.OutOfScope
            } else {
                StorageConfidence.ProviderFailure(
                    IllegalArgumentException("file reference required")
                )
            }
        )
        if (!resolved.exists()) {
            return@withContext StorageDirectorySnapshot(emptyList(), StorageConfidence.Missing)
        }
        if (!resolved.isDirectory) {
            return@withContext StorageDirectorySnapshot(emptyList(), StorageConfidence.Complete)
        }
        val children = try {
            resolved.listFiles()
        } catch (error: Throwable) {
            return@withContext StorageDirectorySnapshot(
                entries = emptyList(),
                confidence = StorageConfidence.ProviderFailure(error)
            )
        } ?: return@withContext StorageDirectorySnapshot(
            entries = emptyList(),
            confidence = StorageConfidence.ProviderFailure(
                IllegalStateException("file list failed")
            )
        )
        StorageDirectorySnapshot(
            entries = children.map(::toStat),
            confidence = StorageConfidence.Complete
        )
    }

    override suspend fun stat(reference: StorageReference): StorageLookupResult<StorageStat> = withContext(Dispatchers.IO) {
        val fileReference = reference as? StorageReference.FileRef
            ?: return@withContext StorageLookupResult.Unsupported("file reference required")
        val file = resolve(fileReference)
            ?: return@withContext StorageLookupResult.OutOfScope
        if (!file.exists()) StorageLookupResult.Missing
        else StorageLookupResult.Found(toStat(file))
    }

    override suspend fun <T> read(
        reference: StorageReference,
        block: suspend (InputStream) -> T
    ): StorageLookupResult<T> = withContext(Dispatchers.IO) {
        val fileReference = reference as? StorageReference.FileRef
            ?: return@withContext StorageLookupResult.Unsupported("file reference required")
        val file = resolve(fileReference)
            ?: return@withContext StorageLookupResult.OutOfScope
        if (!file.isFile) return@withContext StorageLookupResult.Missing
        return@withContext try {
            val value = file.inputStream().use { input ->
                block(input)
            }
            StorageLookupResult.Found(value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: StorageReadBlockFailure) {
            throw error
        } catch (error: Throwable) {
            StorageLookupResult.ProviderFailure(error)
        }
    }

    override suspend fun writeRecoverable(
        target: StorageTarget,
        writer: suspend (OutputStream) -> Unit
    ): StorageWriteResult = withContext(Dispatchers.IO) {
        val fileTarget = target as? StorageTarget.FileTarget
            ?: return@withContext StorageWriteResult.Unsupported("file backend target")
        val targetFile = resolve(StorageReference.FileRef(fileTarget.logicalPath))
            ?: return@withContext StorageWriteResult.OutOfScope
        if (targetFile.isDirectory) {
            return@withContext StorageWriteResult.Unsupported("file target required")
        }
        val parent = targetFile.parentFile
            ?: return@withContext StorageWriteResult.ProviderFailure(
                IllegalStateException("file target parent required")
            )
        return@withContext FileStorageMutationLocks.withTargetLock(targetFile) {
            var temporary: File? = null
            var temporaryLease: ManagedTemporaryWriteLease? = null
            try {
                parent.mkdirs()
                val managedTemporary = ManagedTemporaryWriteArtifacts.createFile(
                    target = fileTarget,
                    parent = parent
                )
                val temporaryFile = managedTemporary.file
                temporary = temporaryFile
                temporaryLease = managedTemporary.lease
                val output = temporaryFile.outputStream()
                try {
                    writer(output)
                } finally {
                    output.close()
                }
                replaceFileAtomically(temporaryFile, targetFile)
                StorageWriteResult.Written(toStat(targetFile))
            } catch (error: CancellationException) {
                temporary?.let { file -> cleanupTemporaryFileWriteCancellation(file, error) }
                throw error
            } catch (error: Throwable) {
                temporary?.let { file -> cleanupTemporaryFileWriteFailure(file, error) }
                    ?: StorageWriteResult.ProviderFailure(error)
            } finally {
                temporaryLease?.close()
            }
        }
    }

    override suspend fun delete(reference: TrustedManagedRef): StorageMutationResult = withContext(Dispatchers.IO) {
        val fileReference = reference.reference as? StorageReference.FileRef
            ?: return@withContext StorageMutationResult.Unsupported("file reference required")
        val file = resolve(fileReference)
            ?: return@withContext StorageMutationResult.OutOfScope
        if (!file.exists()) StorageMutationResult.Missing
        else if (file.deleteRecursively()) StorageMutationResult.Deleted
        else StorageMutationResult.ProviderFailure(IllegalStateException("delete failed"))
    }

    override suspend fun rename(
        reference: TrustedManagedRef,
        displayName: String
    ): StorageRenameResult = withContext(Dispatchers.IO) {
        val fileReference = reference.reference as? StorageReference.FileRef
            ?: return@withContext StorageRenameResult.Unsupported("file reference required")
        if (displayName.isBlank() || displayName == "." || displayName == "..") {
            return@withContext StorageRenameResult.Unsupported("valid file name required")
        }
        val source = resolve(fileReference)
            ?: return@withContext StorageRenameResult.OutOfScope
        if (!source.exists()) return@withContext StorageRenameResult.Missing
        val target = File(source.parentFile, displayName)
        return@withContext try {
            if (target.exists() && target.canonicalFile != source.canonicalFile) {
                return@withContext StorageRenameResult.ProviderFailure(
                    IllegalStateException("rename target already exists")
                )
            }
            if (!source.renameTo(target)) {
                StorageRenameResult.ProviderFailure(
                    IllegalStateException("file rename was not confirmed")
                )
            } else {
                StorageRenameResult.Renamed(toStat(target))
            }
        } catch (_: SecurityException) {
            StorageRenameResult.PermissionLost
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            StorageRenameResult.ProviderFailure(error)
        }
    }

    override suspend fun capabilities(reference: StorageReference): StorageCapabilities =
        if (reference is StorageReference.FileRef && resolve(reference) != null) {
            StorageCapabilities(
                canRead = true,
                canWrite = true,
                canCreate = true,
                canDelete = true,
                canRename = true,
                canMove = true,
                canCopy = true,
                hasReliableSize = true,
                hasReliableLastModified = true
            )
        } else {
            emptyStorageCapabilities()
        }

    private fun resolve(reference: StorageReference): File? {
        val fileReference = reference as? StorageReference.FileRef ?: return null
        val candidate = File(root, fileReference.logicalPath)
        val basePath = runCatching { root.canonicalPath }.getOrNull() ?: return null
        val candidatePath = runCatching { candidate.canonicalPath }.getOrNull() ?: return null
        return candidate.takeIf {
            candidatePath == basePath || candidatePath.startsWith("$basePath${File.separator}")
        }
    }

    private fun toStat(file: File): StorageStat = StorageStat(
        reference = StorageReference.FileRef(file.relativeTo(root).path),
        displayName = file.name,
        sizeBytes = file.length().takeIf { file.isFile },
        lastModifiedMs = file.lastModified().takeIf { it > 0L },
        isDirectory = file.isDirectory
    )

    private fun cleanupTemporaryFileWriteFailure(
        temporary: File,
        initialError: Throwable
    ): StorageWriteResult {
        val cleanupError = deleteTemporaryFileAndConfirm(temporary)
            ?: return StorageWriteResult.ProviderFailure(initialError)
        val combinedError = IllegalStateException(
            "文件临时写入文件未能确认删除: ${temporary.name}",
            cleanupError
        )
        combinedError.addSuppressed(initialError)
        return StorageWriteResult.ProviderFailure(combinedError)
    }

    private fun cleanupTemporaryFileWriteCancellation(
        temporary: File,
        cancellation: CancellationException
    ) {
        deleteTemporaryFileAndConfirm(temporary)?.let(cancellation::addSuppressed)
    }

    private fun deleteTemporaryFileAndConfirm(temporary: File): Throwable? {
        return try {
            when {
                !temporary.exists() -> null
                !temporary.isFile -> IllegalStateException(
                    "临时写入目标不是普通文件: ${temporary.name}"
                )
                !temporary.delete() && temporary.exists() -> IllegalStateException(
                    "临时写入文件删除未确认: ${temporary.name}"
                )
                temporary.exists() -> IllegalStateException(
                    "临时写入文件删除后仍存在: ${temporary.name}"
                )
                else -> null
            }
        } catch (error: Throwable) {
            error
        }
    }
}

internal object FileStorageMutationLocks {
    private const val STRIPE_COUNT = 64
    private val locks = Array(STRIPE_COUNT) { Mutex() }

    fun forTarget(target: File): Mutex {
        val key = runCatching { target.canonicalPath }
            .getOrElse { target.absolutePath }
        return locks[Math.floorMod(key.hashCode(), STRIPE_COUNT)]
    }

    suspend fun <T> withTargetLock(
        target: File,
        block: suspend () -> T
    ): T {
        return forTarget(target).withLock { block() }
    }

}

private fun replaceFileAtomically(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
    }
}

internal class SafStorageBackend(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parentDocumentCache: SafParentDocumentCache<SafQueryResult> =
        sharedParentDocumentCache(context)
) : StorageBackend {
    companion object {
        private val sharedParentDocumentCaches = WeakHashMap<Context, SafParentDocumentCache<SafQueryResult>>()

        private fun sharedParentDocumentCache(context: Context): SafParentDocumentCache<SafQueryResult> {
            val cacheOwner = context.applicationContext ?: context
            return synchronized(sharedParentDocumentCaches) {
                sharedParentDocumentCaches.getOrPut(cacheOwner) {
                    SafParentDocumentCache()
                }
            }
        }
    }

    override suspend fun list(directory: StorageReference): StorageDirectorySnapshot = withContext(ioDispatcher) {
        val safReference = directory as? StorageReference.SafRef
            ?: return@withContext StorageDirectorySnapshot(
                emptyList(),
                StorageConfidence.ProviderFailure(IllegalArgumentException("SAF reference required"))
            )
        when (val parent = queryDocument(safReference.uri)) {
            SafQueryResult.Missing -> StorageDirectorySnapshot(emptyList(), StorageConfidence.Missing)
            SafQueryResult.OutOfScope -> StorageDirectorySnapshot(
                emptyList(),
                StorageConfidence.OutOfScope
            )
            SafQueryResult.PermissionLost -> StorageDirectorySnapshot(
                emptyList(),
                StorageConfidence.PermissionLost
            )
            is SafQueryResult.ProviderFailure -> StorageDirectorySnapshot(
                emptyList(),
                StorageConfidence.ProviderFailure(parent.error)
            )
            is SafQueryResult.Found -> {
                if (!parent.document.isDirectory) {
                    return@withContext StorageDirectorySnapshot(emptyList(), StorageConfidence.Complete)
                }
                when (val children = queryChildren(safReference.uri)) {
                    is SafChildrenResult.Found -> StorageDirectorySnapshot(
                        entries = children.entries,
                        confidence = StorageConfidence.Complete
                    )
                    SafChildrenResult.Missing -> StorageDirectorySnapshot(
                        emptyList(),
                        StorageConfidence.Missing
                    )
                    SafChildrenResult.OutOfScope -> StorageDirectorySnapshot(
                        emptyList(),
                        StorageConfidence.OutOfScope
                    )
                    SafChildrenResult.PermissionLost -> StorageDirectorySnapshot(
                        emptyList(),
                        StorageConfidence.PermissionLost
                    )
                    is SafChildrenResult.ProviderFailure -> StorageDirectorySnapshot(
                        emptyList(),
                        StorageConfidence.ProviderFailure(children.error)
                    )
                }
            }
        }
    }

    override suspend fun stat(reference: StorageReference): StorageLookupResult<StorageStat> = withContext(ioDispatcher) {
        val safReference = reference as? StorageReference.SafRef
            ?: return@withContext StorageLookupResult.Unsupported("SAF reference required")
        when (val result = queryDocument(safReference.uri)) {
            SafQueryResult.Missing -> StorageLookupResult.Missing
            SafQueryResult.OutOfScope -> StorageLookupResult.OutOfScope
            SafQueryResult.PermissionLost -> StorageLookupResult.PermissionLost
            is SafQueryResult.ProviderFailure -> StorageLookupResult.ProviderFailure(result.error)
            is SafQueryResult.Found -> StorageLookupResult.Found(result.document.toStat(safReference.uri))
        }
    }

    override suspend fun <T> read(
        reference: StorageReference,
        block: suspend (InputStream) -> T
    ): StorageLookupResult<T> = withContext(ioDispatcher) {
        val safReference = reference as? StorageReference.SafRef
            ?: return@withContext StorageLookupResult.Unsupported("SAF reference required")
        val input = try {
            context.contentResolver.openInputStream(safReference.uri)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            return@withContext StorageLookupResult.PermissionLost
        } catch (error: FileNotFoundException) {
            return@withContext when (classifySafFileNotFound(error)) {
                SafFileFailure.Missing -> StorageLookupResult.Missing
                SafFileFailure.OutOfScope -> StorageLookupResult.OutOfScope
                SafFileFailure.PermissionLost -> StorageLookupResult.PermissionLost
                SafFileFailure.ProviderFailure -> StorageLookupResult.ProviderFailure(error)
            }
        } catch (error: Throwable) {
            return@withContext when (classifySafFailure(error)) {
                SafFileFailure.Missing -> StorageLookupResult.Missing
                SafFileFailure.OutOfScope -> StorageLookupResult.OutOfScope
                SafFileFailure.PermissionLost -> StorageLookupResult.PermissionLost
                SafFileFailure.ProviderFailure -> StorageLookupResult.ProviderFailure(error)
            }
        } ?: run {
            // 有些 Provider 会返回空流而不是抛出 Missing，保留一次探测来分类这种异常
            return@withContext when (val result = queryDocument(safReference.uri)) {
                SafQueryResult.Missing -> StorageLookupResult.Missing
                SafQueryResult.OutOfScope -> StorageLookupResult.OutOfScope
                SafQueryResult.PermissionLost -> StorageLookupResult.PermissionLost
                is SafQueryResult.ProviderFailure -> StorageLookupResult.ProviderFailure(result.error)
                is SafQueryResult.Found -> StorageLookupResult.ProviderFailure(
                    IllegalStateException("provider returned null input stream")
                )
            }
        }
        return@withContext try {
            val value = input.use { stream -> block(stream) }
            StorageLookupResult.Found(value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: StorageReadBlockFailure) {
            throw error
        } catch (error: Throwable) {
            when (classifySafFailure(error)) {
                SafFileFailure.Missing -> StorageLookupResult.Missing
                SafFileFailure.OutOfScope -> StorageLookupResult.OutOfScope
                SafFileFailure.PermissionLost -> StorageLookupResult.PermissionLost
                SafFileFailure.ProviderFailure -> StorageLookupResult.ProviderFailure(error)
            }
        }
    }

    override suspend fun writeRecoverable(
        target: StorageTarget,
        writer: suspend (OutputStream) -> Unit
    ): StorageWriteResult {
        val safTarget = target as? StorageTarget.SafTarget
            ?: return StorageWriteResult.Unsupported("SAF target required")
        return writeSafRecoverable(
            safTarget = safTarget,
            expectedAbsent = false,
            writer = writer
        )
    }

    internal suspend fun writeCreateOnlyRecoverable(
        target: StorageTarget.SafTarget,
        writer: suspend (OutputStream) -> Unit
    ): StorageWriteResult {
        return writeSafRecoverable(
            safTarget = target,
            expectedAbsent = true,
            writer = writer
        )
    }

    internal suspend fun replaceKnownRecoverable(
        target: StorageTarget.SafTarget,
        existingReference: StorageReference.SafRef,
        writer: suspend (OutputStream) -> Unit
    ): StorageWriteResult {
        return writeSafRecoverable(
            safTarget = target,
            expectedAbsent = false,
            knownExistingReference = existingReference,
            writer = writer
        )
    }

    private suspend fun writeSafRecoverable(
        safTarget: StorageTarget.SafTarget,
        expectedAbsent: Boolean,
        knownExistingReference: StorageReference.SafRef? = null,
        writer: suspend (OutputStream) -> Unit
    ): StorageWriteResult = withContext(ioDispatcher) {
        if (safTarget.displayName.isBlank() || safTarget.displayName == "." || safTarget.displayName == "..") {
            return@withContext StorageWriteResult.Unsupported("valid SAF display name required")
        }
        val parentUri = safTarget.parent.uri
        val parent = when (val result = queryParentDocument(parentUri)) {
            SafQueryResult.Missing -> return@withContext StorageWriteResult.Missing
            SafQueryResult.OutOfScope -> return@withContext StorageWriteResult.OutOfScope
            SafQueryResult.PermissionLost -> return@withContext StorageWriteResult.PermissionLost
            is SafQueryResult.ProviderFailure -> {
                return@withContext StorageWriteResult.ProviderFailure(result.error)
            }
            is SafQueryResult.Found -> result.document
        }
        if (!parent.isDirectory) {
            return@withContext StorageWriteResult.Unsupported("SAF parent directory required")
        }
        if (parent.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong() == 0L) {
            return@withContext StorageWriteResult.Unsupported("provider create unsupported")
        }
        val temporaryLease = ManagedTemporaryWriteArtifacts.acquire(safTarget)
        val temporaryName = temporaryLease.displayName
        try {
        val temporaryUri = try {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                safTarget.mimeType,
                temporaryName
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            invalidateParentDocument(parentUri)
            return@withContext StorageWriteResult.PermissionLost
        } catch (error: UnsupportedOperationException) {
            invalidateParentDocument(parentUri)
            return@withContext StorageWriteResult.Unsupported("provider create")
        } catch (error: Throwable) {
            invalidateParentDocument(parentUri)
            return@withContext StorageWriteResult.ProviderFailure(error)
        } ?: run {
            invalidateParentDocument(parentUri)
            return@withContext StorageWriteResult.ProviderFailure(
                IllegalStateException("provider refused temporary file creation")
            )
        }

        val output = try {
            context.contentResolver.openOutputStream(temporaryUri, "w")
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            return@withContext cleanupTemporarySafWriteFailure(
                temporaryUri,
                StorageWriteResult.PermissionLost
            )
        } catch (error: FileNotFoundException) {
            return@withContext cleanupTemporarySafWriteFailure(
                temporaryUri,
                when (classifySafFileNotFound(error)) {
                    SafFileFailure.Missing -> StorageWriteResult.Missing
                    SafFileFailure.OutOfScope -> StorageWriteResult.OutOfScope
                    SafFileFailure.PermissionLost -> StorageWriteResult.PermissionLost
                    SafFileFailure.ProviderFailure -> StorageWriteResult.ProviderFailure(error)
                }
            )
        } catch (error: Throwable) {
            return@withContext cleanupTemporarySafWriteFailure(
                temporaryUri,
                StorageWriteResult.ProviderFailure(error)
            )
        } ?: run {
            return@withContext cleanupTemporarySafWriteFailure(
                temporaryUri,
                StorageWriteResult.ProviderFailure(
                    IllegalStateException("provider returned null output stream")
                )
            )
        }
        try {
            output.use { stream -> writer(stream) }
        } catch (error: CancellationException) {
            cleanupTemporarySafWriteCancellation(temporaryUri, error)
            throw error
        } catch (error: Throwable) {
            return@withContext cleanupTemporarySafWriteFailure(
                temporaryUri,
                StorageWriteResult.ProviderFailure(error)
            )
        }

        return@withContext ManagedDownloadTreeMutationLocks.withLock(parentUri) commit@{
        val temporaryStat = when (val result = queryDocument(temporaryUri)) {
            SafQueryResult.Missing -> {
                return@commit cleanupTemporarySafWriteFailure(
                    temporaryUri,
                    StorageWriteResult.ProviderFailure(
                        IllegalStateException("temporary file disappeared after write")
                    )
                )
            }
            SafQueryResult.OutOfScope -> {
                return@commit cleanupTemporarySafWriteFailure(
                    temporaryUri,
                    StorageWriteResult.OutOfScope
                )
            }
            SafQueryResult.PermissionLost -> {
                return@commit cleanupTemporarySafWriteFailure(
                    temporaryUri,
                    StorageWriteResult.PermissionLost
                )
            }
            is SafQueryResult.ProviderFailure -> {
                return@commit cleanupTemporarySafWriteFailure(
                    temporaryUri,
                    StorageWriteResult.ProviderFailure(result.error)
                )
            }
            is SafQueryResult.Found -> result.document
        }
        if (temporaryStat.isDirectory) {
            return@commit cleanupTemporarySafWriteFailure(
                temporaryUri,
                StorageWriteResult.ProviderFailure(
                    IllegalStateException("provider created a directory for a file write")
                )
            )
        }

        if (!expectedAbsent && knownExistingReference == null) {
            reconcileSafBackupBeforeWrite(parentUri, safTarget.displayName)?.let { result ->
                return@commit cleanupTemporarySafWriteFailure(temporaryUri, result)
            }
        }

        val existingTarget = if (expectedAbsent) {
            null
        } else if (knownExistingReference != null) {
            when (val existing = queryDocument(knownExistingReference.uri)) {
                SafQueryResult.Missing -> {
                    return@commit cleanupTemporarySafWriteFailure(
                        temporaryUri,
                        StorageWriteResult.ProviderFailure(
                            StorageTargetChangedException(
                                "known SAF target disappeared: ${safTarget.displayName}"
                            )
                        )
                    )
                }
                SafQueryResult.OutOfScope -> {
                    return@commit cleanupTemporarySafWriteFailure(
                        temporaryUri,
                        StorageWriteResult.OutOfScope
                    )
                }
                SafQueryResult.PermissionLost -> {
                    return@commit cleanupTemporarySafWriteFailure(
                        temporaryUri,
                        StorageWriteResult.PermissionLost
                    )
                }
                is SafQueryResult.ProviderFailure -> {
                    return@commit cleanupTemporarySafWriteFailure(
                        temporaryUri,
                        StorageWriteResult.ProviderFailure(existing.error)
                    )
                }
                is SafQueryResult.Found -> {
                    if (existing.document.displayName != safTarget.displayName) {
                        return@commit cleanupTemporarySafWriteFailure(
                            temporaryUri,
                            StorageWriteResult.ProviderFailure(
                                StorageTargetChangedException(
                                    "known SAF target changed name: " +
                                        "expected=${safTarget.displayName} " +
                                        "actual=${existing.document.displayName}"
                                )
                            )
                        )
                    }
                    existing.document.toStat(knownExistingReference.uri)
                }
            }
        } else when (val children = queryChildren(parentUri)) {
            is SafChildrenResult.Found -> children.entries.firstOrNull {
                it.displayName == safTarget.displayName
            }
            SafChildrenResult.Missing -> {
                return@commit cleanupTemporarySafWriteFailure(
                    temporaryUri,
                    StorageWriteResult.ProviderFailure(
                        IllegalStateException("provider lost parent children during write")
                    )
                )
            }
            SafChildrenResult.OutOfScope -> {
                return@commit cleanupTemporarySafWriteFailure(
                    temporaryUri,
                    StorageWriteResult.OutOfScope
                )
            }
            SafChildrenResult.PermissionLost -> {
                return@commit cleanupTemporarySafWriteFailure(
                    temporaryUri,
                    StorageWriteResult.PermissionLost
                )
            }
            is SafChildrenResult.ProviderFailure -> {
                return@commit cleanupTemporarySafWriteFailure(
                    temporaryUri,
                    StorageWriteResult.ProviderFailure(children.error)
                )
            }
        }
        if (existingTarget?.isDirectory == true) {
            return@commit cleanupTemporarySafWriteFailure(
                temporaryUri,
                StorageWriteResult.Unsupported("target directory exists")
            )
        }
        val canRename = temporaryStat.flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME.toLong() != 0L
        if (expectedAbsent && canRename) {
            return@commit commitSafCreateOnlyByRename(
                temporaryUri = temporaryUri,
                displayName = safTarget.displayName
            )
        }
        when (chooseSafWriteCommitMode(canRename, existingTarget != null)) {
            SafWriteCommitMode.Unsupported -> {
                return@commit cleanupTemporarySafWriteFailure(
                    temporaryUri,
                    StorageWriteResult.Unsupported("provider rename")
                )
            }
            SafWriteCommitMode.DirectCreate -> {
                var directTargetUri: Uri? = null
                try {
                    val finalUri = when (val createResult = createDirectSafTarget(
                        parentUri = parentUri,
                        mimeType = safTarget.mimeType,
                        displayName = safTarget.displayName
                    )) {
                        is DirectSafCreateResult.Created -> createResult.uri
                        DirectSafCreateResult.Unsupported -> {
                            return@commit cleanupTemporarySafWriteFailure(
                                temporaryUri,
                                StorageWriteResult.Unsupported("provider direct create")
                            )
                        }
                        DirectSafCreateResult.PermissionLost -> {
                            return@commit cleanupTemporarySafWriteFailure(
                                temporaryUri,
                                StorageWriteResult.PermissionLost
                            )
                        }
                        is DirectSafCreateResult.ProviderFailure -> {
                            return@commit cleanupTemporarySafWriteFailure(
                                temporaryUri,
                                StorageWriteResult.ProviderFailure(createResult.error)
                            )
                        }
                    }
                    directTargetUri = finalUri
                    when (val copyResult = copySafDocument(
                        sourceUri = temporaryUri,
                        targetUri = finalUri,
                        expectedDisplayName = safTarget.displayName
                    )) {
                        is DirectSafCopyResult.Copied -> {
                            val temporaryCleanupError =
                                deleteSafDocumentAndConfirm(temporaryUri)
                            return@commit if (temporaryCleanupError == null) {
                                StorageWriteResult.Written(copyResult.stat)
                            } else {
                                storageWriteFailure(temporaryCleanupError)
                            }
                        }
                        DirectSafCopyResult.PermissionLost -> {
                            val cleanupError = cleanupDirectSafCreateFailure(
                                directTargetUri = finalUri,
                                temporaryUri = temporaryUri
                            )
                            return@commit cleanupError?.let(::storageWriteFailure)
                                ?: StorageWriteResult.PermissionLost
                        }
                        is DirectSafCopyResult.ProviderFailure -> {
                            val cleanupError = cleanupDirectSafCreateFailure(
                                directTargetUri = finalUri,
                                temporaryUri = temporaryUri
                            )
                            return@commit cleanupError?.let(::storageWriteFailure)
                                ?: StorageWriteResult.ProviderFailure(copyResult.error)
                        }
                    }
                } catch (error: CancellationException) {
                    cleanupDirectSafCreateFailure(
                        directTargetUri = directTargetUri,
                        temporaryUri = temporaryUri
                    )?.let(error::addSuppressed)
                    throw error
                }
            }
            SafWriteCommitMode.AtomicRename -> Unit
        }

        val backupUri = existingTarget?.reference
            ?.let { it as? StorageReference.SafRef }
            ?.uri
            ?.let { existingUri ->
                val backupName = if (knownExistingReference == null) {
                    safBackupName(safTarget.displayName)
                } else {
                    ".${safTarget.displayName}.${UUID.randomUUID()}.backup"
                }
                val renamedUri = try {
                    DocumentsContract.renameDocument(context.contentResolver, existingUri, backupName)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: SecurityException) {
                    return@commit cleanupTemporarySafWriteFailure(
                        temporaryUri,
                        StorageWriteResult.PermissionLost
                    )
                } catch (error: UnsupportedOperationException) {
                    return@commit cleanupTemporarySafWriteFailure(
                        temporaryUri,
                        StorageWriteResult.Unsupported("provider rename")
                    )
                } catch (error: Throwable) {
                    return@commit cleanupTemporarySafWriteFailure(
                        temporaryUri,
                        StorageWriteResult.ProviderFailure(error)
                    )
                } ?: run {
                    return@commit cleanupTemporarySafWriteFailure(
                        temporaryUri,
                        StorageWriteResult.Unsupported("provider rename")
                    )
                }
                confirmSafDocumentName(renamedUri, backupName)?.let { error ->
                    val restoreError = restoreBackupAndConfirm(
                        uri = renamedUri,
                        displayName = safTarget.displayName
                    )
                    val temporaryCleanupError = deleteSafDocumentAndConfirm(temporaryUri)
                    return@commit restoreError?.let(::storageWriteFailure)
                        ?: temporaryCleanupError?.let(::storageWriteFailure)
                        ?: storageWriteFailure(error)
                }
                renamedUri
            }

        val finalUri = try {
            DocumentsContract.renameDocument(context.contentResolver, temporaryUri, safTarget.displayName)
        } catch (error: CancellationException) {
            rollbackSafReplacement(
                parentUri = parentUri,
                temporaryUri = temporaryUri,
                finalUri = null,
                backupUri = backupUri,
                displayName = safTarget.displayName,
                initialResult = StorageWriteResult.ProviderFailure(error)
            )
            throw error
        } catch (error: SecurityException) {
            return@commit rollbackSafReplacement(
                parentUri = parentUri,
                temporaryUri = temporaryUri,
                finalUri = null,
                backupUri = backupUri,
                displayName = safTarget.displayName,
                initialResult = StorageWriteResult.PermissionLost
            )
        } catch (error: UnsupportedOperationException) {
            return@commit rollbackSafReplacement(
                parentUri = parentUri,
                temporaryUri = temporaryUri,
                finalUri = null,
                backupUri = backupUri,
                displayName = safTarget.displayName,
                initialResult = StorageWriteResult.Unsupported("provider rename")
            )
        } catch (error: Throwable) {
            return@commit rollbackSafReplacement(
                parentUri = parentUri,
                temporaryUri = temporaryUri,
                finalUri = null,
                backupUri = backupUri,
                displayName = safTarget.displayName,
                initialResult = StorageWriteResult.ProviderFailure(error)
            )
        } ?: run {
            return@commit rollbackSafReplacement(
                parentUri = parentUri,
                temporaryUri = temporaryUri,
                finalUri = null,
                backupUri = backupUri,
                displayName = safTarget.displayName,
                initialResult = StorageWriteResult.Unsupported("provider rename")
            )
        }

        when (val result = queryDocument(finalUri)) {
            SafQueryResult.Missing -> {
                rollbackSafReplacement(
                    parentUri = parentUri,
                    temporaryUri = temporaryUri,
                    finalUri = finalUri,
                    backupUri = backupUri,
                    displayName = safTarget.displayName,
                    initialResult = StorageWriteResult.ProviderFailure(
                        IllegalStateException("renamed file cannot be queried")
                    )
                )
            }
            SafQueryResult.OutOfScope -> {
                return@commit rollbackSafReplacement(
                    parentUri = parentUri,
                    temporaryUri = temporaryUri,
                    finalUri = finalUri,
                    backupUri = backupUri,
                    displayName = safTarget.displayName,
                    initialResult = StorageWriteResult.OutOfScope
                )
            }
            SafQueryResult.PermissionLost -> {
                rollbackSafReplacement(
                    parentUri = parentUri,
                    temporaryUri = temporaryUri,
                    finalUri = finalUri,
                    backupUri = backupUri,
                    displayName = safTarget.displayName,
                    initialResult = StorageWriteResult.PermissionLost
                )
            }
            is SafQueryResult.ProviderFailure -> {
                rollbackSafReplacement(
                    parentUri = parentUri,
                    temporaryUri = temporaryUri,
                    finalUri = finalUri,
                    backupUri = backupUri,
                    displayName = safTarget.displayName,
                    initialResult = StorageWriteResult.ProviderFailure(result.error)
                )
            }
            is SafQueryResult.Found -> {
                if (result.document.displayName != safTarget.displayName) {
                    rollbackSafReplacement(
                        parentUri = parentUri,
                        temporaryUri = temporaryUri,
                        finalUri = finalUri,
                        backupUri = backupUri,
                        displayName = safTarget.displayName,
                        initialResult = StorageWriteResult.ProviderFailure(
                            IllegalStateException("provider changed target display name")
                        )
                    )
                } else {
                    val backupCleanupError = backupUri?.let(::deleteSafDocumentAndConfirm)
                    if (backupCleanupError != null) {
                        storageWriteFailure(backupCleanupError)
                    } else {
                        StorageWriteResult.Written(result.document.toStat(finalUri))
                    }
                }
            }
        }
        }
        } finally {
            temporaryLease.close()
        }
    }

    override suspend fun delete(reference: TrustedManagedRef): StorageMutationResult = withContext(ioDispatcher) {
        val safReference = reference.reference as? StorageReference.SafRef
            ?: return@withContext StorageMutationResult.Unsupported("SAF reference required")
        invalidateParentDocument(safReference.uri)
        when (val result = queryDocument(safReference.uri)) {
            SafQueryResult.Missing -> return@withContext StorageMutationResult.Missing
            SafQueryResult.OutOfScope -> return@withContext StorageMutationResult.OutOfScope
            SafQueryResult.PermissionLost -> return@withContext StorageMutationResult.PermissionLost
            is SafQueryResult.ProviderFailure -> {
                return@withContext StorageMutationResult.ProviderFailure(result.error)
            }
            is SafQueryResult.Found -> Unit
        }
        confirmSafDelete(safReference.uri)?.let { return@withContext it }
        try {
            DocumentsContract.deleteDocument(context.contentResolver, safReference.uri)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            return@withContext StorageMutationResult.PermissionLost
        } catch (error: FileNotFoundException) {
            return@withContext when (classifySafFileNotFound(error)) {
                SafFileFailure.Missing -> StorageMutationResult.Missing
                SafFileFailure.OutOfScope -> StorageMutationResult.OutOfScope
                SafFileFailure.PermissionLost -> StorageMutationResult.PermissionLost
                SafFileFailure.ProviderFailure -> StorageMutationResult.ProviderFailure(error)
            }
        } catch (error: UnsupportedOperationException) {
            return@withContext StorageMutationResult.Unsupported("provider delete")
        } catch (error: Throwable) {
            if (ManagedDownloadReferenceIo.isMissingDocumentFailure(error)) {
                return@withContext StorageMutationResult.Missing
            }
            return@withContext StorageMutationResult.ProviderFailure(error)
        }
        confirmSafDelete(safReference.uri)?.let { return@withContext it }

        try {
            context.contentResolver.delete(safReference.uri, null, null)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            return@withContext StorageMutationResult.PermissionLost
        } catch (error: FileNotFoundException) {
            return@withContext when (classifySafFileNotFound(error)) {
                SafFileFailure.Missing -> StorageMutationResult.Missing
                SafFileFailure.OutOfScope -> StorageMutationResult.OutOfScope
                SafFileFailure.PermissionLost -> StorageMutationResult.PermissionLost
                SafFileFailure.ProviderFailure -> StorageMutationResult.ProviderFailure(error)
            }
        } catch (error: Throwable) {
            if (ManagedDownloadReferenceIo.isMissingDocumentFailure(error)) {
                return@withContext StorageMutationResult.Missing
            }
            return@withContext StorageMutationResult.ProviderFailure(error)
        }
        confirmSafDelete(safReference.uri)?.let { return@withContext it }

        try {
            androidx.documentfile.provider.DocumentFile.fromSingleUri(context, safReference.uri)
                ?.delete()
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            return@withContext StorageMutationResult.PermissionLost
        } catch (error: Throwable) {
            if (ManagedDownloadReferenceIo.isMissingDocumentFailure(error)) {
                return@withContext StorageMutationResult.Missing
            }
            return@withContext StorageMutationResult.ProviderFailure(error)
        }
        confirmSafDelete(safReference.uri)?.let { return@withContext it }
        StorageMutationResult.ProviderFailure(IllegalStateException("provider delete was not confirmed"))
    }

    override suspend fun rename(
        reference: TrustedManagedRef,
        displayName: String
    ): StorageRenameResult = withContext(ioDispatcher) {
        val safReference = reference.reference as? StorageReference.SafRef
            ?: return@withContext StorageRenameResult.Unsupported("SAF reference required")
        invalidateParentDocument(safReference.uri)
        if (displayName.isBlank() || displayName == "." || displayName == "..") {
            return@withContext StorageRenameResult.Unsupported("valid SAF display name required")
        }
        when (val current = queryDocument(safReference.uri)) {
            SafQueryResult.Missing -> return@withContext StorageRenameResult.Missing
            SafQueryResult.OutOfScope -> return@withContext StorageRenameResult.OutOfScope
            SafQueryResult.PermissionLost -> return@withContext StorageRenameResult.PermissionLost
            is SafQueryResult.ProviderFailure -> {
                return@withContext StorageRenameResult.ProviderFailure(current.error)
            }
            is SafQueryResult.Found -> {
                if (current.document.flags and
                    DocumentsContract.Document.FLAG_SUPPORTS_RENAME.toLong() == 0L
                ) {
                    return@withContext StorageRenameResult.Unsupported("provider rename")
                }
            }
        }
        val renamedUri = try {
            DocumentsContract.renameDocument(
                context.contentResolver,
                safReference.uri,
                displayName
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            return@withContext StorageRenameResult.PermissionLost
        } catch (error: UnsupportedOperationException) {
            return@withContext StorageRenameResult.Unsupported("provider rename")
        } catch (error: FileNotFoundException) {
            return@withContext when (classifySafFileNotFound(error)) {
                SafFileFailure.Missing -> StorageRenameResult.Missing
                SafFileFailure.OutOfScope -> StorageRenameResult.OutOfScope
                SafFileFailure.PermissionLost -> StorageRenameResult.PermissionLost
                SafFileFailure.ProviderFailure -> StorageRenameResult.ProviderFailure(error)
            }
        } catch (error: Throwable) {
            return@withContext StorageRenameResult.ProviderFailure(error)
        } ?: return@withContext StorageRenameResult.Unsupported("provider rename")

        when (val result = queryDocument(renamedUri)) {
            SafQueryResult.Missing -> StorageRenameResult.ProviderFailure(
                IllegalStateException("renamed document cannot be queried")
            )
            SafQueryResult.OutOfScope -> StorageRenameResult.OutOfScope
            SafQueryResult.PermissionLost -> StorageRenameResult.PermissionLost
            is SafQueryResult.ProviderFailure -> StorageRenameResult.ProviderFailure(result.error)
            is SafQueryResult.Found -> {
                if (result.document.displayName != displayName) {
                    val cleanupError = deleteSafDocumentAndConfirm(renamedUri)
                    if (cleanupError is SecurityException) {
                        StorageRenameResult.PermissionLost
                    } else if (cleanupError != null) {
                        StorageRenameResult.ProviderFailure(cleanupError)
                    } else {
                        StorageRenameResult.ProviderFailure(
                            IllegalStateException("provider changed renamed display name")
                        )
                    }
                } else {
                    invalidateParentDocument(renamedUri)
                    StorageRenameResult.Renamed(result.document.toStat(renamedUri))
                }
            }
        }
    }

    private fun confirmSafDelete(uri: Uri): StorageMutationResult? {
        return when (val result = ManagedDownloadReferenceIo.inspect(context, uri.toString())) {
            ManagedDownloadReferenceIo.AccessResult.Missing -> StorageMutationResult.Deleted
            ManagedDownloadReferenceIo.AccessResult.PermissionLost -> {
                StorageMutationResult.PermissionLost
            }
            is ManagedDownloadReferenceIo.AccessResult.ProviderFailure -> {
                StorageMutationResult.ProviderFailure(result.error)
            }
            ManagedDownloadReferenceIo.AccessResult.Accessible -> null
        }
    }

    override suspend fun capabilities(reference: StorageReference): StorageCapabilities = withContext(ioDispatcher) {
        val safReference = reference as? StorageReference.SafRef
            ?: return@withContext emptyStorageCapabilities()
        val document = when (val result = queryDocument(safReference.uri)) {
            is SafQueryResult.Found -> result.document
            SafQueryResult.Missing,
            SafQueryResult.OutOfScope,
            SafQueryResult.PermissionLost,
            is SafQueryResult.ProviderFailure -> return@withContext emptyStorageCapabilities()
        }
        val flags = document.flags
        val isDirectory = document.isDirectory
        StorageCapabilities(
            canRead = true,
            canWrite = flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE.toLong() != 0L ||
                (isDirectory && flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong() != 0L),
            canCreate = isDirectory &&
                flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong() != 0L,
            canDelete = flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE.toLong() != 0L,
            canRename = flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME.toLong() != 0L,
            canMove = flags and DocumentsContract.Document.FLAG_SUPPORTS_MOVE.toLong() != 0L,
            canCopy = flags and DocumentsContract.Document.FLAG_SUPPORTS_COPY.toLong() != 0L,
            hasReliableSize = false,
            hasReliableLastModified = false
        )
    }

    private fun queryDocument(uri: Uri): SafQueryResult {
        return try {
            val cursor = context.contentResolver.query(uri, SAF_DOCUMENT_PROJECTION, null, null, null)
                ?: return SafQueryResult.ProviderFailure(
                    IllegalStateException("provider returned null document cursor")
                )
            cursor.use {
                if (!it.moveToFirst()) {
                    SafQueryResult.Missing
                } else {
                    SafQueryResult.Found(SafDocumentMetadata.from(it))
                }
            }
        } catch (error: SecurityException) {
            SafQueryResult.PermissionLost
        } catch (error: CancellationException) {
            throw error
        } catch (error: FileNotFoundException) {
            when (classifySafFileNotFound(error)) {
                SafFileFailure.Missing -> SafQueryResult.Missing
                SafFileFailure.OutOfScope -> SafQueryResult.OutOfScope
                SafFileFailure.PermissionLost -> SafQueryResult.PermissionLost
                SafFileFailure.ProviderFailure -> SafQueryResult.ProviderFailure(error)
            }
        } catch (error: Throwable) {
            when (classifySafFailure(error)) {
                SafFileFailure.Missing -> SafQueryResult.Missing
                SafFileFailure.OutOfScope -> SafQueryResult.OutOfScope
                SafFileFailure.PermissionLost -> SafQueryResult.PermissionLost
                SafFileFailure.ProviderFailure -> SafQueryResult.ProviderFailure(error)
            }
        }
    }

    private fun queryParentDocument(uri: Uri): SafQueryResult {
        return parentDocumentCache.getOrLoad(
            key = uri.toString(),
            load = { queryDocument(uri) },
            shouldCache = { result ->
                result is SafQueryResult.Found && result.document.isDirectory
            }
        )
    }

    private fun invalidateParentDocument(uri: Uri) {
        parentDocumentCache.invalidate(uri.toString())
    }

    private fun queryChildren(uri: Uri): SafChildrenResult {
        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (error: SecurityException) {
            return SafChildrenResult.PermissionLost
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return SafChildrenResult.ProviderFailure(error)
        }
        val isTree = try {
            DocumentsContract.isTreeUri(uri)
        } catch (error: SecurityException) {
            return SafChildrenResult.PermissionLost
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return SafChildrenResult.ProviderFailure(error)
        }
        val childrenUri = try {
            if (isTree) {
                DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
            } else {
                DocumentsContract.buildChildDocumentsUri(uri.authority, documentId)
            }
        } catch (error: SecurityException) {
            return SafChildrenResult.PermissionLost
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return SafChildrenResult.ProviderFailure(error)
        }
        return try {
            val cursor = context.contentResolver.query(childrenUri, SAF_DOCUMENT_PROJECTION, null, null, null)
                ?: return SafChildrenResult.ProviderFailure(
                    IllegalStateException("provider returned null children cursor")
                )
            cursor.use { childrenCursor ->
                val documentIdIndex = childrenCursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                if (documentIdIndex < 0) {
                    return@use SafChildrenResult.ProviderFailure(
                        IllegalStateException("children cursor omitted document id")
                    )
                }
                val entries = mutableListOf<StorageStat>()
                while (childrenCursor.moveToNext()) {
                    if (childrenCursor.isNull(documentIdIndex)) {
                        return@use SafChildrenResult.ProviderFailure(
                            IllegalStateException("children cursor returned null document id")
                        )
                    }
                    val childId = childrenCursor.getString(documentIdIndex)
                    val childUri = if (isTree) {
                        DocumentsContract.buildDocumentUriUsingTree(uri, childId)
                    } else {
                        DocumentsContract.buildDocumentUri(uri.authority, childId)
                    }
                    entries += SafDocumentMetadata.from(childrenCursor).toStat(childUri)
                }
                SafChildrenResult.Found(entries)
            }
        } catch (error: SecurityException) {
            SafChildrenResult.PermissionLost
        } catch (error: CancellationException) {
            throw error
        } catch (error: FileNotFoundException) {
            when (classifySafFileNotFound(error)) {
                SafFileFailure.Missing -> SafChildrenResult.Missing
                SafFileFailure.OutOfScope -> SafChildrenResult.OutOfScope
                SafFileFailure.PermissionLost -> SafChildrenResult.PermissionLost
                SafFileFailure.ProviderFailure -> SafChildrenResult.ProviderFailure(error)
            }
        } catch (error: Throwable) {
            when (classifySafFailure(error)) {
                SafFileFailure.Missing -> SafChildrenResult.Missing
                SafFileFailure.OutOfScope -> SafChildrenResult.OutOfScope
                SafFileFailure.PermissionLost -> SafChildrenResult.PermissionLost
                SafFileFailure.ProviderFailure -> SafChildrenResult.ProviderFailure(error)
            }
        }
    }

    private fun commitSafCreateOnlyByRename(
        temporaryUri: Uri,
        displayName: String
    ): StorageWriteResult {
        val finalUri = try {
            DocumentsContract.renameDocument(context.contentResolver, temporaryUri, displayName)
        } catch (error: CancellationException) {
            cleanupTemporarySafWriteCancellation(temporaryUri, error)
            throw error
        } catch (error: SecurityException) {
            return cleanupTemporarySafWriteFailure(
                temporaryUri,
                StorageWriteResult.PermissionLost
            )
        } catch (error: UnsupportedOperationException) {
            return cleanupTemporarySafWriteFailure(
                temporaryUri,
                StorageWriteResult.Unsupported("provider rename")
            )
        } catch (error: Throwable) {
            return cleanupTemporarySafWriteFailure(
                temporaryUri,
                StorageWriteResult.ProviderFailure(error)
            )
        } ?: return cleanupTemporarySafWriteFailure(
            temporaryUri,
            StorageWriteResult.ProviderFailure(
                StorageTargetChangedException("SAF 迁移目标名称已被占用: $displayName")
            )
        )

        return when (val result = queryDocument(finalUri)) {
            SafQueryResult.Missing -> StorageWriteResult.ProviderFailure(
                IllegalStateException("renamed create-only document cannot be queried")
            )
            SafQueryResult.OutOfScope -> StorageWriteResult.OutOfScope
            SafQueryResult.PermissionLost -> StorageWriteResult.PermissionLost
            is SafQueryResult.ProviderFailure -> StorageWriteResult.ProviderFailure(result.error)
            is SafQueryResult.Found -> {
                if (result.document.displayName == displayName) {
                    StorageWriteResult.Written(result.document.toStat(finalUri))
                } else {
                    cleanupTemporarySafWriteFailure(
                        finalUri,
                        StorageWriteResult.ProviderFailure(
                            StorageTargetChangedException(
                                "SAF Provider 调整了迁移目标名称: " +
                                    "expected=$displayName actual=${result.document.displayName}"
                            )
                        )
                    )
                }
            }
        }
    }

    private fun cleanupTemporarySafWriteFailure(
        temporaryUri: Uri,
        initialResult: StorageWriteResult
    ): StorageWriteResult {
        val cleanupError = deleteSafDocumentAndConfirm(temporaryUri) ?: return initialResult
        val combinedError = IllegalStateException(
            "SAF 临时写入文件未能确认删除",
            cleanupError
        )
        (initialResult as? StorageWriteResult.ProviderFailure)
            ?.error
            ?.let(combinedError::addSuppressed)
        return if (cleanupError is SecurityException) {
            StorageWriteResult.PermissionLost
        } else {
            StorageWriteResult.ProviderFailure(combinedError)
        }
    }

    private fun cleanupTemporarySafWriteCancellation(
        temporaryUri: Uri,
        cancellation: CancellationException
    ) {
        deleteSafDocumentAndConfirm(temporaryUri)?.let(cancellation::addSuppressed)
    }

    private fun cleanupDirectSafCreateFailure(
        directTargetUri: Uri?,
        temporaryUri: Uri
    ): Throwable? {
        val targetCleanupError = directTargetUri?.let(::deleteSafDocumentAndConfirm)
        val temporaryCleanupError = deleteSafDocumentAndConfirm(temporaryUri)
        return targetCleanupError ?: temporaryCleanupError
    }

    private fun reconcileSafBackupBeforeWrite(
        parentUri: Uri,
        displayName: String
    ): StorageWriteResult? {
        val children = when (val result = queryChildren(parentUri)) {
            is SafChildrenResult.Found -> result.entries
            SafChildrenResult.Missing -> {
                return StorageWriteResult.ProviderFailure(
                    IllegalStateException("SAF 写入前父目录不可见")
                )
            }
            SafChildrenResult.OutOfScope -> return StorageWriteResult.OutOfScope
            SafChildrenResult.PermissionLost -> return StorageWriteResult.PermissionLost
            is SafChildrenResult.ProviderFailure -> {
                return StorageWriteResult.ProviderFailure(result.error)
            }
        }
        val backups = children.filter { entry ->
            isSafBackupName(entry.displayName, displayName)
        }
        if (backups.isEmpty()) {
            return null
        }
        val finalExists = children.any { entry -> entry.displayName == displayName }
        if (finalExists) {
            backups.forEach { backup ->
                val backupUri = (backup.reference as? StorageReference.SafRef)?.uri
                    ?: return StorageWriteResult.ProviderFailure(
                        IllegalStateException("SAF 备份缺少文档引用")
                    )
                deleteSafDocumentAndConfirm(backupUri)?.let { error ->
                    return storageWriteFailure(error)
                }
            }
            return null
        }
        if (backups.size != 1) {
            return StorageWriteResult.ProviderFailure(
                IllegalStateException("SAF 缺少最终文件且存在多个备份")
            )
        }
        val backupUri = (backups.single().reference as? StorageReference.SafRef)?.uri
            ?: return StorageWriteResult.ProviderFailure(
                IllegalStateException("SAF 备份缺少文档引用")
            )
        return restoreBackupAndConfirm(backupUri, displayName)
            ?.let(::storageWriteFailure)
    }

    private fun safBackupName(displayName: String): String = ".${displayName}.backup"

    private fun isSafBackupName(name: String, displayName: String): Boolean {
        if (name == safBackupName(displayName)) {
            return true
        }
        val prefix = ".${displayName}."
        if (!name.startsWith(prefix) || !name.endsWith(".backup")) {
            return false
        }
        val legacyId = name.removePrefix(prefix).removeSuffix(".backup")
        return runCatching { UUID.fromString(legacyId) }.isSuccess
    }

    private fun rollbackSafReplacement(
        parentUri: Uri,
        temporaryUri: Uri,
        finalUri: Uri?,
        backupUri: Uri?,
        displayName: String,
        initialResult: StorageWriteResult
    ): StorageWriteResult {
        val observedFinalUri = finalUri ?: when (val lookup = findNamedSafChild(parentUri, displayName)) {
            is NamedSafChild.Found -> lookup.uri
            NamedSafChild.Missing -> null
            NamedSafChild.OutOfScope -> return StorageWriteResult.OutOfScope
            NamedSafChild.PermissionLost -> return StorageWriteResult.PermissionLost
            is NamedSafChild.ProviderFailure -> {
                return StorageWriteResult.ProviderFailure(lookup.error)
            }
        }
        observedFinalUri?.let { uri ->
            deleteSafDocumentAndConfirm(uri)?.let { error ->
                return storageWriteFailure(error)
            }
        }
        val temporaryCleanupError = deleteSafDocumentAndConfirm(temporaryUri)
        val backupRestoreError = backupUri?.let { uri ->
            restoreBackupAndConfirm(uri, displayName)
        }
        return temporaryCleanupError?.let(::storageWriteFailure)
            ?: backupRestoreError?.let(::storageWriteFailure)
            ?: initialResult
    }

    private fun findNamedSafChild(parentUri: Uri, displayName: String): NamedSafChild {
        return when (val children = queryChildren(parentUri)) {
            is SafChildrenResult.Found -> {
                val reference = children.entries.firstOrNull { entry ->
                    entry.displayName == displayName
                }?.reference as? StorageReference.SafRef
                reference?.let { NamedSafChild.Found(it.uri) } ?: NamedSafChild.Missing
            }
            SafChildrenResult.Missing -> NamedSafChild.Missing
            SafChildrenResult.OutOfScope -> NamedSafChild.OutOfScope
            SafChildrenResult.PermissionLost -> NamedSafChild.PermissionLost
            is SafChildrenResult.ProviderFailure -> NamedSafChild.ProviderFailure(children.error)
        }
    }

    private fun deleteSafDocumentAndConfirm(uri: Uri): Throwable? {
        when (val initial = queryDocument(uri)) {
            SafQueryResult.Missing -> return null
            SafQueryResult.OutOfScope -> return IllegalArgumentException(
                "SAF 文档不在当前树范围内: $uri"
            )
            SafQueryResult.PermissionLost -> {
                return SecurityException("SAF 删除前权限丢失: $uri")
            }
            is SafQueryResult.ProviderFailure -> return initial.error
            is SafQueryResult.Found -> Unit
        }
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return error
        }
        return when (val result = queryDocument(uri)) {
            SafQueryResult.Missing -> null
            SafQueryResult.OutOfScope -> IllegalArgumentException(
                "SAF 删除后文档不在当前树范围内: $uri"
            )
            SafQueryResult.PermissionLost -> SecurityException(
                "SAF 删除后无法确认权限: $uri"
            )
            is SafQueryResult.ProviderFailure -> result.error
            is SafQueryResult.Found -> IllegalStateException(
                "SAF 删除未确认: $uri"
            )
        }
    }

    private fun confirmSafDocumentName(uri: Uri, displayName: String): Throwable? {
        return when (val result = queryDocument(uri)) {
            SafQueryResult.Missing -> IllegalStateException("SAF 重命名后的文档丢失")
            SafQueryResult.OutOfScope -> IllegalArgumentException(
                "SAF 重命名后的文档不在当前树范围内: $displayName"
            )
            SafQueryResult.PermissionLost -> SecurityException(
                "SAF 重命名后权限丢失: $displayName"
            )
            is SafQueryResult.ProviderFailure -> result.error
            is SafQueryResult.Found -> {
                if (result.document.displayName == displayName) null
                else IllegalStateException("SAF 重命名名称不匹配: ${result.document.displayName}")
            }
        }
    }

    private fun restoreBackupAndConfirm(uri: Uri, displayName: String): Throwable? {
        val restoredUri = try {
            DocumentsContract.renameDocument(context.contentResolver, uri, displayName)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return error
        } ?: return IllegalStateException("SAF 备份恢复未返回目标 URI")
        return confirmSafDocumentName(restoredUri, displayName)
    }

    private fun storageWriteFailure(error: Throwable): StorageWriteResult {
        return if (error is SecurityException) {
            StorageWriteResult.PermissionLost
        } else {
            StorageWriteResult.ProviderFailure(error)
        }
    }

    private sealed interface NamedSafChild {
        data class Found(val uri: Uri) : NamedSafChild
        data object Missing : NamedSafChild
        data object OutOfScope : NamedSafChild
        data object PermissionLost : NamedSafChild
        data class ProviderFailure(val error: Throwable) : NamedSafChild
    }

    private sealed interface DirectSafCreateResult {
        data class Created(val uri: Uri) : DirectSafCreateResult
        data object Unsupported : DirectSafCreateResult
        data object PermissionLost : DirectSafCreateResult
        data class ProviderFailure(val error: Throwable) : DirectSafCreateResult
    }

    private fun createDirectSafTarget(
        parentUri: Uri,
        mimeType: String,
        displayName: String
    ): DirectSafCreateResult {
        return try {
            val uri = DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                mimeType,
                displayName
            )
            if (uri == null) DirectSafCreateResult.Unsupported else DirectSafCreateResult.Created(uri)
        } catch (_: SecurityException) {
            DirectSafCreateResult.PermissionLost
        } catch (_: UnsupportedOperationException) {
            DirectSafCreateResult.Unsupported
        } catch (error: FileNotFoundException) {
            when (classifySafFileNotFound(error)) {
                SafFileFailure.PermissionLost -> DirectSafCreateResult.PermissionLost
                SafFileFailure.Missing -> DirectSafCreateResult.Unsupported
                SafFileFailure.OutOfScope -> DirectSafCreateResult.Unsupported
                SafFileFailure.ProviderFailure -> DirectSafCreateResult.ProviderFailure(error)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            DirectSafCreateResult.ProviderFailure(error)
        }
    }

    private sealed interface DirectSafCopyResult {
        data class Copied(val stat: StorageStat) : DirectSafCopyResult
        data object PermissionLost : DirectSafCopyResult
        data class ProviderFailure(val error: Throwable) : DirectSafCopyResult
    }

    private fun copySafDocument(
        sourceUri: Uri,
        targetUri: Uri,
        expectedDisplayName: String
    ): DirectSafCopyResult {
        return try {
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: return DirectSafCopyResult.ProviderFailure(
                    IllegalStateException("provider returned null input stream")
                )
            val output = context.contentResolver.openOutputStream(targetUri, "w") ?: run {
                input.close()
                return DirectSafCopyResult.ProviderFailure(
                    IllegalStateException("provider returned null output stream")
                )
            }
            input.use { source ->
                output.use { target ->
                    source.copyTo(target, STREAM_COPY_BUFFER_SIZE_BYTES)
                }
            }
            when (val result = queryDocument(targetUri)) {
                SafQueryResult.OutOfScope -> DirectSafCopyResult.ProviderFailure(
                    IllegalArgumentException("direct SAF target is outside the current tree")
                )
                SafQueryResult.PermissionLost -> DirectSafCopyResult.PermissionLost
                is SafQueryResult.ProviderFailure -> {
                    DirectSafCopyResult.ProviderFailure(result.error)
                }
                SafQueryResult.Missing -> DirectSafCopyResult.ProviderFailure(
                    IllegalStateException("directly created file cannot be queried")
                )
                is SafQueryResult.Found -> {
                    if (result.document.isDirectory) {
                        DirectSafCopyResult.ProviderFailure(
                            IllegalStateException("provider created a directory for a file write")
                        )
                    } else if (result.document.displayName != expectedDisplayName) {
                        DirectSafCopyResult.ProviderFailure(
                            IllegalStateException(
                                "provider changed direct target display name"
                            )
                        )
                    } else {
                        DirectSafCopyResult.Copied(result.document.toStat(targetUri))
                    }
                }
            }
        } catch (_: SecurityException) {
            DirectSafCopyResult.PermissionLost
        } catch (error: FileNotFoundException) {
            when (classifySafFileNotFound(error)) {
                SafFileFailure.PermissionLost -> DirectSafCopyResult.PermissionLost
                SafFileFailure.Missing -> DirectSafCopyResult.ProviderFailure(error)
                SafFileFailure.OutOfScope -> DirectSafCopyResult.ProviderFailure(error)
                SafFileFailure.ProviderFailure -> DirectSafCopyResult.ProviderFailure(error)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            DirectSafCopyResult.ProviderFailure(error)
        }
    }

    internal sealed interface SafQueryResult {
        data class Found(val document: SafDocumentMetadata) : SafQueryResult
        data object Missing : SafQueryResult
        data object OutOfScope : SafQueryResult
        data object PermissionLost : SafQueryResult
        data class ProviderFailure(val error: Throwable) : SafQueryResult
    }

    private enum class SafFileFailure {
        Missing,
        OutOfScope,
        PermissionLost,
        ProviderFailure
    }

    private fun classifySafFileNotFound(error: FileNotFoundException): SafFileFailure {
        return classifySafFailure(error)
    }

    private fun classifySafFailure(error: Throwable): SafFileFailure {
        return when {
            ManagedDownloadReferenceIo.isPermissionDocumentFailure(error) -> {
                SafFileFailure.PermissionLost
            }
            ManagedDownloadReferenceIo.isMissingDocumentFailure(error) -> {
                SafFileFailure.Missing
            }
            isOutOfScopeDocumentFailure(error) -> {
                SafFileFailure.OutOfScope
            }
            else -> SafFileFailure.ProviderFailure
        }
    }

    private fun isOutOfScopeDocumentFailure(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { cause ->
            val message = cause.message?.lowercase(Locale.ROOT).orEmpty()
            message.contains("not a child of") ||
                message.contains("outside the tree") ||
                message.contains("out of scope")
        }
    }

    private sealed interface SafChildrenResult {
        data class Found(val entries: List<StorageStat>) : SafChildrenResult
        data object Missing : SafChildrenResult
        data object OutOfScope : SafChildrenResult
        data object PermissionLost : SafChildrenResult
        data class ProviderFailure(val error: Throwable) : SafChildrenResult
    }

    internal data class SafDocumentMetadata(
        val displayName: String,
        val sizeBytes: Long?,
        val lastModifiedMs: Long?,
        val isDirectory: Boolean,
        val flags: Long
    ) {
        fun toStat(referenceUri: Uri): StorageStat = StorageStat(
            reference = StorageReference.SafRef(referenceUri),
            displayName = displayName,
            sizeBytes = sizeBytes?.takeIf { !isDirectory && it >= 0L },
            lastModifiedMs = lastModifiedMs?.takeIf { it > 0L },
            isDirectory = isDirectory
        )

        companion object {
            fun from(cursor: Cursor): SafDocumentMetadata {
                requireOpaqueDocumentId(
                    cursor.getNullableString(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                )
                val displayName = cursor.getNullableString(DocumentsContract.Document.COLUMN_DISPLAY_NAME).orEmpty()
                val mimeType = cursor.getNullableString(DocumentsContract.Document.COLUMN_MIME_TYPE)
                return SafDocumentMetadata(
                    displayName = displayName,
                    sizeBytes = cursor.getNullableLong(DocumentsContract.Document.COLUMN_SIZE),
                    lastModifiedMs = cursor.getNullableLong(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                    flags = cursor.getNullableLong(DocumentsContract.Document.COLUMN_FLAGS) ?: 0L
                )
            }
        }
    }

}

/**
 * short-lived cache for successful parent-directory probes during write bursts
 */
internal class SafParentDocumentCache<T>(
    private val validateIntervalMs: Long = SAF_PARENT_DOCUMENT_CACHE_VALIDATE_INTERVAL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = 64
) {
    private data class Entry<T>(
        val value: T,
        val cachedAtMs: Long
    )

    private val entries = object : LinkedHashMap<String, Entry<T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry<T>>?): Boolean {
            return size > maxEntries
        }
    }

    init {
        require(validateIntervalMs > 0L)
        require(maxEntries > 0)
    }

    fun getOrLoad(
        key: String,
        load: () -> T,
        shouldCache: (T) -> Boolean
    ): T {
        synchronized(entries) {
            val now = nowMs()
            entries[key]
                ?.takeIf { now - it.cachedAtMs <= validateIntervalMs }
                ?.let { return it.value }
            entries.remove(key)
            val loaded = load()
            if (shouldCache(loaded)) {
                entries[key] = Entry(value = loaded, cachedAtMs = now)
            }
            return loaded
        }
    }

    fun invalidate(key: String) {
        synchronized(entries) {
            entries.remove(key)
        }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
        }
    }
}

private fun emptyStorageCapabilities() = StorageCapabilities(
    canRead = false,
    canWrite = false,
    canCreate = false,
    canDelete = false,
    canRename = false,
    canMove = false,
    canCopy = false,
    hasReliableSize = false,
    hasReliableLastModified = false
)

internal fun requireOpaqueDocumentId(documentId: String?): String = documentId
    ?: throw IllegalStateException("provider omitted document id")

private val SAF_DOCUMENT_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    DocumentsContract.Document.COLUMN_FLAGS
)

private fun Cursor.getNullableString(column: String): String? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun Cursor.getNullableLong(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}
