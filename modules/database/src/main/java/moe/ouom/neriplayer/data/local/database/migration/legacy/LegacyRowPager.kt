package moe.ouom.neriplayer.data.local.database.migration.legacy

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase

internal fun forEachLegacyBatch(
    db: SupportSQLiteDatabase,
    tableName: String,
    projection: String = "*",
    block: (Cursor, Array<String>) -> Unit
) {
    LegacyRowPager(db, tableName, projection).forEach(block)
}

private class LegacyRowPager(
    private val db: SupportSQLiteDatabase,
    private val tableName: String,
    private val projection: String
) {
    private var useRowId = true
    private var lastRowId: Long? = null
    private var offset = 0
    private var processedRows = 0

    fun forEach(block: (Cursor, Array<String>) -> Unit) {
        while (true) {
            val rowsInBatch = readBatch(block)
            processedRows += rowsInBatch
            if (rowsInBatch < LEGACY_MIGRATION_BATCH_SIZE) return
            if (!useRowId) offset += rowsInBatch
        }
    }

    private fun readBatch(block: (Cursor, Array<String>) -> Unit): Int {
        return queryCursor().use { cursor ->
            val rowIdIndex = if (useRowId) {
                cursor.getColumnIndex(LEGACY_ROW_ID_ALIAS)
            } else {
                -1
            }
            val columnNames = cursor.columnNames
            var rows = 0
            while (cursor.moveToNext()) {
                rows += 1
                if (rowIdIndex >= 0) lastRowId = cursor.getLong(rowIdIndex)
                block(cursor, columnNames)
            }
            rows
        }
    }

    private fun queryCursor(): Cursor {
        return try {
            val query = batchQuery()
            if (useRowId && lastRowId != null) db.query(query, arrayOf(lastRowId.toString()))
            else db.query(query)
        } catch (error: Exception) {
            if (!useRowId) throw error
            // 少数旧库可能没有 ROWID，退回兼容分页
            useRowId = false
            lastRowId = null
            offset = processedRows
            db.query(batchQuery())
        }
    }

    private fun batchQuery(): String = when {
        !useRowId -> "SELECT $projection FROM `$tableName` " +
            "LIMIT $LEGACY_MIGRATION_BATCH_SIZE OFFSET $offset"
        lastRowId == null -> "SELECT rowid AS `$LEGACY_ROW_ID_ALIAS`, $projection " +
            "FROM `$tableName` ORDER BY rowid ASC LIMIT $LEGACY_MIGRATION_BATCH_SIZE"
        else -> "SELECT rowid AS `$LEGACY_ROW_ID_ALIAS`, $projection " +
            "FROM `$tableName` WHERE rowid > ? ORDER BY rowid ASC LIMIT $LEGACY_MIGRATION_BATCH_SIZE"
    }
}

internal const val LEGACY_MIGRATION_BATCH_SIZE = 64

internal const val LEGACY_ROW_ID_ALIAS = "__neriplayer_migration_rowid"
