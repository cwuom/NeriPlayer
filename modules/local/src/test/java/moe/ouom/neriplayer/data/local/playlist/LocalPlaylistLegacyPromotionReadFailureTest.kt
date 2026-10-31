package moe.ouom.neriplayer.data.local.playlist

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.LocalPlaylistRoomStore
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalPlaylistLegacyPromotionReadFailureTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun `a transient legacy read failure cannot promote an empty Room primary`() = runTest {
        val original = File(directory.root, "local_playlists.json")
        val originalText = Gson().toJson(listOf(LocalPlaylist(id = 7L, name = "original playlist")))
        original.writeText(originalText)
        val files = LocalPlaylistFileStorage(original, directory.root)
        var reads = 0
        val storage = object : LocalPlaylistStorage by files {
            override fun readPrimary(): String? {
                reads++
                if (reads == 1) throw IOException("primary temporarily unavailable")
                return files.readPrimary()
            }
        }
        withRoom { context, room ->
            val repository = repository(context, original, storage, room)

            assertFalse(repository.initializationReadyFlow.value)
            assertFalse(room.isRoomPrimary())
            assertTrue(room.readPlaylists().isEmpty())
            assertEquals(originalText, original.readText())

            assertTrue(repository.awaitInitialized())
            assertTrue(room.isRoomPrimary())
            assertEquals(listOf(7L), room.readPlaylists().map { it.id })
            assertEquals(listOf(7L), repository.playlists.value.map { it.id })
        }
    }

    @Test
    fun `a failed backup read cannot turn a missing primary into empty authority`() = runTest {
        val original = File(directory.root, "local_playlists.json")
        val files = LocalPlaylistFileStorage(original, directory.root)
        val storage = object : LocalPlaylistStorage by files {
            override fun readBackup(): String? = throw IOException("backup temporarily unavailable")
        }
        withRoom { context, room ->
            val repository = repository(context, original, storage, room)

            assertFalse(repository.awaitInitialized())
            assertFalse(room.isRoomPrimary())
            assertFalse(original.exists())
        }
    }

    @Test
    fun `a transient primary read failure cannot overwrite newer playlists with an older backup`() = runTest {
        val original = File(directory.root, "local_playlists.json")
        val originalText = Gson().toJson(listOf(LocalPlaylist(id = 7L, name = "newer playlist")))
        val backupText = Gson().toJson(listOf(LocalPlaylist(id = 6L, name = "older backup")))
        original.writeText(originalText)
        File(directory.root, "local_playlists.json.bak").writeText(backupText)
        val files = LocalPlaylistFileStorage(original, directory.root)
        var reads = 0
        val storage = object : LocalPlaylistStorage by files {
            override fun readPrimary(): String? {
                reads++
                if (reads == 1) throw IOException("primary temporarily unavailable")
                return files.readPrimary()
            }
        }
        withRoom { context, room ->
            val repository = repository(context, original, storage, room)

            assertFalse(repository.initializationReadyFlow.value)
            assertFalse(room.isRoomPrimary())
            assertEquals(originalText, original.readText())
            assertEquals(backupText, files.readBackup())

            assertTrue(repository.awaitInitialized())
            assertEquals(listOf(7L), room.readPlaylists().map { it.id })
            assertEquals(originalText, original.readText())
            assertEquals(backupText, files.readBackup())
        }
    }

    @Test
    fun `corrupt legacy data that cannot be quarantined cannot become Room primary`() = runTest {
        val original = File(directory.root, "local_playlists.json").apply { writeText("[{broken") }
        val files = LocalPlaylistFileStorage(original, directory.root)
        val storage = object : LocalPlaylistStorage by files {
            override fun quarantinePrimary(): File? = throw IOException("quarantine unavailable")
        }
        withRoom { context, room ->
            val repository = repository(context, original, storage, room)

            assertFalse(repository.awaitInitialized())
            assertFalse(room.isRoomPrimary())
            assertEquals("[{broken", original.readText())
        }
    }

    @Test
    fun `confirmed missing legacy data still permits first Room initialization`() = runTest {
        val original = File(directory.root, "local_playlists.json")
        withRoom { context, room ->
            val repository = repository(context, original, LocalPlaylistFileStorage(original, directory.root), room)

            assertTrue(repository.awaitInitialized())
            assertTrue(room.isRoomPrimary())
            assertTrue(room.readPlaylists().isEmpty())
        }
    }

    @Test
    fun `a valid empty legacy list still permits Room promotion`() = runTest {
        val original = File(directory.root, "local_playlists.json").apply { writeText("[]") }
        withRoom { context, room ->
            val repository = repository(context, original, LocalPlaylistFileStorage(original, directory.root), room)

            assertTrue(repository.awaitInitialized())
            assertTrue(room.isRoomPrimary())
            assertTrue(room.readPlaylists().isEmpty())
            assertEquals("[]", original.readText())
        }
    }

    @Test
    fun `quarantined corrupt source remains preserved across promotion retry and reconstruction`() = runTest {
        val original = File(directory.root, "local_playlists.json").apply { writeText("[{broken") }
        val storage = LocalPlaylistFileStorage(original, directory.root)
        withRoom { context, room ->
            val repository = repository(context, original, storage, room)

            assertTrue(repository.initializationReadyFlow.value)
            assertTrue(repository.awaitInitialized())
            assertTrue(room.isRoomPrimary())
            val quarantine = directory.root.listFiles().orEmpty().single {
                it.name.startsWith("local_playlists.json.corrupt-")
            }
            assertEquals("[{broken", quarantine.readText())
            assertFalse(original.exists())

            val reopened = repository(context, original, storage, room)
            assertTrue(reopened.awaitInitialized())
            assertTrue(reopened.playlists.value.isEmpty())
            assertEquals("[{broken", quarantine.readText())
            assertTrue(room.readPlaylists().isEmpty())
        }
    }

    private fun repository(context: Context, file: File, storage: LocalPlaylistStorage, room: LocalPlaylistRoomStore) =
        LocalPlaylistRepository.createForTest(
            context = context,
            file = file,
            normalizePlaylists = { it },
            autoSyncEnabled = false,
            loadSynchronously = true,
            storage = storage,
            roomStore = room
        )

    private suspend fun withRoom(block: suspend (Context, LocalPlaylistRoomStore) -> Unit) {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir(): File = directory.root
            override fun getApplicationContext(): Context = this
        }
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            block(context, LocalPlaylistRoomStore(database))
        } finally {
            database.close()
        }
    }
}
