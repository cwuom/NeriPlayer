package moe.ouom.neriplayer.core.download.storage.snapshot

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadSnapshotRoomStoreTest {
    @Test
    fun persistAndRestoreKeepsSnapshotIndexesAndRootIsolation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val store = ManagedDownloadSnapshotRoomStore(context, database)
            val snapshot = snapshot()

            assertTrue(store.persist("root-a", snapshot))

            val restored = store.restore(expectedKey = "root-a")
            assertEquals("root-a", restored?.first)
            assertEquals(snapshot.audioEntries, restored?.second?.audioEntries)
            assertEquals(
                snapshot.metadataByAudioName.keys,
                restored?.second?.metadataByAudioName?.keys
            )
            assertEquals(
                snapshot.metadataByAudioName.values.single().stableKey,
                restored?.second?.metadataByAudioName?.values?.single()?.stableKey
            )
            assertEquals(
                snapshot.metadataByAudioName.values.single().name,
                restored?.second?.metadataByAudioName?.values?.single()?.name
            )
            assertEquals(
                snapshot.audioEntriesByRemoteTrackKey,
                restored?.second?.audioEntriesByRemoteTrackKey
            )
            assertNull(store.restore(expectedKey = "root-b"))
        } finally {
            database.close()
        }
    }

    @Test
    fun importsLegacyDiskCacheAndDeletesJsonAfterRoomPromotion() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val cacheFile = ManagedDownloadSnapshotDiskCache.cacheFile(context)
        cacheFile.delete()
        cacheFile.writeText(
            ManagedDownloadSnapshotIndex.serializePayload(
                cacheKey = "root-a",
                snapshot = snapshot()
            ),
            Charsets.UTF_8
        )

        try {
            val store = ManagedDownloadSnapshotRoomStore(context, database)
            val restored = store.restore(expectedKey = "root-a")

            assertEquals("root-a", restored?.first)
            assertFalse(cacheFile.exists())
            assertEquals(
                ManagedDownloadSnapshotRoomStore.ROOM_PRIMARY_STATE,
                database.syncMetadataDao()
                    .getMigrationMetadata(
                        ManagedDownloadSnapshotRoomStore.CUTOVER_STATE_METADATA_KEY
                    )
                    ?.value
            )
        } finally {
            cacheFile.delete()
            database.close()
        }
    }

    private fun snapshot(): ManagedDownloadStorage.DownloadLibrarySnapshot {
        val audioEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Snapshot Song.flac",
            reference = "/music/Artist - Snapshot Song.flac",
            mediaUri = "file:///music/Artist%20-%20Snapshot%20Song.flac",
            localFilePath = "/music/Artist - Snapshot Song.flac",
            sizeBytes = 4096L,
            lastModifiedMs = 100L
        )
        val metadataEntry = ManagedDownloadStorage.StoredEntry(
            name = "Artist - Snapshot Song.flac.npmeta.json",
            reference = "/music/Artist - Snapshot Song.flac.npmeta.json",
            mediaUri = "file:///music/Artist%20-%20Snapshot%20Song.flac.npmeta.json",
            localFilePath = "/music/Artist - Snapshot Song.flac.npmeta.json",
            sizeBytes = 256L,
            lastModifiedMs = 101L
        )
        val metadata = ManagedDownloadStorage.DownloadedAudioMetadata(
            stableKey = "snapshot-stable",
            songId = 55L,
            identityAlbum = "NeteaseAlbum",
            name = "Snapshot Song",
            artist = "Artist",
            mediaUri = "https://example.com/snapshot.flac",
            channelId = "netease",
            audioId = "55",
            durationMs = 180_000L,
            downloadFinalized = true
        )
        return ManagedDownloadSnapshotIndex.compose(
            audioEntries = listOf(audioEntry),
            metadataEntries = listOf(metadataEntry),
            metadataByAudioName = mapOf(audioEntry.name to metadata),
            coverEntries = emptyList(),
            lyricEntries = emptyList()
        )
    }
}
