package moe.ouom.neriplayer.core.download.catalog

import android.content.Context
import java.io.File
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.logging.NPLogger

internal class DownloadedSongCatalogStore(
    private val cacheFileName: String,
    private val snapshotCacheKeyProvider: (Context) -> String,
    private val loggerTag: String
) {
    fun restore(context: Context): List<DownloadedSong>? {
        val rawPayload = runCatching {
            cacheFile(context).takeIf(File::exists)?.readText(Charsets.UTF_8)
        }.onFailure {
            NPLogger.w(loggerTag, "读取下载歌曲目录缓存失败: ${it.message}")
        }.getOrNull() ?: return null

        return runCatching {
            deserializeDownloadedSongsCatalog(
                raw = rawPayload,
                expectedCacheKey = snapshotCacheKeyProvider(context)
            )
        }.onFailure {
            NPLogger.w(loggerTag, "解析下载歌曲目录缓存失败: ${it.message}")
        }.getOrNull()
    }

    fun persist(context: Context, songs: List<DownloadedSong>) {
        runCatching {
            cacheFile(context).writeText(
                serializeDownloadedSongsCatalog(
                    cacheKey = snapshotCacheKeyProvider(context),
                    songs = songs
                ),
                Charsets.UTF_8
            )
        }.onFailure {
            NPLogger.w(loggerTag, "写入下载歌曲目录缓存失败: ${it.message}")
        }
    }

    private fun cacheFile(context: Context): File {
        return File(context.filesDir, cacheFileName)
    }
}
