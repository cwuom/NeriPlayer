package moe.ouom.neriplayer.core.download.storage.entry

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild

internal object ManagedDownloadStoredEntryMapper {
    fun fromFile(file: File): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = file.name,
            reference = file.absolutePath,
            mediaUri = Uri.fromFile(file).toString(),
            localFilePath = file.absolutePath,
            sizeBytes = file.length(),
            lastModifiedMs = file.lastModified(),
            isDirectory = file.isDirectory
        )
    }

    fun fromTreeChild(child: QueriedTreeChild): ManagedDownloadStorage.StoredEntry {
        return fromTreeChild(
            name = child.name,
            documentReference = child.documentUri.toString(),
            sizeBytes = child.sizeBytes,
            lastModifiedMs = child.lastModifiedMs,
            isDirectory = child.isDirectory
        )
    }

    fun fromTreeChild(
        name: String,
        documentReference: String,
        sizeBytes: Long,
        lastModifiedMs: Long,
        isDirectory: Boolean
    ): ManagedDownloadStorage.StoredEntry {
        return ManagedDownloadStorage.StoredEntry(
            name = name,
            reference = documentReference,
            mediaUri = documentReference,
            localFilePath = null,
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs,
            isDirectory = isDirectory
        )
    }

    fun fromDocumentFile(
        documentFile: DocumentFile,
        knownName: String? = null,
        knownSizeBytes: Long? = null,
        knownLastModifiedMs: Long? = null,
        knownIsDirectory: Boolean? = null
    ): ManagedDownloadStorage.StoredEntry? {
        val displayName = knownName ?: documentFile.name ?: return null
        return ManagedDownloadStorage.StoredEntry(
            name = displayName,
            reference = documentFile.uri.toString(),
            mediaUri = documentFile.uri.toString(),
            localFilePath = null,
            sizeBytes = knownSizeBytes ?: documentFile.length(),
            lastModifiedMs = knownLastModifiedMs ?: documentFile.lastModified(),
            isDirectory = knownIsDirectory ?: documentFile.isDirectory
        )
    }
}
