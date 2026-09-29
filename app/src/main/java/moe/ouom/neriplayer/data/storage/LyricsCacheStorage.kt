package moe.ouom.neriplayer.data.storage

import moe.ouom.neriplayer.data.storage.source.LYRICS_CACHE_DIRECTORY_NAME

import android.content.Context
import java.io.File

fun lyricsCacheDirectory(context: Context): File {
    return File(context.applicationContext.filesDir, LYRICS_CACHE_DIRECTORY_NAME)
}
