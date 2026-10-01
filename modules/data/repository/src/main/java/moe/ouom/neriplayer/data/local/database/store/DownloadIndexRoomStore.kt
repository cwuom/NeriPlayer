package moe.ouom.neriplayer.data.local.database.store

import androidx.sqlite.db.SupportSQLiteDatabase
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase

internal data class DownloadIndexStorageStats(
    val databaseRecordCount: Int,
    val allocatedPageBytes: Long
) {
    companion object {
        val Empty = DownloadIndexStorageStats(
            databaseRecordCount = 0,
            allocatedPageBytes = 0L
        )
    }
}

internal class DownloadIndexRoomStore(
    private val database: NeriUserDataDatabase
) {
    fun storageStats(): DownloadIndexStorageStats {
        val sqliteDatabase = database.openHelper.readableDatabase
        val tableStats = DOWNLOAD_INDEX_TABLES.map { tableName ->
            readTableStats(sqliteDatabase, tableName)
        }
        val recordCount = tableStats.sumOf(DownloadIndexTableStats::recordCount)
        if (recordCount <= 0L) return DownloadIndexStorageStats.Empty

        val payloadBytes = tableStats.sumOf(DownloadIndexTableStats::payloadBytes)
        val allocatedPageBytes = downloadIndexPageBytes(sqliteDatabase)
            ?.takeIf { bytes -> bytes > 0L }
            ?: estimatedDownloadIndexPageBytes(sqliteDatabase, payloadBytes)
        return DownloadIndexStorageStats(
            databaseRecordCount = recordCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            allocatedPageBytes = allocatedPageBytes
        )
    }
}

private data class DownloadIndexTableStats(
    val recordCount: Long,
    val payloadBytes: Long
) {
    companion object {
        val Empty = DownloadIndexTableStats(recordCount = 0L, payloadBytes = 0L)
    }
}

private fun readTableStats(
    database: SupportSQLiteDatabase,
    tableName: String
): DownloadIndexTableStats {
    val columns = database.tableColumns(tableName)
    if (columns.isEmpty()) return DownloadIndexTableStats.Empty

    val payloadExpression = columns.joinToString(" + ") { columnName ->
        "COALESCE(length(CAST(${quoteIdentifier(columnName)} AS TEXT)), 0)"
    }
    return database.query(
        "SELECT COUNT(*), COALESCE(SUM($DOWNLOAD_INDEX_ROW_OVERHEAD_BYTES + " +
            "$payloadExpression), 0) FROM ${quoteIdentifier(tableName)}"
    ).use { cursor ->
        check(cursor.moveToFirst())
        DownloadIndexTableStats(
            recordCount = cursor.getLong(0),
            payloadBytes = cursor.getLong(1)
        )
    }
}

private fun SupportSQLiteDatabase.tableColumns(tableName: String): List<String> {
    return query("PRAGMA table_info(${quoteString(tableName)})").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        buildList {
            while (cursor.moveToNext()) {
                add(cursor.getString(nameIndex))
            }
        }
    }
}

private fun downloadIndexPageBytes(database: SupportSQLiteDatabase): Long? {
    return runCatching {
        database.query(
            "SELECT COALESCE(SUM(pgsize), 0) FROM dbstat " +
                "WHERE name IN (" +
                "SELECT name FROM sqlite_master " +
                "WHERE type IN ('table', 'index') AND tbl_name IN (" +
                DOWNLOAD_INDEX_TABLES.joinToString(",") { tableName ->
                    quoteString(tableName)
                } +
                ")" +
                ")"
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
    }.getOrNull()
}

private fun estimatedDownloadIndexPageBytes(
    database: SupportSQLiteDatabase,
    payloadBytes: Long
): Long {
    if (payloadBytes <= 0L) return 0L
    val pageSize = database.pragmaLong("page_size").coerceAtLeast(1L)
    val roundedPayloadBytes = ((payloadBytes + pageSize - 1L) / pageSize) * pageSize
    return roundedPayloadBytes.coerceAtLeast(pageSize)
}

private fun SupportSQLiteDatabase.pragmaLong(name: String): Long {
    return query("PRAGMA $name").use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }
}

private fun quoteIdentifier(value: String): String {
    return "\"${value.replace("\"", "\"\"")}\""
}

private fun quoteString(value: String): String {
    return "'${value.replace("'", "''")}'"
}

private const val DOWNLOAD_INDEX_ROW_OVERHEAD_BYTES = 24L

private val DOWNLOAD_INDEX_TABLES = listOf(
    "download_operation",
    "managed_library_item"
)
