package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
    fun unresolvedLegacyConflictRemainsPendingForExplicitResolution() = runTest {
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
            assertFalse(result.isComplete)
            assertTrue(
                database.openHelper.writableDatabase.query(
                    "SELECT 1 FROM legacy_download_upgrade_payload " +
                        "WHERE stable_key = '8|netease|'"
                ).use { it.moveToFirst() }
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun unresolvedFallbackIdentityRemainsPendingWithoutNameBasedBinding() = runTest {
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

            assertFalse(result.isComplete)
            assertEquals(1, result.rowsPending)
            assertTrue(
                database.openHelper.writableDatabase.query(
                    "SELECT 1 FROM legacy_download_upgrade_payload " +
                        "WHERE stable_key LIKE 'legacy:download_snapshot_metadata:%'"
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
}
