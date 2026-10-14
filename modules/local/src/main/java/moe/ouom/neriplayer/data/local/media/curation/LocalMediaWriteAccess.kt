package moe.ouom.neriplayer.data.local.media.curation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.media.localMediaUri
import moe.ouom.neriplayer.data.model.SongItem

private const val MEDIA_STORE_AUTHORITY = "media"

/**
 * Android 11 起修改其他应用创建的媒体文件需要用户逐批授权。批量写入前集中申请一次，
 * 避免每首歌都因 RecoverableSecurityException 失败
 */
object LocalMediaWriteAccess {
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    val isRequestSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun isMediaStoreItem(uri: Uri): Boolean =
        uri.scheme.equals("content", ignoreCase = true) && uri.authority == MEDIA_STORE_AUTHORITY

    suspend fun pendingWriteUris(context: Context, songs: List<SongItem>): List<Uri> =
        withContext(Dispatchers.IO) {
            if (!isRequestSupported) return@withContext emptyList()
            songs.mapNotNull { song ->
                val uri = song.localMediaUri()?.takeIf(::isMediaStoreItem) ?: return@mapNotNull null
                uri.takeUnless {
                    hasWriteGrant(context, it) || FolderArtworkLocator.isInsideWritableTree(context, song)
                }
            }.distinct()
        }

    fun createWriteRequest(context: Context, uris: List<Uri>): PendingIntent? {
        if (!isRequestSupported || uris.isEmpty()) return null
        return runCatching { MediaStore.createWriteRequest(context.contentResolver, uris) }.getOrNull()
    }

    private fun hasWriteGrant(context: Context, uri: Uri): Boolean {
        return context.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
