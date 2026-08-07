package moe.ouom.neriplayer.core.download.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase

internal class DownloadedSongCatalogStore(
    private val cacheFileName: String,
    private val snapshotCacheKeyProvider: (Context) -> String,
    private val loggerTag: String
) {
    fun restore(context: Context): List<DownloadedSong>? {
        return runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore(context).restore()
            }
        }.onFailure {
            NPLogger.w(loggerTag, "读取下载歌曲目录失败: ${it.message}")
        }.getOrNull()
    }

    fun persist(context: Context, songs: List<DownloadedSong>) {
        runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore(context).persist(songs)
            }
        }.onFailure {
            NPLogger.e(loggerTag, "写入下载歌曲目录失败", it)
        }
    }

    private fun roomStore(context: Context): DownloadedSongCatalogRoomStore {
        val appContext = context.applicationContext
        return DownloadedSongCatalogRoomStore(
            context = appContext,
            database = NeriUserDataDatabase.getInstance(appContext),
            cacheFileName = cacheFileName,
            snapshotCacheKeyProvider = snapshotCacheKeyProvider,
            loggerTag = loggerTag
        )
    }
}
