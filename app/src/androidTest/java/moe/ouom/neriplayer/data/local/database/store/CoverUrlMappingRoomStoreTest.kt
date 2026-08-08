package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverUrlMappingRoomStoreTest {
    @Test
    fun importLegacyAndPromoteMakesMappingsRoomPrimary() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val store = CoverUrlMappingRoomStore(database)
            val mappings = mapOf(
                "file:///covers/local.jpg" to "https://example.com/covers/local.jpg"
            )

            assertNull(store.readIfRoomPrimary())
            store.importLegacyAndPromote(mappings, cleanupEligible = true, now = 100L)
            store.importLegacyAndPromote(emptyMap(), cleanupEligible = true, now = 200L)

            assertEquals(mappings, store.readIfRoomPrimary())
            val state = database.syncMetadataDao().getMigrationMetadata(
                CoverUrlMappingRoomStore.CUTOVER_STATE_METADATA_KEY
            )
            assertEquals(CoverUrlMappingRoomStore.ROOM_PRIMARY_STATE, state?.value)
        } finally {
            database.close()
        }
    }

    @Test
    fun upsertKeepsLegacyImportFailureBlockedForCleanup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val store = CoverUrlMappingRoomStore(database)
            store.importLegacyAndPromote(
                mappings = emptyMap(),
                cleanupEligible = false,
                now = 100L
            )

            store.upsert(
                localUrl = "file:///covers/local.jpg",
                networkUrl = "https://example.com/covers/local.jpg",
                cleanupEligible = true,
                now = 200L
            )

            assertEquals(
                mapOf("file:///covers/local.jpg" to "https://example.com/covers/local.jpg"),
                store.readIfRoomPrimary()
            )
            val state = database.syncMetadataDao().getMigrationMetadata(
                CoverUrlMappingRoomStore.CUTOVER_STATE_METADATA_KEY
            )
            assertEquals(
                CoverUrlMappingRoomStore.ROOM_PRIMARY_WITH_LEGACY_IMPORT_FAILURE_STATE,
                state?.value
            )
        } finally {
            database.close()
        }
    }
}
