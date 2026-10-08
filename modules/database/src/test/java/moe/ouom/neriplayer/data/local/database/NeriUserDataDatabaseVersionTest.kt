package moe.ouom.neriplayer.data.local.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeriUserDataDatabaseVersionTest {
    private lateinit var directory: File
    private val supported = NeriUserDataDatabase.FINAL_DB_VERSION

    @Before fun createDirectory() {
        directory = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "version-check").apply { mkdirs() }
    }

    @After fun deleteFiles() {
        directory.deleteRecursively()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(NeriUserDataDatabase.DATABASE_NAME)
    }

    @Test fun `current and older versions can be opened by this app`() {
        assertEquals(DatabaseVersionState.Compatible(supported, supported), databaseVersionState(databaseAt("current.db", supported)))
        assertEquals(DatabaseVersionState.Compatible(15, supported), databaseVersionState(databaseAt("older.db", 15)))
    }

    @Test fun `version from a newer app is reported and the file keeps its contents`() {
        val file = databaseAt("newer.db", supported + 1)
        val before = file.readBytes()

        assertEquals(DatabaseVersionState.NewerThanApp(supported + 1, supported), databaseVersionState(file))

        assertArrayEquals(before, file.readBytes())
    }

    @Test fun `version bump still waiting in the write ahead log is seen`() {
        val file = databaseAt("wal.db", supported)
        val writer = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            writer.enableWriteAheadLogging()
            writer.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
            writer.version = supported + 1
            assertEquals(supported, headerUserVersion(file))

            assertEquals(DatabaseVersionState.NewerThanApp(supported + 1, supported), databaseVersionState(file))
        } finally {
            writer.close()
        }
    }

    @Test fun `corrupt file is reported unreadable and never deleted`() {
        val file = File(directory, "corrupt.db").apply { writeBytes(ByteArray(4096) { 0x5A }) }

        val state = databaseVersionState(file)

        assertTrue(state is DatabaseVersionState.Unreadable)
        assertTrue(file.exists())
        assertArrayEquals(ByteArray(4096) { 0x5A }, file.readBytes())
    }

    @Test fun `app check reads the user database in the app database directory`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath(NeriUserDataDatabase.DATABASE_NAME).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.version = supported + 2 }

        assertEquals(DatabaseVersionState.NewerThanApp(supported + 2, supported), NeriUserDataDatabase.checkVersion(context))
    }

    private fun databaseAt(name: String, version: Int): File = File(directory, name).also { file ->
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL("CREATE TABLE marker(id INTEGER PRIMARY KEY)")
            database.version = version
        }
    }

    private fun headerUserVersion(file: File): Int = RandomAccessFile(file, "r").use { header ->
        header.seek(60)
        header.readInt()
    }
}
