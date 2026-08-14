package moe.ouom.neriplayer.data.storage

import android.content.Context
import java.io.File

const val LYRICS_CACHE_DIRECTORY_NAME = "lyrics_cache"

fun lyricsCacheDirectory(context: Context): File {
    return File(context.applicationContext.filesDir, LYRICS_CACHE_DIRECTORY_NAME)
}
