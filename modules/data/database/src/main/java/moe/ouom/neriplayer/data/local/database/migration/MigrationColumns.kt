package moe.ouom.neriplayer.data.local.database.migration

import androidx.sqlite.db.SupportSQLiteDatabase

internal fun addIntegerColumnIfMissing(
    db: SupportSQLiteDatabase,
    tableName: String,
    columnName: String
) {
    val hasColumn = db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        nameIndex >= 0 && generateSequence {
            if (cursor.moveToNext()) cursor.getString(nameIndex) else null
        }.any { it == columnName }
    }
    if (!hasColumn) {
        db.execSQL(
            "ALTER TABLE `$tableName` ADD COLUMN `$columnName` INTEGER"
        )
    }
}

internal fun addTextColumnIfMissing(
    db: SupportSQLiteDatabase,
    tableName: String,
    columnName: String
) {
    val hasColumn = db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        nameIndex >= 0 && generateSequence {
            if (cursor.moveToNext()) cursor.getString(nameIndex) else null
        }.any { it == columnName }
    }
    if (!hasColumn) {
        db.execSQL(
            "ALTER TABLE `$tableName` ADD COLUMN `$columnName` TEXT"
        )
    }
}
