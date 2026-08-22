package moe.ouom.neriplayer.core.download.storage.backend

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.database.Cursor
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
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
    data object Missing : StorageConfidence
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
    data object Missing : StorageWriteResult
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
        when (val parent = queryDocument(safReference.uri)) {
            SafQueryResult.Missing -> StorageDirectorySnapshot(emptyList(), StorageConfidence.Missing)
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

    override suspend fun stat(reference: StorageReference): StorageLookupResult<StorageStat> = withContext(Dispatchers.IO) {
        val safReference = reference as? StorageReference.SafRef
            ?: return@withContext StorageLookupResult.Unsupported("SAF reference required")
        when (val result = queryDocument(safReference.uri)) {
            SafQueryResult.Missing -> StorageLookupResult.Missing
            SafQueryResult.PermissionLost -> StorageLookupResult.PermissionLost
            is SafQueryResult.ProviderFailure -> StorageLookupResult.ProviderFailure(result.error)
            is SafQueryResult.Found -> StorageLookupResult.Found(result.document.toStat(safReference.uri))
        }
    }

    override suspend fun <T> read(
        reference: StorageReference,
        block: suspend (InputStream) -> T
    ): StorageLookupResult<T> = withContext(Dispatchers.IO) {
        val safReference = reference as? StorageReference.SafRef
            ?: return@withContext StorageLookupResult.Unsupported("SAF reference required")
        when (val result = queryDocument(safReference.uri)) {
            SafQueryResult.Missing -> return@withContext StorageLookupResult.Missing
            SafQueryResult.PermissionLost -> return@withContext StorageLookupResult.PermissionLost
            is SafQueryResult.ProviderFailure -> return@withContext StorageLookupResult.ProviderFailure(result.error)
            is SafQueryResult.Found -> Unit
        }
        val input = try {
            context.contentResolver.openInputStream(safReference.uri)
        } catch (error: SecurityException) {
            return@withContext StorageLookupResult.PermissionLost
        } catch (error: FileNotFoundException) {
            return@withContext StorageLookupResult.Missing
        } catch (error: Throwable) {
            return@withContext StorageLookupResult.ProviderFailure(error)
        } ?: return@withContext StorageLookupResult.ProviderFailure(
            IllegalStateException("provider returned null input stream")
        )
        return@withContext try {
            val value = block(input)
            input.close()
            StorageLookupResult.Found(value)
        } catch (error: CancellationException) {
            runCatching { input.close() }
            throw error
        } catch (error: Throwable) {
            runCatching { input.close() }
            StorageLookupResult.ProviderFailure(error)
        }
    }

    override suspend fun writeRecoverable(
        target: StorageTarget,
        writer: suspend (OutputStream) -> Unit
    ): StorageWriteResult = withContext(Dispatchers.IO) {
        val safTarget = target as? StorageTarget.SafTarget
            ?: return@withContext StorageWriteResult.Unsupported("SAF target required")
        if (safTarget.displayName.isBlank() || safTarget.displayName == "." || safTarget.displayName == "..") {
            return@withContext StorageWriteResult.Unsupported("valid SAF display name required")
        }
        val parentUri = safTarget.parent.uri
        val parent = when (val result = queryDocument(parentUri)) {
            SafQueryResult.Missing -> return@withContext StorageWriteResult.Missing
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
        val temporaryName = ".${safTarget.displayName}.${UUID.randomUUID()}.pending"
        val temporaryUri = try {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentUri,
                safTarget.mimeType,
                temporaryName
            )
        } catch (error: SecurityException) {
            return@withContext StorageWriteResult.PermissionLost
        } catch (error: UnsupportedOperationException) {
            return@withContext StorageWriteResult.Unsupported("provider create")
        } catch (error: Throwable) {
            return@withContext StorageWriteResult.ProviderFailure(error)
        } ?: return@withContext StorageWriteResult.ProviderFailure(
            IllegalStateException("provider refused temporary file creation")
        )

        val output = try {
            context.contentResolver.openOutputStream(temporaryUri, "w")
        } catch (error: SecurityException) {
            deleteQuietly(temporaryUri)
            return@withContext StorageWriteResult.PermissionLost
        } catch (error: FileNotFoundException) {
            deleteQuietly(temporaryUri)
            return@withContext StorageWriteResult.Missing
        } catch (error: Throwable) {
            deleteQuietly(temporaryUri)
            return@withContext StorageWriteResult.ProviderFailure(error)
        } ?: run {
            deleteQuietly(temporaryUri)
            return@withContext StorageWriteResult.ProviderFailure(
                IllegalStateException("provider returned null output stream")
            )
        }
        try {
            try {
                writer(output)
            } finally {
                output.close()
            }
        } catch (error: CancellationException) {
            deleteQuietly(temporaryUri)
            throw error
        } catch (error: Throwable) {
            deleteQuietly(temporaryUri)
            return@withContext StorageWriteResult.ProviderFailure(error)
        }

        val temporaryStat = when (val result = queryDocument(temporaryUri)) {
            SafQueryResult.Missing -> {
                deleteQuietly(temporaryUri)
                return@withContext StorageWriteResult.ProviderFailure(
                    IllegalStateException("temporary file disappeared after write")
                )
            }
            SafQueryResult.PermissionLost -> {
                deleteQuietly(temporaryUri)
                return@withContext StorageWriteResult.PermissionLost
            }
            is SafQueryResult.ProviderFailure -> {
                deleteQuietly(temporaryUri)
                return@withContext StorageWriteResult.ProviderFailure(result.error)
            }
            is SafQueryResult.Found -> result.document
        }
        if (temporaryStat.isDirectory) {
            deleteQuietly(temporaryUri)
            return@withContext StorageWriteResult.ProviderFailure(
                IllegalStateException("provider created a directory for a file write")
            )
        }

        val existingTarget = when (val children = queryChildren(parentUri)) {
            is SafChildrenResult.Found -> children.entries.firstOrNull {
                it.displayName == safTarget.displayName
            }
            SafChildrenResult.Missing -> return@withContext StorageWriteResult.ProviderFailure(
                IllegalStateException("provider lost parent children during write")
            )
            SafChildrenResult.PermissionLost -> return@withContext StorageWriteResult.PermissionLost
            is SafChildrenResult.ProviderFailure -> {
                return@withContext StorageWriteResult.ProviderFailure(children.error)
            }
        }
        if (existingTarget?.isDirectory == true) {
            deleteQuietly(temporaryUri)
            return@withContext StorageWriteResult.Unsupported("target directory exists")
        }
        val canRename = temporaryStat.flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME.toLong() != 0L
        if (!canRename) {
            return@withContext StorageWriteResult.Unsupported("provider rename")
        }

        val backupUri = existingTarget?.reference
            ?.let { it as? StorageReference.SafRef }
            ?.uri
            ?.let { existingUri ->
                val backupName = ".${safTarget.displayName}.${UUID.randomUUID()}.backup"
                try {
                    DocumentsContract.renameDocument(context.contentResolver, existingUri, backupName)
                } catch (error: SecurityException) {
                    return@withContext StorageWriteResult.PermissionLost
                } catch (error: UnsupportedOperationException) {
                    return@withContext StorageWriteResult.Unsupported("provider rename")
                } catch (error: Throwable) {
                    return@withContext StorageWriteResult.ProviderFailure(error)
                } ?: return@withContext StorageWriteResult.Unsupported("provider rename")
            }

        val finalUri = try {
            DocumentsContract.renameDocument(context.contentResolver, temporaryUri, safTarget.displayName)
        } catch (error: SecurityException) {
            backupUri?.let { restoreBackup(it, safTarget.displayName) }
            return@withContext StorageWriteResult.PermissionLost
        } catch (error: UnsupportedOperationException) {
            backupUri?.let { restoreBackup(it, safTarget.displayName) }
            return@withContext StorageWriteResult.Unsupported("provider rename")
        } catch (error: Throwable) {
            backupUri?.let { restoreBackup(it, safTarget.displayName) }
            return@withContext StorageWriteResult.ProviderFailure(error)
        } ?: run {
            backupUri?.let { restoreBackup(it, safTarget.displayName) }
            return@withContext StorageWriteResult.Unsupported("provider rename")
        }

        when (val result = queryDocument(finalUri)) {
            SafQueryResult.Missing -> StorageWriteResult.ProviderFailure(
                IllegalStateException("renamed file cannot be queried")
            )
            SafQueryResult.PermissionLost -> StorageWriteResult.PermissionLost
            is SafQueryResult.ProviderFailure -> StorageWriteResult.ProviderFailure(result.error)
            is SafQueryResult.Found -> {
                backupUri?.let(::deleteQuietly)
                if (result.document.displayName != safTarget.displayName) {
                    deleteQuietly(finalUri)
                    StorageWriteResult.ProviderFailure(
                        IllegalStateException("provider changed target display name")
                    )
                } else {
                    StorageWriteResult.Written(result.document.toStat(finalUri))
                }
            }
        }
    }

    override suspend fun delete(reference: StorageReference): StorageMutationResult = withContext(Dispatchers.IO) {
        val safReference = reference as? StorageReference.SafRef
            ?: return@withContext StorageMutationResult.Unsupported("SAF reference required")
        when (val result = queryDocument(safReference.uri)) {
            SafQueryResult.Missing -> return@withContext StorageMutationResult.Missing
            SafQueryResult.PermissionLost -> return@withContext StorageMutationResult.PermissionLost
            is SafQueryResult.ProviderFailure -> {
                return@withContext StorageMutationResult.ProviderFailure(result.error)
            }
            is SafQueryResult.Found -> Unit
        }
        try {
            if (DocumentsContract.deleteDocument(context.contentResolver, safReference.uri)) {
                StorageMutationResult.Deleted
            } else {
                StorageMutationResult.ProviderFailure(IllegalStateException("provider delete failed"))
            }
        } catch (error: SecurityException) {
            StorageMutationResult.PermissionLost
        } catch (error: FileNotFoundException) {
            StorageMutationResult.Missing
        } catch (error: UnsupportedOperationException) {
            StorageMutationResult.Unsupported("provider delete")
        } catch (error: Throwable) {
            StorageMutationResult.ProviderFailure(error)
        }
    }

    override suspend fun capabilities(reference: StorageReference): StorageCapabilities = withContext(Dispatchers.IO) {
        val safReference = reference as? StorageReference.SafRef
            ?: return@withContext emptyCapabilities()
        val document = when (val result = queryDocument(safReference.uri)) {
            is SafQueryResult.Found -> result.document
            SafQueryResult.Missing,
            SafQueryResult.PermissionLost,
            is SafQueryResult.ProviderFailure -> return@withContext emptyCapabilities()
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
        } catch (error: FileNotFoundException) {
            SafQueryResult.Missing
        } catch (error: Throwable) {
            SafQueryResult.ProviderFailure(error)
        }
    }

    private fun queryChildren(uri: Uri): SafChildrenResult {
        val documentId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (error: Throwable) {
            return SafChildrenResult.ProviderFailure(error)
        }
        val isTree = runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)
        val childrenUri = try {
            if (isTree) {
                DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
            } else {
                DocumentsContract.buildChildDocumentsUri(uri.authority, documentId)
            }
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
        } catch (error: FileNotFoundException) {
            SafChildrenResult.Missing
        } catch (error: Throwable) {
            SafChildrenResult.ProviderFailure(error)
        }
    }

    private fun deleteQuietly(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }

    private fun restoreBackup(uri: Uri, displayName: String) {
        runCatching {
            DocumentsContract.renameDocument(context.contentResolver, uri, displayName)
        }
    }

    private fun emptyCapabilities() = StorageCapabilities(
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

    private sealed interface SafQueryResult {
        data class Found(val document: SafDocumentMetadata) : SafQueryResult
        data object Missing : SafQueryResult
        data object PermissionLost : SafQueryResult
        data class ProviderFailure(val error: Throwable) : SafQueryResult
    }

    private sealed interface SafChildrenResult {
        data class Found(val entries: List<StorageStat>) : SafChildrenResult
        data object Missing : SafChildrenResult
        data object PermissionLost : SafChildrenResult
        data class ProviderFailure(val error: Throwable) : SafChildrenResult
    }

    private data class SafDocumentMetadata(
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
