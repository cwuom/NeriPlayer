package moe.ouom.neriplayer.data.local.database.migration.legacy

import android.database.Cursor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

internal fun legacyCursor(rows: List<Map<String, Any?>>): Cursor {
    val cursor = mock(Cursor::class.java)
    val columns = rows.flatMap { it.keys }.distinct()
    var position = -1
    fun value(index: Int): Any? = rows[position][columns[index]]
    doAnswer { ++position < rows.size }.`when`(cursor).moveToNext()
    doAnswer { position = 0; rows.isNotEmpty() }.`when`(cursor).moveToFirst()
    doAnswer { position }.`when`(cursor).position
    doAnswer { columns.toTypedArray() }.`when`(cursor).columnNames
    doAnswer { columns.indexOf(it.getArgument<String>(0)) }.`when`(cursor).getColumnIndex(anyString())
    doAnswer { value(it.getArgument(0)) == null }.`when`(cursor).isNull(anyInt())
    doAnswer { value(it.getArgument(0))?.toString() }.`when`(cursor).getString(anyInt())
    doAnswer { value(it.getArgument(0)).toString().toLong() }.`when`(cursor).getLong(anyInt())
    doAnswer { value(it.getArgument(0)).toString().toDouble() }.`when`(cursor).getDouble(anyInt())
    doAnswer { value(it.getArgument(0)) as ByteArray }.`when`(cursor).getBlob(anyInt())
    doAnswer {
        when (value(it.getArgument(0))) {
            null -> Cursor.FIELD_TYPE_NULL
            is ByteArray -> Cursor.FIELD_TYPE_BLOB
            is Float, is Double -> Cursor.FIELD_TYPE_FLOAT
            is Number -> Cursor.FIELD_TYPE_INTEGER
            else -> Cursor.FIELD_TYPE_STRING
        }
    }.`when`(cursor).getType(anyInt())
    return cursor
}

internal fun legacyRow(vararg values: Pair<String, Any?>): Cursor =
    legacyCursor(listOf(linkedMapOf(*values))).apply { moveToFirst() }
