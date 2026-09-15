package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.METADATA_SUFFIX
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootResolver
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyDownloadUpgradeCoordinatorTest {
    @Test
    fun cancelledMarkerWithoutPendingOperationDoesNotCreateSyntheticJournalRow() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TABLE legacy_download_upgrade_payload (
                  stable_key TEXT NOT NULL PRIMARY KEY,
                  payload_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO legacy_download_upgrade_payload (stable_key, payload_json)
                VALUES (
                  '7|netease|',
                  '{"stableKey":"7|netease|","download_cancelled_key":' ||
                  '{"stable_key":"7|netease|","cancelled_at_ms":10}}'
                )
                """.trimIndent()
            )

            val result = LegacyDownloadUpgradeCoordinator(context, database).execute()

            assertTrue(database.downloadOperationDao().findAll().isEmpty())
            assertTrue(result.isComplete)
        } finally {
            database.close()
        }
    }

    @Test
    fun unresolvedLegacyConflictMovesToQuarantineForExplicitResolution() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TABLE legacy_download_upgrade_payload (
                  stable_key TEXT NOT NULL PRIMARY KEY,
                  payload_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO legacy_download_upgrade_payload (stable_key, payload_json)
                VALUES ('8|netease|', '{"stableKey":"8|netease|",' ||
                  '"legacyConflicts":[{"reason":"SAME_STABLE_KEY_DIFFERENT_BYTES"}]}')
                """.trimIndent()
            )

            val result = LegacyDownloadUpgradeCoordinator(context, database).execute()

            assertTrue(database.downloadOperationDao().findAll().isEmpty())
            assertTrue(result.isComplete)
            assertEquals(0, result.rowsPending)
            assertEquals(1, result.rowsQuarantined)
            assertFalse(
                database.openHelper.writableDatabase.query(
                    "SELECT 1 FROM sqlite_master " +
                        "WHERE type = 'table' " +
                        "AND name = 'legacy_download_upgrade_payload'"
                ).use { it.moveToFirst() }
            )
            assertTrue(
                database.openHelper.writableDatabase.query(
                    "SELECT 1 FROM legacy_download_upgrade_quarantine " +
                        "WHERE stable_key = '8|netease|' " +
                        "AND reason = 'CONFLICT' " +
                        "AND payload_json LIKE '%SAME_STABLE_KEY_DIFFERENT_BYTES%'"
                ).use { it.moveToFirst() }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun unresolvedFallbackIdentityMovesOutOfTheHotUpgradeQueueWithoutDataLoss() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TABLE legacy_download_upgrade_payload (
                  stable_key TEXT NOT NULL PRIMARY KEY,
                  payload_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO legacy_download_upgrade_payload (stable_key, payload_json)
                VALUES (
                  'legacy:download_snapshot_metadata:root_key=root|audio_name=orphan.flac',
                  '{"stableKey":"legacy:download_snapshot_metadata:root_key=root|audio_name=orphan.flac",' ||
                  '"download_snapshot_metadata":{"audio_name":"orphan.flac"}}'
                )
                """.trimIndent()
            )

            val result = LegacyDownloadUpgradeCoordinator(context, database).execute()

            assertTrue(result.isComplete)
            assertEquals(0, result.rowsPending)
            assertEquals(1, result.rowsQuarantined)
            assertTrue(
                database.openHelper.writableDatabase.query(
                    "SELECT 1 FROM legacy_download_upgrade_quarantine " +
                        "WHERE stable_key LIKE 'legacy:download_snapshot_metadata:%' " +
                        "AND payload_json LIKE '%orphan.flac%'"
                ).use { it.moveToFirst() }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun pendingLegacyOperationUsesTheInjectedDatabase() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            database.openHelper.writableDatabase.execSQL(
                """
                CREATE TABLE legacy_download_upgrade_payload (
                  stable_key TEXT NOT NULL PRIMARY KEY,
                  payload_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO legacy_download_upgrade_payload (stable_key, payload_json)
                VALUES (
                  '11|netease|',
                  '{"stableKey":"11|netease|","download_pending_queue":' ||
                  '{"stable_key":"11|netease|","name":"Pending","artist":"Artist",' ||
                  '"queue_order":3}}'
                )
                """.trimIndent()
            )

            val result = LegacyDownloadUpgradeCoordinator(context, database).execute()

            assertTrue(result.isComplete)
            assertEquals(1, database.downloadOperationDao().findAll().size)
            assertEquals(
                "11|netease|",
                database.downloadOperationDao().findAll().single().stableKey
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun unresolvedProjectionQuarantineHandlesTwoThousandRowsWithinTenSeconds() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val sqliteDatabase = database.openHelper.writableDatabase
            sqliteDatabase.execSQL(
                """
                CREATE TABLE legacy_download_upgrade_payload (
                  stable_key TEXT NOT NULL PRIMARY KEY,
                  payload_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            sqliteDatabase.beginTransaction()
            try {
                repeat(2_200) { index ->
                    val stableKey = "legacy:download_snapshot_entry:row=$index"
                    sqliteDatabase.execSQL(
                        "INSERT INTO legacy_download_upgrade_payload " +
                            "(stable_key, payload_json) VALUES (?, ?)",
                        arrayOf(
                            stableKey,
                            "{\"stableKey\":\"$stableKey\"," +
                                "\"download_snapshot_entries\":[]}"
                        )
                    )
                }
                sqliteDatabase.setTransactionSuccessful()
            } finally {
                sqliteDatabase.endTransaction()
            }

            val startedAtNanos = System.nanoTime()
            val result = LegacyDownloadUpgradeCoordinator(context, database).execute()
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L

            assertTrue(result.isComplete)
            assertEquals(2_200, result.rowsQuarantined)
            assertTrue("elapsedMs=$elapsedMs", elapsedMs < 10_000L)
        } finally {
            database.close()
        }
    }

    @Test
    fun publishedSnapshotRequeuesOnlyUniquelyIdentifiedQuarantinedSong() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val sqliteDatabase = database.openHelper.writableDatabase
            sqliteDatabase.execSQL(
                """
                CREATE TABLE legacy_download_upgrade_quarantine (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    stable_key TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    quarantined_at_ms INTEGER NOT NULL,
                    UNIQUE(stable_key, payload_json)
                )
                """.trimIndent()
            )
            val unresolvedKey = "legacy:download_snapshot_metadata:audio_name=song.mp3"
            val payload =
                "{\"stableKey\":\"$unresolvedKey\",\"audioFileName\":\"song.mp3\"}"
            sqliteDatabase.execSQL(
                "INSERT INTO legacy_download_upgrade_quarantine " +
                    "(stable_key, payload_json, reason, quarantined_at_ms) " +
                    "VALUES (?, ?, 'UNTRUSTWORTHY_STABLE_IDENTITY', 1)",
                arrayOf(unresolvedKey, payload)
            )
            val audio = ManagedDownloadStorage.StoredEntry(
                name = "song.mp3",
                reference = "content://current/song.mp3",
                mediaUri = "content://current/song.mp3",
                localFilePath = null,
                sizeBytes = 64L,
                lastModifiedMs = 1L
            )
            val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
                stableKey = "42|netease|",
                audioFileName = audio.name
            )
            val snapshot = ManagedDownloadStorage.DownloadLibrarySnapshot(
                audioEntries = listOf(audio),
                audioEntriesByLookupKey = emptyMap(),
                metadataEntriesByAudioName = emptyMap(),
                metadataByAudioName = mapOf(audio.name to metadata),
                audioEntriesWithoutMetadata = emptyList(),
                audioEntriesByStableKey = mapOf("42|netease|" to listOf(audio)),
                audioEntriesBySongId = emptyMap(),
                audioEntriesByMediaUri = emptyMap(),
                audioEntriesByRemoteTrackKey = emptyMap(),
                coverEntriesByName = emptyMap(),
                lyricEntriesByName = emptyMap(),
                knownReferences = setOf(audio.reference)
            )

            val restored = LegacyDownloadUpgradeCoordinator(context, database)
                .requeueResolvableQuarantinedRows(snapshot)

            assertEquals(1, restored)
            assertTrue(
                sqliteDatabase.query(
                    "SELECT payload_json FROM legacy_download_upgrade_payload " +
                        "WHERE stable_key = '42|netease|'"
                ).use { cursor ->
                    cursor.moveToFirst() &&
                        cursor.getString(0).contains("\"legacyQuarantineStableKey\"")
                }
            )
            assertFalse(
                sqliteDatabase.query(
                    "SELECT 1 FROM legacy_download_upgrade_quarantine LIMIT 1"
                ).use { cursor -> cursor.moveToFirst() }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun completedPayloadRowsAreDeletedInBatchesWithBoundedProgress() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val sqliteDatabase = database.openHelper.writableDatabase
            sqliteDatabase.execSQL(
                """
                CREATE TABLE legacy_download_upgrade_payload (
                  stable_key TEXT NOT NULL PRIMARY KEY,
                  payload_json TEXT NOT NULL
                )
                """.trimIndent()
            )
            sqliteDatabase.beginTransaction()
            try {
                repeat(130) { index ->
                    val stableKey = "$index|netease|"
                    val payload =
                        "{\"stableKey\":\"$stableKey\",\"download_cancelled_key\":" +
                            "{\"stable_key\":\"$stableKey\",\"cancelled_at_ms\":10}}"
                    sqliteDatabase.execSQL(
                        "INSERT INTO legacy_download_upgrade_payload " +
                            "(stable_key, payload_json) VALUES (?, ?)",
                        arrayOf(stableKey, payload)
                    )
                }
                sqliteDatabase.setTransactionSuccessful()
            } finally {
                sqliteDatabase.endTransaction()
            }
            val progress = mutableListOf<Pair<Int, Int>>()

            val coordinator = LegacyDownloadUpgradeCoordinator(context, database)
            val result = coordinator.execute { processed, total ->
                progress += processed to total
            }

            assertTrue(result.isComplete)
            assertEquals(130, result.rowsCompleted)
            assertEquals(
                listOf(
                    16 to 130,
                    32 to 130,
                    48 to 130,
                    64 to 130,
                    80 to 130,
                    96 to 130,
                    112 to 130,
                    128 to 130,
                    130 to 130
                ),
                progress
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun oneThousandMetadataPayloadsUpgradeWithinTenSeconds() = runTest {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createStorageFixture(baseContext)
        val database = Room.inMemoryDatabaseBuilder(
            baseContext,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            seedMetadataUpgrade(
                database = database,
                root = fixture.managedRoot,
                itemCount = METADATA_UPGRADE_BENCHMARK_SIZE
            )

            val startedAtNanos = System.nanoTime()
            val result = LegacyDownloadUpgradeCoordinator(fixture.context, database).execute()
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L

            assertTrue(result.isComplete)
            assertEquals(METADATA_UPGRADE_BENCHMARK_SIZE, result.rowsCompleted)
            assertEquals(
                METADATA_UPGRADE_BENCHMARK_SIZE,
                metadataFiles(fixture.managedRoot).size
            )
            assertTrue("elapsedMs=$elapsedMs", elapsedMs < METADATA_UPGRADE_BUDGET_MS)
            assertMetadataMatchesStableKeys(
                root = fixture.managedRoot,
                itemCount = METADATA_UPGRADE_BENCHMARK_SIZE
            )
        } finally {
            database.close()
            fixture.close()
        }
    }

    @Test
    fun cancelledBatchResumesFromDurablePayloadAfterDatabaseReopen() = runTest {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val fixture = createStorageFixture(baseContext)
        val databaseName = "legacy-upgrade-resume-${UUID.randomUUID()}.db"
        var database = openFileBackedDatabase(baseContext, databaseName)
        try {
            seedMetadataUpgrade(
                database = database,
                root = fixture.managedRoot,
                itemCount = PROCESS_DEATH_FIXTURE_SIZE
            )
            var cancellationObserved = false
            try {
                LegacyDownloadUpgradeCoordinator(fixture.context, database).execute {
                    processed,
                    _ ->
                    if (processed >= 16) {
                        throw CancellationException("simulated process stop")
                    }
                }
            } catch (_: CancellationException) {
                cancellationObserved = true
            }

            assertTrue(cancellationObserved)
            assertEquals(PROCESS_DEATH_FIXTURE_SIZE, payloadRowCount(database))
            database.close()
            database = openFileBackedDatabase(baseContext, databaseName)

            val resumed = LegacyDownloadUpgradeCoordinator(fixture.context, database).execute()

            assertTrue(resumed.isComplete)
            assertEquals(PROCESS_DEATH_FIXTURE_SIZE, resumed.rowsCompleted)
            assertFalse(payloadTableExists(database))
            assertMetadataMatchesStableKeys(
                root = fixture.managedRoot,
                itemCount = PROCESS_DEATH_FIXTURE_SIZE
            )
        } finally {
            database.close()
            baseContext.deleteDatabase(databaseName)
            fixture.close()
        }
    }

    private fun createStorageFixture(baseContext: Context): StorageFixture {
        val sandbox = File(
            baseContext.cacheDir,
            "legacy-upgrade-storage-${UUID.randomUUID()}"
        ).apply { mkdirs() }
        val context = IsolatedStorageContext(baseContext, sandbox)
        ManagedDownloadStorage.primeSettings(
            directoryUri = null,
            directoryLabel = null
        )
        val managedRoot = ManagedDownloadRootResolver.defaultRootDirectory(context).apply {
            mkdirs()
        }
        return StorageFixture(
            context = context,
            sandbox = sandbox,
            managedRoot = managedRoot
        )
    }

    private fun seedMetadataUpgrade(
        database: NeriUserDataDatabase,
        root: File,
        itemCount: Int
    ) {
        val sqliteDatabase = database.openHelper.writableDatabase
        sqliteDatabase.execSQL(
            """
            CREATE TABLE legacy_download_upgrade_payload (
              stable_key TEXT NOT NULL PRIMARY KEY,
              payload_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        val insert = sqliteDatabase.compileStatement(
            "INSERT INTO legacy_download_upgrade_payload " +
                "(stable_key, payload_json) VALUES (?, ?)"
        )
        sqliteDatabase.beginTransaction()
        try {
            repeat(itemCount) { index ->
                val stableKey = stableKey(index)
                val audioName = audioName(index)
                File(root, audioName).writeBytes(byteArrayOf((index % 251).toByte()))
                val payload = JSONObject()
                    .put("stableKey", stableKey)
                    .put("audioFileName", audioName)
                    .put("name", "Legacy song $index")
                    .put("artist", "Legacy artist")
                    .put("source", "netease")
                    .put("downloadTime", 10_000L + index)
                    .put(
                        "downloaded_song_catalog",
                        JSONObject()
                            .put("stable_key", stableKey)
                            .put("audio_file_name", audioName)
                    )
                insert.clearBindings()
                insert.bindString(1, stableKey)
                insert.bindString(2, payload.toString())
                insert.executeInsert()
            }
            sqliteDatabase.setTransactionSuccessful()
        } finally {
            sqliteDatabase.endTransaction()
        }
    }

    private fun openFileBackedDatabase(
        context: Context,
        databaseName: String
    ): NeriUserDataDatabase {
        return Room.databaseBuilder(
            context,
            NeriUserDataDatabase::class.java,
            databaseName
        ).allowMainThreadQueries().build()
    }

    private fun payloadRowCount(database: NeriUserDataDatabase): Int {
        return database.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM legacy_download_upgrade_payload"
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    private fun payloadTableExists(database: NeriUserDataDatabase): Boolean {
        return database.openHelper.writableDatabase.query(
            "SELECT 1 FROM sqlite_master " +
                "WHERE type = 'table' AND name = 'legacy_download_upgrade_payload'"
        ).use { cursor -> cursor.moveToFirst() }
    }

    private fun assertMetadataMatchesStableKeys(root: File, itemCount: Int) {
        repeat(itemCount) { index ->
            val metadataFile = File(root, audioName(index) + METADATA_SUFFIX)
            assertTrue(metadataFile.isFile)
            assertEquals(
                stableKey(index),
                JSONObject(metadataFile.readText()).getString("stableKey")
            )
        }
    }

    private fun metadataFiles(root: File): List<File> {
        return root.listFiles()
            ?.filter { file -> file.isFile && file.name.endsWith(METADATA_SUFFIX) }
            .orEmpty()
    }

    private fun stableKey(index: Int): String = "${index + 1}|netease|"

    private fun audioName(index: Int): String = "legacy-${index.toString().padStart(4, '0')}.mp3"

    private data class StorageFixture(
        val context: Context,
        val sandbox: File,
        val managedRoot: File
    ) {
        fun close() {
            ManagedDownloadStorage.primeSettings(
                directoryUri = null,
                directoryLabel = null
            )
            sandbox.deleteRecursively()
        }
    }

    private class IsolatedStorageContext(
        baseContext: Context,
        private val sandbox: File
    ) : ContextWrapper(baseContext) {
        override fun getApplicationContext(): Context = this

        override fun getExternalFilesDir(type: String?): File {
            return File(sandbox, "external/${type ?: "root"}").apply { mkdirs() }
        }

        override fun getFilesDir(): File {
            return File(sandbox, "files").apply { mkdirs() }
        }

        override fun getCacheDir(): File {
            return File(sandbox, "cache").apply { mkdirs() }
        }
    }

    private companion object {
        const val METADATA_UPGRADE_BENCHMARK_SIZE = 1_000
        const val PROCESS_DEATH_FIXTURE_SIZE = 128
        const val METADATA_UPGRADE_BUDGET_MS = 10_000L
    }
}
