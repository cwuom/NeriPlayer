package moe.ouom.neriplayer.core.player.privileged.coloros

import android.database.Cursor

internal object ColorOsProviderRows {
    fun read(cursor: Cursor?): ColorOsRusRow? {
        if (cursor == null) throw ColorOsConfigException(ColorOsConfigStatus.ERROR, "provider_null_cursor")
        return cursor.use {
            val version = requiredColumn(it, "version")
            val xml = requiredColumn(it, "xml")
            if (it.count > 1) throw ColorOsConfigException(ColorOsConfigStatus.CONFLICT, "provider_duplicate_rows")
            if (!it.moveToFirst()) return@use null
            val md5 = it.getColumnIndex("md5")
            val isOpen = it.getColumnIndex("isOpen")
            ColorOsRusRow(
                scalar(it, version, false, true)!!,
                scalar(it, xml, false, false)!!,
                md5 >= 0, if (md5 >= 0) scalar(it, md5, true, false) else null,
                isOpen >= 0, if (isOpen >= 0) scalar(it, isOpen, true, true) else null
            ).also(ColorOsTransportProtocol::rowJson)
        }
    }

    fun selection(expected: ColorOsRusRow): Pair<String, Array<String>> {
        val args = mutableListOf(ColorOsTransportProtocol.FILTER, expected.version, expected.xml)
        val clauses = mutableListOf("filtername = ?", "version = ?", "xml = ?")
        fun optional(column: String, present: Boolean, value: String?) {
            if (!present) return
            if (value == null) clauses += "$column IS NULL" else { clauses += "$column = ?"; args += value }
        }
        optional("md5", expected.hasMd5, expected.md5)
        optional("isOpen", expected.hasIsOpen, expected.isOpen)
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    private fun requiredColumn(cursor: Cursor, name: String): Int = cursor.getColumnIndex(name).also {
        if (it < 0) throw ColorOsConfigException(ColorOsConfigStatus.UNSUPPORTED, "provider_missing_column")
    }

    private fun scalar(cursor: Cursor, column: Int, nullable: Boolean, integer: Boolean): String? = when (cursor.getType(column)) {
        Cursor.FIELD_TYPE_NULL -> if (nullable) null else invalidType()
        Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
        Cursor.FIELD_TYPE_INTEGER -> if (integer) cursor.getLong(column).toString() else invalidType()
        else -> invalidType()
    }

    private fun invalidType(): Nothing = throw ColorOsConfigException(ColorOsConfigStatus.UNSUPPORTED, "provider_column_type")
}
