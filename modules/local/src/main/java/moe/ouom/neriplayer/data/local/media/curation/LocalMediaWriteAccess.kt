package moe.ouom.neriplayer.data.local.media.curation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.local.media.localMediaUri
import moe.ouom.neriplayer.data.model.SongItem

private const val MEDIA_STORE_AUTHORITY = "media"
private const val TAG = "LocalMediaWriteAccess"

/** 系统单次修改请求最多接受 2000 个条目，留出余量分批申请 */
internal const val MAX_URIS_PER_WRITE_REQUEST = 1_000

/** 批量写入前需要用户完成的授权 */
sealed interface LocalMediaWriteAccessPlan {
    /** 所有文件都已可写 */
    data object Ready : LocalMediaWriteAccessPlan

    /** Android 11 起依次弹出系统修改授权，每个请求最多 [MAX_URIS_PER_WRITE_REQUEST] 首 */
    data class SystemConsent(val requests: List<PendingIntent>) : LocalMediaWriteAccessPlan

    /** Android 10 没有批量修改授权，只能让用户授予这批歌曲所在的文件夹，写入改走文件夹授权 */
    data class FolderGrant(val suggestedFolder: StorageDocumentPath?) : LocalMediaWriteAccessPlan

    /** 系统拒绝创建授权请求，继续写入必然整批失败 */
    data class Unavailable(val error: Throwable) : LocalMediaWriteAccessPlan
}

/**
 * Android 10 起修改其他应用创建的媒体文件需要用户授权。批量写入前集中申请，
 * 避免每首歌都因 RecoverableSecurityException 失败
 */
object LocalMediaWriteAccess {
    fun isMediaStoreItem(uri: Uri): Boolean =
        uri.scheme.equals("content", ignoreCase = true) && uri.authority == MEDIA_STORE_AUTHORITY

    suspend fun plan(context: Context, songs: List<SongItem>): LocalMediaWriteAccessPlan =
        withContext(Dispatchers.IO) {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    val pending = songs.mapNotNull { song -> pendingMediaStoreUri(context, song) }.distinct()
                    systemConsentPlan(pending) { batch -> MediaStore.createWriteRequest(context.contentResolver, batch) }
                }
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                    val pending = songs.filter { song ->
                        pendingMediaStoreUri(context, song)?.let { uri -> !isOwnedByApp(context, uri) } == true
                    }
                    folderGrantPlan(pending.map { song -> FolderArtworkLocator.storagePathOf(context, song) })
                }
                else -> LocalMediaWriteAccessPlan.Ready
            }
        }

    private suspend fun pendingMediaStoreUri(context: Context, song: SongItem): Uri? {
        val uri = song.localMediaUri()?.takeIf(::isMediaStoreItem) ?: return null
        return uri.takeUnless {
            hasWriteGrant(context, it) || FolderArtworkLocator.isInsideWritableTree(context, song)
        }
    }

    private fun hasWriteGrant(context: Context, uri: Uri): Boolean {
        return context.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Android 10 上应用自己写入的媒体文件无需授权 */
    private fun isOwnedByApp(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(OWNER_PACKAGE_NAME_COLUMN), null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == context.packageName
        } == true
    }.getOrDefault(false)

    private const val OWNER_PACKAGE_NAME_COLUMN = "owner_package_name"
}

internal fun systemConsentPlan(
    pending: List<Uri>,
    createRequest: (List<Uri>) -> PendingIntent
): LocalMediaWriteAccessPlan {
    if (pending.isEmpty()) return LocalMediaWriteAccessPlan.Ready
    return runCatching {
        pending.chunked(MAX_URIS_PER_WRITE_REQUEST).map(createRequest)
    }.fold(
        onSuccess = { LocalMediaWriteAccessPlan.SystemConsent(it) },
        onFailure = { error ->
            NPLogger.e(TAG, "create write request failed for ${pending.size} items", error)
            LocalMediaWriteAccessPlan.Unavailable(error)
        }
    )
}

internal fun folderGrantPlan(pendingDirectories: List<StorageDocumentPath?>): LocalMediaWriteAccessPlan {
    if (pendingDirectories.isEmpty()) return LocalMediaWriteAccessPlan.Ready
    return LocalMediaWriteAccessPlan.FolderGrant(
        FolderArtworkLocator.commonAncestor(pendingDirectories.filterNotNull().distinct())
    )
}
