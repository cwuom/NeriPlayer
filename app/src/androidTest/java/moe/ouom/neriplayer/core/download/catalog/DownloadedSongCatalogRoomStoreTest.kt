package moe.ouom.neriplayer.core.download.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.model.DownloadedSong
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage

@RunWith(AndroidJUnit4::class)
class DownloadedSongCatalogRoomStoreTest {
    @Test
    fun fullSnapshotRemovalSurvivesDatabaseReopenBeforeBackupWrite() = runTest {
        assertRemovalSurvivesDatabaseReopen(useDelta = false)
    }

    @Test
    fun deltaRemovalSurvivesDatabaseReopenBeforeBackupWrite() = runTest {
        assertRemovalSurvivesDatabaseReopen(useDelta = true)
    }

    @Test
    fun removalSurvivesLegacyFallbackAndExplicitReadditionRestoresTheSong() = runTest {
        assertRemovalSurvivesDatabaseReopen(useDelta = true, verifyLegacyFallback = true)
    }

    private suspend fun assertRemovalSurvivesDatabaseReopen(
        useDelta: Boolean,
        verifyLegacyFallback: Boolean = false
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtureId = UUID.randomUUID().toString()
        val databaseName = "catalog-interrupted-$fixtureId.db"
        val cacheFileName = "catalog-interrupted-$fixtureId.json"
        val rootKey = ManagedDownloadStorage.currentSnapshotCacheKey(context)
        fun openDatabase() = Room.databaseBuilder(
            context,
            NeriUserDataDatabase::class.java,
            databaseName
        ).build()
        fun store(database: NeriUserDataDatabase) = DownloadedSongCatalogRoomStore(
            context = context,
            database = database,
            cacheFileName = cacheFileName,
            snapshotCacheKeyProvider = { rootKey },
            loggerTag = "DownloadedSongCatalogRoomStoreTest"
        )
        var database = openDatabase()
        try {
            val removedSong = song("removed", "/music/removed.mp3")
            val retainedSong = song("retained", "/music/retained.mp3").copy(
                id = 2L,
                stableKey = "2|netease|",
                originalLyric = "retained original lyric"
            )
            store(database).persist(listOf(removedSong, retainedSong))
            if (verifyLegacyFallback) {
                File(context.filesDir, cacheFileName).writeText(
                    serializeDownloadedSongsCatalog(
                        cacheKey = rootKey,
                        songs = listOf(removedSong, retainedSong),
                        includeOriginalLyrics = true
                    )
                )
            }

            // 只完成 Room 事务，保留旧备份以重现两次持久写之间的进程退出
            if (useDelta) {
                ManagedLibraryItemRoomStore.applyPreviewDelta(
                    context = context,
                    upserts = emptyList(),
                    removedStableKeys = setOf("1|netease|"),
                    database = database
                )
            } else {
                ManagedLibraryItemRoomStore.replacePreviews(
                    context = context,
                    songs = listOf(retainedSong),
                    database = database
                )
            }
            assertEquals(
                listOf("2|netease|"),
                database.managedLibraryItemDao().findAll(rootKey).map { it.stableKey }
            )
            database.close()
            database = openDatabase()

            val restored = store(database).restore()
            assertEquals(listOf("2|netease|"), restored?.map(DownloadedSong::stableKey))
            assertEquals("retained original lyric", restored?.single()?.originalLyric)
            if (verifyLegacyFallback) {
                store(database).persist(listOf(retainedSong))
                assertTrue(
                    File(context.filesDir, cacheFileName + MANAGED_LIBRARY_CATALOG_BACKUP_SUFFIX)
                        .delete()
                )
                assertEquals(
                    listOf("2|netease|"),
                    store(database).restore()?.map(DownloadedSong::stableKey)
                )
                ManagedLibraryItemRoomStore.upsertPreview(
                    context = context,
                    song = removedSong,
                    database = database
                )
                assertEquals(
                    setOf("1|netease|", "2|netease|"),
                    store(database).restore()?.map(DownloadedSong::stableKey)?.toSet()
                )
            }
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
            listOf(
                cacheFileName,
                cacheFileName + MANAGED_LIBRARY_CATALOG_BACKUP_SUFFIX,
                cacheFileName + CONFIRMED_EMPTY_CATALOG_MARKER_SUFFIX
            ).forEach { name -> File(context.filesDir, name).delete() }
        }
    }

