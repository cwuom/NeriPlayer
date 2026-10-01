package moe.ouom.neriplayer.platform.bilibili.cache

import android.content.Context
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.platform.bilibili.cache.archive.BiliArchiveCacheRepository
import moe.ouom.neriplayer.platform.bilibili.cache.favorite.BiliFavoriteFolderCacheRepository
import java.io.File

object BiliCacheRepositories {
    fun createArchive(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context.applicationContext),
        cacheDir: File = File(context.applicationContext.filesDir, "bili_archive_cache")
    ): BiliArchiveCacheRepository {
        return BiliArchiveCacheRepository(BiliArchiveCacheRoomStore(database), cacheDir)
    }

    fun createFavoriteFolder(
        context: Context,
        database: NeriUserDataDatabase = NeriUserDataDatabase.getInstance(context.applicationContext),
        cacheDir: File = File(context.applicationContext.filesDir, "bili_favorite_cache")
    ): BiliFavoriteFolderCacheRepository {
        return BiliFavoriteFolderCacheRepository(BiliFavoriteFolderCacheRoomStore(database), cacheDir)
    }
}
