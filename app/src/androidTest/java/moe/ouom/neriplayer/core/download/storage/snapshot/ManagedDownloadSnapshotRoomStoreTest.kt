package moe.ouom.neriplayer.core.download.storage.snapshot

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.SNAPSHOT_CACHE_FILE_NAME
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ManagedDownloadSnapshotRoomStoreTest {
    @Test
    fun persistAndRestoreKeepsSnapshotIndexesAndRootIsolation() = runTest {
        val context = isolatedContext()
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
            context.filesDir.deleteRecursively()
        }
    }

    @Test
    fun readsLegacyDiskCacheWithoutDeletingItsOnlyDurableCopy() = runTest {
        val context = isolatedContext()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val cacheFile = File(context.filesDir, SNAPSHOT_CACHE_FILE_NAME)
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
            assertTrue(cacheFile.exists())
            assertEquals(
                snapshot().audioEntries,
                ManagedDownloadSnapshotRoomStore(context, database)
                    .restore(expectedKey = "root-a")?.second?.audioEntries
            )
            assertEquals(
                ManagedDownloadSnapshotRoomStore.DISK_PRIMARY_STATE,
                database.syncMetadataDao()
                    .getMigrationMetadata(
                        ManagedDownloadSnapshotRoomStore.CUTOVER_STATE_METADATA_KEY
                    )
                    ?.value
            )
        } finally {
            cacheFile.delete()
            database.close()
            context.filesDir.deleteRecursively()
        }
    }

    @Test
    fun persistRecreatesAMissingFilesDirectory() = runTest {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            baseContext,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val isolatedRoot = File(
            baseContext.cacheDir,
            "snapshot-room-store-${System.nanoTime()}"
        )
        val isolatedContext = object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = File(isolatedRoot, "files")
        }

        try {
            val store = ManagedDownloadSnapshotRoomStore(isolatedContext, database)

            assertTrue(store.persist("root-a", snapshot()))
            assertTrue(ManagedDownloadSnapshotDiskCache.cacheFile(isolatedContext).isFile)
        } finally {
            database.close()
            isolatedRoot.deleteRecursively()
        }
    }

    private fun isolatedContext(): Context {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(baseContext.cacheDir, "snapshot-room-store-${UUID.randomUUID()}")
        check(directory.mkdirs())
        return object : ContextWrapper(baseContext) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
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