    @Test
    fun fullSnapshotKeepsLeaseProtectedPreviewAndItsBackupMetadata() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val rootKey = ManagedDownloadStorage.currentSnapshotCacheKey(context)
        val cacheFileName = "catalog-leased-${UUID.randomUUID()}.json"
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).build()
        try {
            val store = DownloadedSongCatalogRoomStore(
                context = context,
                database = database,
                cacheFileName = cacheFileName,
                snapshotCacheKeyProvider = { rootKey },
                loggerTag = "DownloadedSongCatalogRoomStoreTest"
            )
            val leasedSong = song("leased", "/music/leased.mp3").copy(
                originalLyric = "leased original lyric"
            )
            store.persist(listOf(leasedSong))
            val row = database.managedLibraryItemDao().findAll(rootKey).single()
            database.managedLibraryItemDao().upsert(row.copy(leaseId = "active-lease"))

            ManagedLibraryItemRoomStore.replacePreviews(context, emptyList(), database)

            val restored = store.restore()
            assertEquals(listOf("1|netease|"), restored?.map(DownloadedSong::stableKey))
            assertEquals("leased original lyric", restored?.single()?.originalLyric)
            assertEquals(
                "active-lease",
                database.managedLibraryItemDao().findAll(rootKey).single().leaseId
            )
        } finally {
            database.close()
            listOf(cacheFileName, cacheFileName + MANAGED_LIBRARY_CATALOG_BACKUP_SUFFIX)
                .forEach { name -> File(context.filesDir, name).delete() }
        }
    }

    @Test
    fun persistAndRestoreKeepsMetadataAndRootIsolation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            var rootKey = "root-a"
            val store = DownloadedSongCatalogRoomStore(
                context = context,
                database = database,
                cacheFileName = "unused-catalog.json",
                snapshotCacheKeyProvider = { rootKey },
                loggerTag = "DownloadedSongCatalogRoomStoreTest"
            )
            val song = song("first", "/music/first.mp3")

            store.persist(listOf(song))

            val restored = store.restore()
            assertEquals(listOf(song.stableKey), restored?.map(DownloadedSong::stableKey))
            assertEquals(song.name, restored?.single()?.name)
            rootKey = "root-b"
            assertNull(store.restore())
            assertTrue(
                database.syncMetadataDao()
                    .getMigrationMetadata(
                        DownloadedSongCatalogRoomStore.CUTOVER_STATE_METADATA_KEY
                    )
                    ?.value == DownloadedSongCatalogRoomStore.ROOM_PRIMARY_STATE
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun importsLegacyCatalogBeforePromotingRoom() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val cacheFileName = "downloaded-song-catalog-room-test.json"
        val cacheFile = File(context.filesDir, cacheFileName)
        val song = song("legacy", "/music/legacy.mp3")
        cacheFile.writeText(
            serializeDownloadedSongsCatalog(
                cacheKey = "root-a",
                songs = listOf(song)
            )
        )

        try {
            val store = DownloadedSongCatalogRoomStore(
                context = context,
                database = database,
                cacheFileName = cacheFileName,
                snapshotCacheKeyProvider = { "root-a" },
                loggerTag = "DownloadedSongCatalogRoomStoreTest"
            )

            val restored = store.restore()
            assertEquals(listOf(song.stableKey), restored?.map(DownloadedSong::stableKey))
            assertEquals("legacy", restored?.single()?.name)
            assertTrue(cacheFile.exists())
            assertEquals(
                DownloadedSongCatalogRoomStore.ROOM_PRIMARY_STATE,
                database.syncMetadataDao()
                    .getMigrationMetadata(
                        DownloadedSongCatalogRoomStore.CUTOVER_STATE_METADATA_KEY
                    )
                    ?.value
            )
        } finally {
            cacheFile.delete()
            database.close()
        }
    }

    @Test
    fun roomPersistFailureFallsBackToLegacyCatalogForNextRestore() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val cacheFileName = "downloaded-song-catalog-fallback-test.json"
        val cacheFile = File(context.filesDir, cacheFileName)

        try {
            val store = DownloadedSongCatalogRoomStore(
                context = context,
                database = database,
                cacheFileName = cacheFileName,
                snapshotCacheKeyProvider = { "root-a" },
                loggerTag = "DownloadedSongCatalogRoomStoreTest"
            )
            val oldSong = song("old-room", "/music/old.mp3")
            val newSong = song("new-legacy", "/music/new.mp3")
            store.persist(listOf(oldSong))

            val target = persistDownloadedSongCatalogWithFallback(
                store = FailingRoomCatalogStore(store),
                songs = listOf(newSong)
            )

            assertEquals(DownloadedSongCatalogPersistTarget.LEGACY_JSON, target)
            assertTrue(cacheFile.exists())
            assertEquals(
                DownloadedSongCatalogRoomStore.LEGACY_JSON_STATE,
                database.syncMetadataDao()
                    .getMigrationMetadata(
                        DownloadedSongCatalogRoomStore.CUTOVER_STATE_METADATA_KEY
                    )
                    ?.value
            )
            val restored = store.restore()
            assertEquals(listOf(newSong.stableKey), restored?.map(DownloadedSong::stableKey))
            assertEquals(newSong.name, restored?.single()?.name)
            assertEquals(
                DownloadedSongCatalogRoomStore.ROOM_PRIMARY_STATE,
                database.syncMetadataDao()
                    .getMigrationMetadata(
                        DownloadedSongCatalogRoomStore.CUTOVER_STATE_METADATA_KEY
                    )
                    ?.value
            )
        } finally {
            cacheFile.delete()
            database.close()
        }
    }

    @Test
    fun deltaPersistenceUpdatesAndRemovesOnlyAffectedPreviewRows() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NeriUserDataDatabase::class.java
        ).allowMainThreadQueries().build()
        val cacheFileName = "downloaded-song-catalog-delta-test.json"
        val cacheFile = File(context.filesDir, "$cacheFileName.managed-v1.json")
        try {
            val store = DownloadedSongCatalogRoomStore(
                context = context,
                database = database,
                cacheFileName = cacheFileName,
                snapshotCacheKeyProvider = { "root-delta" },
                loggerTag = "DownloadedSongCatalogRoomStoreTest"
            )
            val first = song("first", "/music/first.mp3")
            val second = song("second", "/music/second.mp3")
                .copy(stableKey = "2|netease|")
            store.persist(listOf(first, second))
            val replacement = first.copy(name = "first updated", fileSize = 200L)
            val delta = buildDownloadedSongCatalogDelta(
                previousSongs = listOf(first, second),
                currentSongs = listOf(replacement)
            )

            store.persistCatalogDelta(delta, listOf(replacement))

            val restored = store.restore()
            assertEquals(listOf(replacement.stableKey), restored?.map(DownloadedSong::stableKey))
            assertEquals("first updated", restored?.single()?.name)
            assertEquals(200L, restored?.single()?.fileSize)
        } finally {
            cacheFile.delete()
            database.close()
        }
    }

    private fun song(name: String, filePath: String): DownloadedSong {
        return DownloadedSong(
            id = 1L,
            name = name,
            artist = "artist",
            album = "album",
            filePath = filePath,
            fileSize = 100L,
            downloadTime = 20L,
            matchedLyric = "lyric",
            matchedTranslatedLyric = "translation",
            durationMs = 180_000L,
            stableKey = "1|netease|"
        )
    }

    private class FailingRoomCatalogStore(
        private val delegate: DownloadedSongCatalogRoomStore
    ) : DownloadedSongCatalogPersistenceStore {
        override suspend fun persistCatalog(songs: List<DownloadedSong>) {
            throw IOException("forced Room catalog failure")
        }

        override suspend fun persistLegacyFallback(songs: List<DownloadedSong>) {
            delegate.persistLegacyFallback(songs)
        }
    }
}
