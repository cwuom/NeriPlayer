package moe.ouom.neriplayer.data.local.database

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** 打开 Room 之前对用户数据库文件版本的检查结果 */
sealed interface DatabaseVersionState {
    /** 还没有数据库文件，Room 会直接按当前版本创建 */
    data object Missing : DatabaseVersionState

    /** Room 可以打开，[found] 较旧时会按迁移升级到 [supported] */
    data class Compatible(val found: Int, val supported: Int) : DatabaseVersionState

    /** 由更新的应用写入，Room 打开会崩溃；数据要留给新版应用，不能清除 */
    data class NewerThanApp(val found: Int, val supported: Int) : DatabaseVersionState

    /** 文件存在但读不到版本号 */
    data class Unreadable(val cause: Exception) : DatabaseVersionState
}

/**
 * 不经过 Room 只读读取 user_version，供应用在 [NeriUserDataDatabase.getInstance] 之前判断能否打开。
 * 读取过程不写入、不删除数据库文件。
 */
fun NeriUserDataDatabase.Companion.checkVersion(context: Context): DatabaseVersionState =
    databaseVersionState(context.getDatabasePath(NeriUserDataDatabase.DATABASE_NAME))

internal fun databaseVersionState(
    file: File,
    supported: Int = NeriUserDataDatabase.FINAL_DB_VERSION,
    readUserVersion: (File) -> Int = ::readUserVersionReadOnly
): DatabaseVersionState {
    if (!file.exists()) return DatabaseVersionState.Missing
    val found = try {
        readUserVersion(file)
    } catch (error: Exception) {
        return DatabaseVersionState.Unreadable(error)
    }
    return if (found > supported) DatabaseVersionState.NewerThanApp(found, supported)
    else DatabaseVersionState.Compatible(found, supported)
}

// 默认的损坏处理会删除数据库文件，这里只读检查必须保留原文件
private val keepFileOnCorruption = DatabaseErrorHandler { }

// 通过 SQLite 读取才能看到仍留在 WAL 里、尚未写回主文件的新版本号
private fun readUserVersionReadOnly(file: File): Int = SQLiteDatabase.openDatabase(
    file.path,
    null,
    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    keepFileOnCorruption
).use { it.version }
