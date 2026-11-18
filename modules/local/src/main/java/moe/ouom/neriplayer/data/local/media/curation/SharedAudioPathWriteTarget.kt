package moe.ouom.neriplayer.data.local.media.curation

import android.content.ContentUris
import android.content.Context
import android.content.UriPermission
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.util.Locale

internal data class SharedAudioPathWriteTarget(
    val file: File,
    val uri: Uri?,
    val folderNeedingGrant: StorageDocumentPath?,
    val recoveryAliases: List<Uri>
)

internal fun resolveSharedAudioPathWriteTarget(
    context: Context,
    source: Uri,
    permissions: () -> List<UriPermission>
): SharedAudioPathWriteTarget? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        !source.scheme.equals("file", ignoreCase = true) || !source.authority.isNullOrEmpty()
    ) return null

    val path = source.path?.takeIf { it.startsWith('/') } ?: return null
    val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
    val primaryRoot = runCatching { Environment.getExternalStorageDirectory().canonicalPath + "/" }.getOrNull()
    val storagePath = if (primaryRoot != null && file.path.startsWith(primaryRoot)) {
        StorageDocumentPath("primary", file.path.removePrefix(primaryRoot))
    } else {
        FolderArtworkLocator.storagePathOf(file.path)?.takeUnless { it.volumeId == "primary" } ?: return null
    }
    if (storagePath.relativePath.isEmpty()) return null
    val parent = storagePath.copy(relativePath = storagePath.relativePath.substringBeforeLast('/', ""))
    val document = FolderArtworkLocator.documentUriFor(storagePath)
    val writableDocument = FolderArtworkLocator.writableExternalStorageDocumentUri(document, parent, permissions())
    val mediaItem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        resolveMediaStoreAudioItem(context, file.path, storagePath.volumeId)
    } else null
    val canWriteFile = file.canWrite()
    val target = if (!canWriteFile || file.parentFile?.canWrite() != true ||
        mediaItem?.let { LocalMediaWriteAccess.hasWriteGrant(context, it) } == true
    ) {
        // 媒体授权也可能让路径看起来可写，但不会授权创建同目录的侧载
        writableDocument ?: mediaItem
    } else {
        null
    }
    return SharedAudioPathWriteTarget(
        file = file,
        uri = target,
        folderNeedingGrant = parent.takeIf { target == null && !canWriteFile },
        recoveryAliases = listOfNotNull(document, mediaItem)
    )
}

@RequiresApi(Build.VERSION_CODES.R)
private fun resolveMediaStoreAudioItem(context: Context, path: String, volumeId: String): Uri? {
    val volumeName = if (volumeId == "primary") {
        MediaStore.VOLUME_EXTERNAL_PRIMARY
    } else {
        volumeId.lowercase(Locale.ROOT)
    }
    val collection = MediaStore.Audio.Media.getContentUri(volumeName)
    // 旧曲库只保留了绝对路径，必须核对完整路径才能安全地回写原文件
    val pathColumn = "_data"
    return try {
        context.contentResolver.query(
            collection, arrayOf(MediaStore.Audio.Media._ID, pathColumn), "$pathColumn = ?", arrayOf(path), null
        )?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(1) != path || cursor.isNull(0)) return null
            val id = cursor.getLong(0)
            if (id < 0 || cursor.moveToNext()) null else ContentUris.withAppendedId(collection, id)
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SQLiteException) {
        null
    }
}
