package moe.ouom.neriplayer.core.download.storage.backend

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface StorageReference {
    data class FileRef(val logicalPath: String) : StorageReference
    data class SafRef(val uri: Uri) : StorageReference
}

sealed interface StorageTarget {
    data class FileTarget(val logicalPath: String) : StorageTarget
    data class SafTarget(
        val parent: StorageReference.SafRef,
        val displayName: String,
        val mimeType: String
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
    data object PermissionLost : StorageConfidence
    data class ProviderFailure(val error: Throwable) : StorageConfidence
}

sealed interface StorageLookupResult<out T> {
    data class Found<T>(val value: T) : StorageLookupResult<T>
    data object Missing : StorageLookupResult<Nothing>
    data object PermissionLost : StorageLookupResult<Nothing>
    data class ProviderFailure(val error: Throwable) : StorageLookupResult<Nothing>
    data class Unsupported(val operation: String) : StorageLookupResult<Nothing>
}

sealed interface StorageWriteResult {
    data class Written(val stat: StorageStat) : StorageWriteResult
    data object PermissionLost : StorageWriteResult
    data class ProviderFailure(val error: Throwable) : StorageWriteResult
    data class Unsupported(val operation: String) : StorageWriteResult
}

sealed interface StorageMutationResult {
    data object Deleted : StorageMutationResult
    data object Missing : StorageMutationResult
    data object PermissionLost : StorageMutationResult
    data class ProviderFailure(val error: Throwable) : StorageMutationResult
    data class Unsupported(val operation: String) : StorageMutationResult
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

    suspend fun delete(reference: StorageReference): StorageMutationResult

