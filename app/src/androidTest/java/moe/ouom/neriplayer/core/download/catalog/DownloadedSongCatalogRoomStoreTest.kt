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
}
