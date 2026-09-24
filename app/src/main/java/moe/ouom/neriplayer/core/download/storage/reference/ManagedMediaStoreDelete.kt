package moe.ouom.neriplayer.core.download.storage.reference

import android.content.ContentProviderOperation
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.core.logging.NPLogger

/** 系统提供映射和原 URI 的授权，删除后仍以 SAF 物理状态为准 */
internal object ManagedMediaStoreDelete {
    fun deleteConfirmed(context: Context, documents: List<Uri>): Set<Uri> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptySet()
        val startedAt = System.nanoTime()
        val targets = documents.mapNotNull { prepare(context, it) }
        if (targets.isEmpty()) return emptySet()
        val preparedAt = System.nanoTime()
        var deletedAt = preparedAt
        val operations = ArrayList<ContentProviderOperation>(targets.size)
        targets.forEach { target ->
            operations += ContentProviderOperation.newDelete(target.media)
                // 目录或文件名改变后不再删除这个 MediaStore 行
                .withSelection("${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(target.name, target.relativePath))
                .withExceptionAllowed(true)
                .build()
        }
        val confirmed = deleteAndConfirmDocuments(
            targets = targets,
            delete = {
                try {
                    context.contentResolver.applyBatch(MediaStore.AUTHORITY, operations)
                } finally {
                    deletedAt = System.nanoTime()
                }
            },
            inspect = { target ->
                ManagedDownloadReferenceIo.inspect(context, target.document)
            }
        ).mapTo(linkedSetOf()) { it.document }
        NPLogger.d("ManagedMediaStoreDelete", "requested=${documents.size}, mapped=${targets.size}, " +
            "confirmed=${confirmed.size}, prepareMs=${(preparedAt - startedAt) / 1_000_000}, " +
            "deleteMs=${(deletedAt - preparedAt) / 1_000_000}, " +
            "confirmMs=${(System.nanoTime() - deletedAt) / 1_000_000}")
        return confirmed
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun prepare(context: Context, document: Uri): Target? {
        if (document.authority != "com.android.externalstorage.documents") return null
        return try {
            val name = context.contentResolver.query(document, arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst() ||
                    cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR
                ) return null
                cursor.getString(0)
            } ?: return null
            val media = MediaStore.getMediaUri(context, document) ?: return null
            if (media.scheme != "content" || media.authority != MediaStore.AUTHORITY) return null
            resolveMappedTarget(context, document, media, name)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 未索引、权限不足或不支持转换的文档继续使用 DocumentsProvider
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    internal fun resolveMappedTarget(context: Context, document: Uri, media: Uri, name: String): Target? {
        return try {
            val target = context.contentResolver.query(media, arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH
            ), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != name || cursor.isNull(1)) return null
                Target(document, media, name, cursor.getString(1))
            } ?: return null
            // 行 ID 会跨目录移动，读取行属性后再核对其仍对应原 SAF 文档
            val currentDocument = MediaStore.getDocumentUri(context, media) ?: return null
            target.takeIf {
                currentDocument.authority == document.authority &&
                    DocumentsContract.getDocumentId(currentDocument) == DocumentsContract.getDocumentId(document)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    internal data class Target(val document: Uri, val media: Uri, val name: String, val relativePath: String)
}

internal fun <T> deleteAndConfirmDocuments(
    targets: Collection<T>,
    delete: () -> Unit,
    inspect: (T) -> ManagedDownloadReferenceIo.AccessResult
): Set<T> {
    try {
        delete()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Provider 可能已完成部分操作，逐项核对后再走原来的删除路径
    }
    return targets.filterTo(linkedSetOf()) { target ->
        try {
            inspect(target) == ManagedDownloadReferenceIo.AccessResult.Missing
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }
}
