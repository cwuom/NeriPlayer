package moe.ouom.neriplayer.core.download.storage.tree.query

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild

internal object ManagedDownloadTreeChildQuery {
    fun queryChildren(
        context: Context,
        parent: DocumentFile,
        onQueryFailure: (Throwable) -> Unit
    ): List<QueriedTreeChild> {
        val parentUri = parent.uri
        val documentId = runCatching { DocumentsContract.getDocumentId(parentUri) }.getOrNull()
            ?: return listChildrenWithDocumentFile(parent)

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, documentId)
        return runCatching {
            context.contentResolver.query(
                childrenUri,
                CHILD_PROJECTION,
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idIndex < 0 || nameIndex < 0 || mimeTypeIndex < 0) {
                    return@use emptyList()
                }
                buildList {
                    while (cursor.moveToNext()) {
                        val childDocumentId = cursor.getString(idIndex) ?: continue
                        val childName = cursor.getString(nameIndex) ?: continue
                        val childMimeType = cursor.getString(mimeTypeIndex).orEmpty()
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(parentUri, childDocumentId)
                        add(
                            QueriedTreeChild(
                                name = childName,
                                documentUri = childUri,
                                sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                                    cursor.getLong(sizeIndex)
                                } else {
                                    0L
                                },
                                lastModifiedMs = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                                    cursor.getLong(modifiedIndex)
                                } else {
                                    0L
                                },
                                isDirectory = childMimeType == DocumentsContract.Document.MIME_TYPE_DIR
                            )
                        )
                    }
                }
            }.orEmpty()
        }.onFailure(onQueryFailure).getOrElse {
            listChildrenWithDocumentFile(parent)
        }
    }

    private fun listChildrenWithDocumentFile(parent: DocumentFile): List<QueriedTreeChild> {
        return parent.listFiles().mapNotNull { file ->
            file.name?.let { name ->
                QueriedTreeChild(
                    name = name,
                    documentUri = file.uri,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                    isDirectory = file.isDirectory
                )
            }
        }
    }

    private val CHILD_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )
}
