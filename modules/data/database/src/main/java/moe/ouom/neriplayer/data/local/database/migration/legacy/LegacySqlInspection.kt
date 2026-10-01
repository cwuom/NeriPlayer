package moe.ouom.neriplayer.data.local.database.migration.legacy

import androidx.sqlite.db.SupportSQLiteDatabase

internal fun hasTable(db: SupportSQLiteDatabase, tableName: String): Boolean {
    db.query(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
        arrayOf(tableName)
    ).use { cursor ->
        return cursor.moveToFirst()
    }
}

internal fun tableColumnNames(
    db: SupportSQLiteDatabase,
    tableName: String
): Set<String> {
    val columns = linkedSetOf<String>()
    db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return columns
        while (cursor.moveToNext()) {
            cursor.getString(nameIndex)?.let(columns::add)
        }
    }
    return columns
}
