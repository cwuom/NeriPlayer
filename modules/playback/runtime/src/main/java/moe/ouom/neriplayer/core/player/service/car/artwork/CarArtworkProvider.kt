package moe.ouom.neriplayer.core.player.service.car.artwork

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.player.service.car.CarControllerTrust
import moe.ouom.neriplayer.data.model.SongItem
import java.io.FileNotFoundException

class CarArtworkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Artwork is read only")
        val application = context ?: throw FileNotFoundException("Artwork unavailable")
        authorize(application)
        val key = validatedKey(application, uri) ?: throw FileNotFoundException("Invalid artwork URI")
        // 已校验调用方, 用应用自己的权限读取原始封面与 SAF 授权
        val identity = Binder.clearCallingIdentity()
        try {
            val file = runBlocking(Dispatchers.IO) { CarArtworkStore.file(application, key) }
                ?: throw FileNotFoundException("Artwork unavailable")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun getType(uri: Uri): String? {
        val application = context ?: return null
        authorize(application)
        return validatedKey(application, uri)?.let { "image/jpeg" }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Artwork is read only")

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Artwork is read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Artwork is read only")

    private fun authorize(application: Context) {
        if (!CarControllerTrust.isArtworkTrusted(application, Binder.getCallingUid())) {
            throw SecurityException("Artwork access denied")
        }
    }

    companion object {
        fun uriFor(context: Context, song: SongItem): Uri =
            uri(context, CarArtworkStore.register(song))

        suspend fun publish(context: Context, song: SongItem, bitmap: Bitmap, source: String?): Uri? {
            val key = CarArtworkStore.register(song, source)
            return if (CarArtworkStore.safelyPublish(context, key, bitmap)) uri(context, key) else null
        }

        private fun uri(context: Context, key: String): Uri = Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.car-artwork")
            .appendPath("v1")
            .appendPath(key)
            .build()

        internal fun validatedKey(context: Context, uri: Uri): String? {
            if (uri.scheme != "content" || uri.authority != "${context.packageName}.car-artwork") return null
            if (uri.encodedQuery != null || uri.encodedFragment != null) return null
            return carArtworkKeyFromPath(uri.encodedPath)
        }
    }
}
