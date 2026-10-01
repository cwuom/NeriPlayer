package moe.ouom.neriplayer.data.local.database.migration.legacy

import android.database.Cursor
import org.json.JSONObject

internal fun rowToJson(cursor: Cursor, columnNames: Array<String>): JSONObject {
    val rowIdIndex = cursor.getColumnIndex(LEGACY_ROW_ID_ALIAS)
    return JSONObject().apply {
        columnNames.forEachIndexed { index, columnName ->
            if (index != rowIdIndex && columnName != LEGACY_ROW_ID_ALIAS) {
                put(columnName, cursorValue(cursor, index))
            }
        }
    }
}

internal fun cursorValue(cursor: Cursor, index: Int): Any {
    if (cursor.isNull(index)) return JSONObject.NULL
    return when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> String(cursor.getBlob(index), Charsets.ISO_8859_1)
        else -> cursor.getString(index)
    }
}
