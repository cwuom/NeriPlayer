package moe.ouom.neriplayer.data.local.database.migration.legacy

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

internal class LegacyMigrationDatabase(
    val tables: Map<String, List<Map<String, Any?>>> = emptyMap(),
    val columns: Map<String, List<String>> = emptyMap()
) {
    val database: SupportSQLiteDatabase = mock(SupportSQLiteDatabase::class.java)
    val payloads = linkedMapOf<String, String>()
    val inserts = mutableListOf<Int>()
    val queries = mutableListOf<String>()
    val statements = mutableListOf<String>()
    var writeFailure: RuntimeException? = null
    var queryFailure: ((String) -> RuntimeException?)? = null

    init {
        doAnswer { query(it.getArgument(0), emptyArray()) }.`when`(database).query(anyString())
        doAnswer { query(it.getArgument(0), it.getArgument(1)) }.`when`(database)
            .query(anyString(), any<Array<Any?>>() ?: emptyArray())
        doAnswer { statements += it.getArgument<String>(0); null }.`when`(database).execSQL(anyString())
        doAnswer { invocation ->
            val sql = invocation.getArgument<String>(0)
            val statement = mock(SupportSQLiteStatement::class.java)
            val bindings = sortedMapOf<Int, String>()
            doAnswer { bindings[it.getArgument(0)] = it.getArgument(1); null }.`when`(statement)
                .bindString(anyInt(), anyString())
            doAnswer {
                writeFailure?.let { throw it }
                check(sql.startsWith("INSERT OR REPLACE INTO `legacy_download_upgrade_payload`"))
                val rows = bindings.values.chunked(2)
                rows.forEach { (key, json) -> payloads[key] = json }
                inserts += rows.size
                1L
            }.`when`(statement).executeInsert()
            statement
        }.`when`(database).compileStatement(anyString())
    }

    private fun query(sql: String, args: Array<out Any?>): android.database.Cursor {
        queries += sql
        queryFailure?.invoke(sql)?.let { throw it }
        if (sql.contains("sqlite_master")) {
            return legacyCursor(if (args.first() in tables) listOf(mapOf("exists" to 1L)) else emptyList())
        }
        if (sql.startsWith("PRAGMA table_info")) {
            val table = sql.substringAfter('`').substringBefore('`')
            val names = columns[table] ?: tables[table].orEmpty().flatMap { it.keys }.distinct()
            return legacyCursor(names.map { mapOf("name" to it) })
        }
        if (sql.startsWith("SELECT `payload_json`")) {
            val payload = payloads[args.first()]
            return legacyCursor(if (payload == null) emptyList() else listOf(mapOf("payload_json" to payload)))
        }
        val table = sql.substringAfter("FROM `").substringBefore('`')
        check(table in tables) { "Unexpected query: $sql" }
        val rows = tables.getValue(table)
        val offset = if (sql.contains("OFFSET")) sql.substringAfter("OFFSET ").toInt()
            else args.firstOrNull()?.toString()?.toInt() ?: 0
        return legacyCursor(rows.drop(offset).take(64).mapIndexed { index, row ->
            if (sql.contains("rowid AS")) linkedMapOf<String, Any?>(LEGACY_ROW_ID_ALIAS to (offset + index + 1L)) + row
            else row
        })
    }
}
