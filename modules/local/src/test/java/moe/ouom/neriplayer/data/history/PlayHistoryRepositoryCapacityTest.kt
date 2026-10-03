@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.ouom.neriplayer.data.history

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.store.PlayHistoryRoomStore
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlay
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncSystemPlaylist
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.merge.host.SyncMergeHost
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.preferences.PlayHistorySyncPreferences
import moe.ouom.neriplayer.data.sync.store.preferences.PlayHistoryUpdateMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class PlayHistoryRepositoryCapacityTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `legacy loading keeps every identity and selects newest duplicate beyond former capacity`() = runTest {
        val entries = entries() + entry(3L).copy(playedAt = 20_000L, name = "latest")
        Fixture(this, entries).use { fixture ->
            val loaded = fixture.repository.historyFlow.value
            assertEquals(1501, loaded.size)
            assertEquals((1L..1501L).toSet(), loaded.map { it.id }.toSet())
            assertEquals("latest", loaded.first().name)
            assertEquals(3L, loaded.first().id)
            assertEquals(entries, fixture.persisted())
        }
    }

    @Test
    fun `replacement and guarded replacement retain all identities and persist across reload`() = runTest {
        Fixture(this).use { fixture ->
            val newest = entry(3L).copy(playedAt = 20_000L)
            fixture.repository.updateHistory(entries() + newest)
            assertEquals(1501, fixture.repository.historyFlow.value.size)
            assertEquals(newest, fixture.repository.historyFlow.value.first())
            assertEquals(fixture.repository.historyFlow.value, fixture.persisted())

            val replacement = entries() + entry(1502L)
            assertTrue(fixture.repository.updateHistoryIfUnchanged(replacement, 7L))
            val beforeRejected = fixture.repository.historyFlow.value
            assertEquals(1502, beforeRejected.size)
            `when`(fixture.storage.getSyncMutationVersion()).thenReturn(8L)
            assertFalse(fixture.repository.updateHistoryIfUnchanged(emptyList(), 7L))
            assertEquals(beforeRejected, fixture.repository.historyFlow.value)
            assertEquals(beforeRejected, fixture.persisted())

            fixture.reload().use { reloaded ->
                assertEquals(beforeRejected, reloaded.repository.historyFlow.value)
            }
        }
    }

    @Test
    fun `record position and metadata updates preserve older identities without growing duplicates`() = runTest {
        Fixture(this, entries()).use { fixture ->
            fixture.repository.record(song(1502L), now = 5000L)
            runCurrent()
            assertEquals(1502, fixture.repository.historyFlow.value.size)
            assertEquals(1502L, fixture.repository.historyFlow.value.first().id)

            fixture.repository.record(song(1L), now = 6000L)
            runCurrent()
            assertEquals(1502, fixture.repository.historyFlow.value.size)
            assertEquals(6000L, fixture.repository.historyFlow.value.single { it.id == 1L }.playedAt)

            fixture.repository.updateRememberedPlaybackPosition(song(1503L), 77L, now = 7000L)
            runCurrent()
            assertEquals(1503, fixture.repository.historyFlow.value.size)
            assertEquals(77L, fixture.repository.rememberedPlaybackPosition(song(1503L)))

            fixture.repository.updateRememberedPlaybackPosition(song(2L), 99L, now = 8000L)
            runCurrent()
            fixture.repository.updateSongMetadata(song(1L), song(1L).copy(name = "renamed"), triggerSync = false)
            runCurrent()
            assertEquals(1503, fixture.repository.historyFlow.value.size)
            assertEquals("renamed", fixture.repository.historyFlow.value.single { it.id == 1L }.name)
            assertEquals(99L, fixture.repository.rememberedPlaybackPosition(song(2L)))
            assertEquals((1L..1503L).toSet(), fixture.repository.historyFlow.value.map { it.id }.toSet())
            assertEquals(fixture.repository.historyFlow.value, fixture.persisted())
        }
    }

    @Test
    fun `removal creates tombstones for both old and recent identities after former capacity`() = runTest {
        Fixture(this, entries()).use { fixture ->
            fixture.repository.removeSongs(listOf(song(1L), song(1501L)))
            runCurrent()

            assertEquals(1499, fixture.repository.historyFlow.value.size)
            assertEquals(setOf(1L, 1501L), fixture.deletions.map { it.songId }.toSet())
            assertTrue(fixture.deletions.all { it.deletedAt > 0L && it.deviceId == "device" })
            assertFalse(fixture.repository.historyFlow.value.any { it.id == 1L || it.id == 1501L })
            assertEquals(fixture.repository.historyFlow.value, fixture.persisted())
        }
    }

    @Test
    fun `real Netease history removal cannot return from an older provider snapshot`() = runTest {
        val source = song(1).copy(album = "Netease", channelId = "netease", audioId = "1")
        Fixture(this, listOf(source.toPlayedEntry(10))).use { fixture ->
            fixture.repository.removeSongs(listOf(source))
            runCurrent()
            assertTrue(fixture.repository.historyFlow.value.isEmpty())
            assertEquals("Netease", fixture.deletions.single().album)
            val oldProvider = SyncData(recentPlays = listOf(SyncRecentPlay(
                1, SyncSong(id = 1, album = "Netease", channelId = "netease", audioId = "1"), 10
            )))
            val local = SyncData(recentPlayDeletions = fixture.deletions.toList())
            val merger = SyncDataMerger(object : SyncMergeHost {
                override val favoritesPlaylistId = -1L
                override val mergeSuccessMessage = "merged"
                override val initialUploadMessage = "initial"
                override fun systemPlaylist(id: Long, name: String): SyncSystemPlaylist? = null
                override fun localRenameMessage(name: String) = name
                override fun remoteRenameMessage(name: String) = name
            }) { 50L }

            val merged = merger.merge(local, oldProvider, 1).mergedData

            assertTrue(merged.recentPlays.isEmpty())
            assertEquals("netease", merged.recentPlayDeletions.single().album)
            assertEquals(fixture.deletions.single().deletedAt, merged.recentPlayDeletions.single().deletedAt)
            assertTrue(merger.merge(merged, oldProvider, 1).mergedData.recentPlays.isEmpty())
            assertEquals(merged.recentPlayDeletions, merger.merge(merged, oldProvider, 1).mergedData.recentPlayDeletions)
        }
    }

    private fun entries() = (1L..1501L).map(::entry)

    private fun entry(id: Long) = PlayedEntry(
        id = id, name = "song $id", artist = "artist", album = "netease", albumId = 1L,
        durationMs = 100L, coverUrl = null, playedAt = id
    )

    private fun song(id: Long) = SongItem(
        id = id, name = "song $id", artist = "artist", album = "netease", albumId = 1L,
        durationMs = 100L, coverUrl = null
    )

    private inner class Fixture(
        private val testScope: TestScope,
        initial: List<PlayedEntry>? = null,
        private val directory: File = temporary.newFolder()
    ) : Closeable {
        val storage = mock(SecureTokenStorage::class.java)
        val deletions = ArrayList<SyncRecentPlayDeletion>()
        private val scope = CoroutineScope(SupervisorJob(testScope.backgroundScope.coroutineContext[Job]) + StandardTestDispatcher(testScope.testScheduler))
        private val file = File(directory, "play_history.json")
        val repository: PlayHistoryRepository

        init {
            if (initial != null) file.writeText(Gson().toJson(initial))
            val context = mock(Context::class.java)
            `when`(context.filesDir).thenReturn(directory)
            `when`(storage.getSyncMutationVersion()).thenReturn(7L)
            `when`(storage.getOrCreateDeviceId()).thenReturn("device")
            doAnswer { invocation ->
                deletions.addAll(invocation.getArgument<List<SyncRecentPlayDeletion>>(0))
                null
            }.`when`(storage).addRecentPlayDeletions(anyList())
            val preferences = mock(PlayHistorySyncPreferences::class.java)
            `when`(preferences.getUpdateMode(null)).thenReturn(PlayHistoryUpdateMode.EVERY_30_MINUTES)
            val constructor = PlayHistoryRepository::class.java.getDeclaredConstructor(Context::class.java, PlayHistoryRoomStore::class.java)
            constructor.isAccessible = true
            repository = constructor.newInstance(context, null)
            val originalScope = PlayHistoryRepository::class.java.getDeclaredField("scope").also { it.isAccessible = true }
            (originalScope.get(repository) as CoroutineScope).cancel()
            originalScope.set(repository, scope)
            setField("storage\$delegate", lazy { storage })
            setField("syncPreferences\$delegate", lazy { preferences })
            setField("lastBatchSyncTime", System.currentTimeMillis())
        }

        fun persisted(): List<PlayedEntry> = Gson().fromJson(file.readText(), object : TypeToken<List<PlayedEntry>>() {}.type)

        fun reload() = Fixture(testScope, directory = directory)

        private fun setField(name: String, value: Any) {
            PlayHistoryRepository::class.java.getDeclaredField(name).also { it.isAccessible = true }.set(repository, value)
        }

        override fun close() {
            scope.cancel()
        }
    }
}
