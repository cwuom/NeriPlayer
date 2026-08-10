package moe.ouom.neriplayer.core.download.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.DownloadedSong
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class DownloadedSongCatalogRoomStoreTest {
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

            assertEquals(listOf(song), store.restore())
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

            assertEquals(
                listOf(song.copy(matchedLyric = null, matchedTranslatedLyric = null)),
                store.restore()
            )
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
            assertEquals(listOf(newSong), store.restore())
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
