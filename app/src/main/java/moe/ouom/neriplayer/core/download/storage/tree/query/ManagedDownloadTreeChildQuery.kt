package moe.ouom.neriplayer.core.download.storage.tree.query

import android.content.Context
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import moe.ouom.neriplayer.core.download.storage.tree.cache.QueriedTreeChild
import java.io.IOException

internal object ManagedDownloadTreeChildQuery {
    internal enum class State {
        COMPLETE,
        LOADING,
        PROVIDER_ERROR,
        FAILED
    }

    internal data class QueryResult(
        val children: List<QueriedTreeChild>,
        val state: State
    ) {
        val isComplete: Boolean
            get() = state == State.COMPLETE
    }

    fun queryChildren(
        context: Context,
        parent: DocumentFile,
        onQueryFailure: (Throwable) -> Unit
    ): List<QueriedTreeChild> = queryChildrenWithStatus(
        context = context,
        parent = parent,
        onQueryFailure = onQueryFailure
    ).children

    fun queryChildrenWithStatus(
        context: Context,
        parent: DocumentFile,
        onQueryFailure: (Throwable) -> Unit
    ): QueryResult {
        val parentUri = parent.uri
        val documentId = try {
            DocumentsContract.getDocumentId(parentUri)
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return QueryResult(
            children = listChildrenWithDocumentFile(parent),
            state = State.FAILED
        )

        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, documentId)
        } catch (error: IllegalArgumentException) {
            return QueryResult(
                children = listChildrenWithDocumentFile(parent),
                state = State.FAILED
            )
        }
        return try {
            val cursor = context.contentResolver.query(
                childrenUri,
                CHILD_PROJECTION,
                null,
                null,
                null
            ) ?: throw IOException("DocumentsProvider returned null cursor for $childrenUri")
            val queryResult = cursor.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeTypeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (idIndex < 0 || nameIndex < 0 || mimeTypeIndex < 0) {
                    throw IllegalStateException("DocumentsProvider omitted required child columns")
                }
                val children = buildList {
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
                                    null
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
                val extras = cursor.extras
                val providerError = extras?.getString(DocumentsContract.EXTRA_ERROR)
                val loading = extras?.getBoolean(DocumentsContract.EXTRA_LOADING, false) == true
                QueryResult(
                    children = children,
                    state = when {
                        loading -> State.LOADING
                        !providerError.isNullOrBlank() -> State.PROVIDER_ERROR
                        else -> State.COMPLETE
                    }
                )
            }
            queryResult
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            onQueryFailure(error)
            QueryResult(
                children = listChildrenWithDocumentFile(parent),
                state = State.FAILED
            )
        }
    }

    private fun listChildrenWithDocumentFile(parent: DocumentFile): List<QueriedTreeChild> {
        val files = try {
            parent.listFiles().toList()
        } catch (error: SecurityException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        return files
            .mapNotNull { file ->
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

    internal fun isCompleteQuery(loading: Boolean, providerError: String?): Boolean {
        return !loading && providerError.isNullOrBlank()
    }

    private val CHILD_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )
}
