package moe.ouom.neriplayer.data.local.database.store

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

class DownloadIndexRoomStoreStorageStatsTest {
    private val statements = mutableListOf<String>()
    private val responses = mutableMapOf<String, () -> Cursor>()
    private val store: DownloadIndexRoomStore

    init {
        val sqlite = mock(SupportSQLiteDatabase::class.java)
        doAnswer { call ->
            val sql = call.getArgument<String>(0)
            statements.add(sql)
            checkNotNull(responses[sql]) { "Unexpected SQL: $sql" }()
        }.`when`(sqlite).query(anyString())
        val helper = mock(SupportSQLiteOpenHelper::class.java)
        doReturn(sqlite).`when`(helper).readableDatabase
        val database = mock(NeriUserDataDatabase::class.java)
        doReturn(helper).`when`(database).openHelper
        store = DownloadIndexRoomStore(database)
    }

    @Test
    fun `an index without records reports empty storage without sizing pages`() {
        respond(TABLE_INFO_OPERATIONS) { rows(listOf("cid", "name"), listOf(0L, "operation_id")) }
        respond(TABLE_INFO_LIBRARY) { rows(listOf("cid", "name")) }
        respond(countOperations("operation_id")) { rows(listOf("count", "bytes"), listOf(0L, 0L)) }

        assertEquals(DownloadIndexStorageStats.Empty, store.storageStats())
        assertEquals(listOf(TABLE_INFO_OPERATIONS, countOperations("operation_id"), TABLE_INFO_LIBRARY), statements)
    }

    @Test
    fun `allocated bytes come from dbstat when it reports pages`() {
        stageTables(operations = 3L to 300L, library = 2L to 120L)
        respond(DBSTAT) { rows(listOf("bytes"), listOf(12_288L)) }

        assertEquals(DownloadIndexStorageStats(databaseRecordCount = 5, allocatedPageBytes = 12_288), store.storageStats())
        assertFalse(PAGE_SIZE in statements)
    }

    @Test
    fun `missing or empty dbstat falls back to the payload rounded up to whole pages`() {
        stageTables(operations = 3L to 5_000L, library = 0L to 0L)
        respond(PAGE_SIZE) { rows(listOf("page_size"), listOf(4_096L)) }
        respond(DBSTAT) { throw IllegalStateException("no such table: dbstat") }

        assertEquals(DownloadIndexStorageStats(databaseRecordCount = 3, allocatedPageBytes = 8_192), store.storageStats())

        stageTables(operations = 1L to 10L, library = 0L to 0L)
        respond(DBSTAT) { rows(listOf("bytes"), listOf(0L)) }

        assertEquals(DownloadIndexStorageStats(databaseRecordCount = 1, allocatedPageBytes = 4_096), store.storageStats())
    }

    @Test
    fun `record counts beyond int range are capped and column names are quoted`() {
        respond(TABLE_INFO_OPERATIONS) { rows(listOf("cid", "name"), listOf(0L, "we\"ird")) }
        respond(TABLE_INFO_LIBRARY) { rows(listOf("cid", "name"), listOf(0L, "item_id")) }
        respond(countOperations("we\"\"ird")) { rows(listOf("count", "bytes"), listOf(2_000_000_000L, 64L)) }
        respond(countLibrary("item_id")) { rows(listOf("count", "bytes"), listOf(2_000_000_000L, 64L)) }
        respond(DBSTAT) { rows(listOf("bytes"), listOf(4_096L)) }

        assertEquals(DownloadIndexStorageStats(databaseRecordCount = Int.MAX_VALUE, allocatedPageBytes = 4_096), store.storageStats())
    }

    private fun stageTables(operations: Pair<Long, Long>, library: Pair<Long, Long>) {
        respond(TABLE_INFO_OPERATIONS) { rows(listOf("cid", "name"), listOf(0L, "operation_id"), listOf(1L, "payload")) }
        respond(TABLE_INFO_LIBRARY) { rows(listOf("cid", "name"), listOf(0L, "item_id")) }
        respond(countOperations("operation_id", "payload")) { rows(listOf("count", "bytes"), operations.toList()) }
        respond(countLibrary("item_id")) { rows(listOf("count", "bytes"), library.toList()) }
    }

    private fun respond(sql: String, cursor: () -> Cursor) {
        responses[sql] = cursor
    }

    /** Cursor over in-memory [rows] whose cells are read by column index. */
    private fun rows(columns: List<String>, vararg rows: List<Any>): Cursor {
        val cursor = mock(Cursor::class.java)
        var position = -1
        doAnswer { position = 0; rows.isNotEmpty() }.`when`(cursor).moveToFirst()
        doAnswer { position += 1; position < rows.size }.`when`(cursor).moveToNext()
        doAnswer { rows[position][it.getArgument(0)] as Long }.`when`(cursor).getLong(anyInt())
        doAnswer { rows[position][it.getArgument(0)] as String }.`when`(cursor).getString(anyInt())
        doAnswer { columns.indexOf(it.getArgument(0)) }.`when`(cursor).getColumnIndexOrThrow(anyString())
        return cursor
    }

    private companion object {
        const val TABLE_INFO_OPERATIONS = "PRAGMA table_info('download_operation')"
        const val TABLE_INFO_LIBRARY = "PRAGMA table_info('managed_library_item')"
        const val PAGE_SIZE = "PRAGMA page_size"
        const val DBSTAT = "SELECT COALESCE(SUM(pgsize), 0) FROM dbstat WHERE name IN (SELECT name FROM sqlite_master " +
            "WHERE type IN ('table', 'index') AND tbl_name IN ('download_operation','managed_library_item'))"

        fun countOperations(vararg columns: String) = countSql("download_operation", columns)

        fun countLibrary(vararg columns: String) = countSql("managed_library_item", columns)

        private fun countSql(table: String, columns: Array<out String>): String {
            val payload = columns.joinToString(" + ") { "COALESCE(length(CAST(\"$it\" AS TEXT)), 0)" }
            return "SELECT COUNT(*), COALESCE(SUM(24 + $payload), 0) FROM \"$table\""
        }
    }
}
