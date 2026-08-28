package moe.ouom.neriplayer.core.download.catalog

import android.content.Context
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import java.io.File
import moe.ouom.neriplayer.util.io.writeTextAtomically

internal class DownloadedSongCatalogStore(
    private val cacheFileName: String,
    private val snapshotCacheKeyProvider: (Context) -> String,
    private val loggerTag: String
) {
    fun restore(context: Context): List<DownloadedSong>? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            NPLogger.d(loggerTag, "主线程跳过下载歌曲目录阻塞恢复，等待后台预热")
            return null
        }
        val roomRestored = runCatching {
            runBlocking(Dispatchers.IO) {
                roomStore(context).restore()
            }
        }.onFailure { error ->
            NPLogger.w(loggerTag, "读取 Room 下载歌曲目录失败，尝试旧 JSON: ${error.message}")
        }.getOrNull()
        return roomRestored ?: restoreDurableOrLegacyCatalog(context)
    }

    fun persist(context: Context, songs: List<DownloadedSong>): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            NPLogger.w(loggerTag, "主线程拒绝同步写入下载歌曲目录")
            return false
        }
        return runCatching {
            runBlocking(Dispatchers.IO) {
                persistDownloadedSongCatalogWithFallback(
                    store = roomStore(context),
                    songs = songs,
                    onRoomFailure = { error ->
                        NPLogger.e(
                            loggerTag,
                            "写入 Room 下载歌曲目录失败，降级写旧 JSON",
                            error
                        )
                    }
                )
            }
        }.map { true }.getOrElse { error ->
            NPLogger.e(loggerTag, "写入 Room 下载歌曲目录失败，直接写旧 JSON", error)
            runCatching { writeLegacyCatalog(context, songs) }
                .onFailure { fallbackError ->
                    NPLogger.e(loggerTag, "写入旧下载歌曲目录也失败", fallbackError)
                }
                .isSuccess
        }
    }

    private fun restoreDurableOrLegacyCatalog(context: Context): List<DownloadedSong>? {
        val appContext = context.applicationContext
        val rootKey = snapshotCacheKeyProvider(appContext)
        val backupFile = File(
            appContext.filesDir,
            "$cacheFileName$MANAGED_LIBRARY_CATALOG_BACKUP_SUFFIX"
        )
        if (backupFile.isFile) {
            readManagedCatalogBackupFile(backupFile, rootKey)?.let { return it }
            NPLogger.w(loggerTag, "完整下载目录备份无效，回退旧下载歌曲目录: ${backupFile.name}")
        }
        return restoreLegacyCatalog(appContext, rootKey)
    }

    private fun restoreLegacyCatalog(
        context: Context,
        rootKey: String = snapshotCacheKeyProvider(context.applicationContext)
    ): List<DownloadedSong>? {
        val file = File(context.applicationContext.filesDir, cacheFileName)
        val rawPayload = runCatching {
            file.takeIf(File::exists)?.readText(Charsets.UTF_8)
        }.onFailure { error ->
            NPLogger.w(loggerTag, "读取旧下载歌曲目录失败: ${error.message}")
        }.getOrNull() ?: return null
        if (rawPayload.isBlank()) return null
        return runCatching {
            deserializeDownloadedSongsCatalog(
                raw = rawPayload,
                expectedCacheKey = rootKey,
                includeOriginalLyrics = true
            )
        }.onFailure { error ->
            NPLogger.w(loggerTag, "解析旧下载歌曲目录失败: ${error.message}")
        }.getOrNull()
    }

    private fun writeLegacyCatalog(context: Context, songs: List<DownloadedSong>) {
        val appContext = context.applicationContext
        val rootKey = snapshotCacheKeyProvider(appContext)
        File(appContext.filesDir, cacheFileName).writeTextAtomically(
            serializeDownloadedSongsCatalog(rootKey, songs)
        )
        val backupFile = File(
            appContext.filesDir,
            "$cacheFileName$MANAGED_LIBRARY_CATALOG_BACKUP_SUFFIX"
        )
        if (!writeManagedCatalogBackupFile(backupFile, rootKey, songs)) {
            NPLogger.w(
                loggerTag,
                "写入完整下载目录备份失败，保留旧下载歌曲目录: ${backupFile.name}"
            )
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

internal enum class DownloadedSongCatalogPersistTarget {
    ROOM,
    LEGACY_JSON
}

internal interface DownloadedSongCatalogPersistenceStore {
    suspend fun persistCatalog(songs: List<DownloadedSong>)

    suspend fun persistLegacyFallback(songs: List<DownloadedSong>)
}

internal suspend fun persistDownloadedSongCatalogWithFallback(
    store: DownloadedSongCatalogPersistenceStore,
    songs: List<DownloadedSong>,
    onRoomFailure: (Throwable) -> Unit = {}
): DownloadedSongCatalogPersistTarget {
    return runCatching {
        store.persistCatalog(songs)
        DownloadedSongCatalogPersistTarget.ROOM
    }.getOrElse { error ->
        onRoomFailure(error)
        store.persistLegacyFallback(songs)
        DownloadedSongCatalogPersistTarget.LEGACY_JSON
    }
}
