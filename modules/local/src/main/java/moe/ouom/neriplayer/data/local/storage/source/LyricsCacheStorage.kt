package moe.ouom.neriplayer.data.local.storage.source

import android.content.Context
import java.io.File

fun lyricsCacheDirectory(context: Context): File {
    return File(context.applicationContext.filesDir, LYRICS_CACHE_DIRECTORY_NAME)
}