    suspend fun capabilities(reference: StorageReference): StorageCapabilities
}

internal class FileStorageBackend(
    private val root: File
) : StorageBackend {
    override suspend fun list(directory: StorageReference): StorageDirectorySnapshot = withContext(Dispatchers.IO) {
        val resolved = resolve(directory) ?: return@withContext StorageDirectorySnapshot(
            entries = emptyList(),
            confidence = StorageConfidence.PermissionLost
        )
        if (!resolved.isDirectory) {
            return@withContext StorageDirectorySnapshot(emptyList(), StorageConfidence.Complete)
        }
        val children = runCatching { resolved.listFiles() }.getOrElse {
            return@withContext StorageDirectorySnapshot(
                entries = emptyList(),
                confidence = StorageConfidence.ProviderFailure(it)
            )
        }
        StorageDirectorySnapshot(
            entries = children.orEmpty().map(::toStat),
            confidence = StorageConfidence.Complete
        )
    }

    override suspend fun stat(reference: StorageReference): StorageLookupResult<StorageStat> = withContext(Dispatchers.IO) {
        val file = resolve(reference) ?: return@withContext StorageLookupResult.PermissionLost
        if (!file.exists()) StorageLookupResult.Missing
        else StorageLookupResult.Found(toStat(file))
    }

    override suspend fun <T> read(
        reference: StorageReference,
        block: suspend (InputStream) -> T
    ): StorageLookupResult<T> = withContext(Dispatchers.IO) {
        val file = resolve(reference) ?: return@withContext StorageLookupResult.PermissionLost
        if (!file.isFile) return@withContext StorageLookupResult.Missing
        return@withContext try {
            val input = file.inputStream()
            val value = try {
                block(input)
            } finally {
                input.close()
            }
            StorageLookupResult.Found(value)
        } catch (error: CancellationException) {
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
            ?: return@withContext StorageWriteResult.PermissionLost
        val temporary = File(targetFile.parentFile, ".${targetFile.name}.pending")
        return@withContext try {
            targetFile.parentFile?.mkdirs()
            val output = temporary.outputStream()
            try {
                writer(output)
            } finally {
                output.close()
            }
            check(temporary.renameTo(targetFile)) { "atomic file rename failed" }
            StorageWriteResult.Written(toStat(targetFile))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            StorageWriteResult.ProviderFailure(error)
        }
    }

    override suspend fun delete(reference: StorageReference): StorageMutationResult = withContext(Dispatchers.IO) {
        val file = resolve(reference) ?: return@withContext StorageMutationResult.PermissionLost
        if (!file.exists()) StorageMutationResult.Missing
        else if (file.deleteRecursively()) StorageMutationResult.Deleted
        else StorageMutationResult.ProviderFailure(IllegalStateException("delete failed"))
    }

    override suspend fun capabilities(reference: StorageReference): StorageCapabilities =
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
}

internal class SafStorageBackend(
    private val context: Context
) : StorageBackend {
    override suspend fun list(directory: StorageReference): StorageDirectorySnapshot = withContext(Dispatchers.IO) {
        val safReference = directory as? StorageReference.SafRef
            ?: return@withContext StorageDirectorySnapshot(
                emptyList(),
                StorageConfidence.ProviderFailure(IllegalArgumentException("SAF reference required"))
            )
        val document = resolve(safReference.uri)
            ?: return@withContext StorageDirectorySnapshot(emptyList(), StorageConfidence.PermissionLost)
        val children = runCatching { document.listFiles().toList() }.getOrElse {
            return@withContext StorageDirectorySnapshot(emptyList(), StorageConfidence.ProviderFailure(it))
        }
        StorageDirectorySnapshot(
            entries = children.map(::toStat),
            confidence = StorageConfidence.Complete
        )
    }

    override suspend fun stat(reference: StorageReference): StorageLookupResult<StorageStat> = withContext(Dispatchers.IO) {
        val safReference = reference as? StorageReference.SafRef
            ?: return@withContext StorageLookupResult.Unsupported("SAF reference required")
        val document = resolve(safReference.uri)
            ?: return@withContext StorageLookupResult.PermissionLost
        if (!document.exists()) StorageLookupResult.Missing
        else StorageLookupResult.Found(toStat(document))
    }

    override suspend fun <T> read(
        reference: StorageReference,
        block: suspend (InputStream) -> T
    ): StorageLookupResult<T> = withContext(Dispatchers.IO) {
        val safReference = reference as? StorageReference.SafRef
            ?: return@withContext StorageLookupResult.Unsupported("SAF reference required")
        val input = runCatching { context.contentResolver.openInputStream(safReference.uri) }
            .getOrElse { return@withContext StorageLookupResult.ProviderFailure(it) }
            ?: return@withContext StorageLookupResult.Missing
        return@withContext try {
            val value = try {
                block(input)
            } finally {
                input.close()
            }
            StorageLookupResult.Found(value)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            StorageLookupResult.ProviderFailure(error)
        }
    }

    override suspend fun writeRecoverable(
        target: StorageTarget,
        writer: suspend (OutputStream) -> Unit
    ): StorageWriteResult = withContext(Dispatchers.IO) {
        val safTarget = target as? StorageTarget.SafTarget
            ?: return@withContext StorageWriteResult.Unsupported("SAF target required")
        val parent = resolve(safTarget.parent.uri)
            ?: return@withContext StorageWriteResult.PermissionLost
        val child = runCatching {
            parent.findFile(safTarget.displayName)?.takeIf { it.isFile }
                ?: parent.createFile(safTarget.mimeType, safTarget.displayName)
        }.getOrElse { return@withContext StorageWriteResult.ProviderFailure(it) }
            ?: return@withContext StorageWriteResult.ProviderFailure(
                IllegalStateException("provider refused file creation")
            )
        val output = runCatching { context.contentResolver.openOutputStream(child.uri, "w") }
            .getOrElse { return@withContext StorageWriteResult.ProviderFailure(it) }
            ?: return@withContext StorageWriteResult.PermissionLost
        return@withContext try {
            try {
                writer(output)
            } finally {
                output.close()
            }
            StorageWriteResult.Written(toStat(child))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            StorageWriteResult.ProviderFailure(error)
        }
    }

    override suspend fun delete(reference: StorageReference): StorageMutationResult = withContext(Dispatchers.IO) {
        val safReference = reference as? StorageReference.SafRef
            ?: return@withContext StorageMutationResult.Unsupported("SAF reference required")
        val document = resolve(safReference.uri)
            ?: return@withContext StorageMutationResult.PermissionLost
        if (!document.exists()) StorageMutationResult.Missing
        else if (document.delete()) StorageMutationResult.Deleted
        else StorageMutationResult.Unsupported("provider delete")
    }

    override suspend fun capabilities(reference: StorageReference): StorageCapabilities = withContext(Dispatchers.IO) {
        val safReference = reference as? StorageReference.SafRef
            ?: return@withContext StorageCapabilities(false, false, false, false, false, false, false, false, false)
        val document = resolve(safReference.uri)
            ?: return@withContext StorageCapabilities(false, false, false, false, false, false, false, false, false)
        val flags = runCatching {
            context.contentResolver.query(
                safReference.uri,
                arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
            } ?: 0L
        }.getOrDefault(0L)
        val canWrite = document.canWrite()
        StorageCapabilities(
            canRead = document.exists(),
            canWrite = canWrite,
            canCreate = flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong() != 0L || canWrite,
            canDelete = flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE.toLong() != 0L || canWrite,
            canRename = flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME.toLong() != 0L,
            canMove = flags and DocumentsContract.Document.FLAG_SUPPORTS_MOVE.toLong() != 0L,
            canCopy = flags and DocumentsContract.Document.FLAG_SUPPORTS_COPY.toLong() != 0L,
            hasReliableSize = true,
            hasReliableLastModified = false
        )
    }

    private fun resolve(uri: Uri): DocumentFile? =
        DocumentFile.fromTreeUri(context, uri)
            ?: DocumentFile.fromSingleUri(context, uri)

    private fun toStat(document: DocumentFile): StorageStat = StorageStat(
        reference = StorageReference.SafRef(document.uri),
        displayName = document.name.orEmpty(),
        sizeBytes = document.length().takeIf { it >= 0L && document.isFile },
        lastModifiedMs = document.lastModified().takeIf { it > 0L },
        isDirectory = document.isDirectory
    )
}
