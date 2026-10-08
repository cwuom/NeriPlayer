package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistSyncMutationOutbox
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.playlist.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.data.model.sync.SyncLocalPlaylistPlaybackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageStat
import moe.ouom.neriplayer.data.playlist.usage.LocalPlaylistPlaybackStatsRepository
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.withSettings
import org.mockito.invocation.InvocationOnMock

/**
 * Drives each repository's Room-to-JSON fallback through its public entry point while the legacy
 * JSON cleanup runs on another thread.
 *
 * The cleanup follows LegacyJsonCleanupCoordinator.execute: inside one Room transaction it reads the
 * cutover marker and deletes the legacy files only while the marker still says room_primary. As in
 * SQLite, the database double admits one write transaction at a time. The cleanup starts when the
 * repository flips its marker to legacy_json, i.e. right after the recovery JSON was written, and the
 * repository only continues once the cleanup has either finished or is waiting for the writer lock.
 * The real stores commit the fallback; only their Room table reads and writes are stubbed, and every
 * Room write fails so the repository must fall back to JSON.
 */
class LegacyJsonFallbackCleanupRaceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val room = InlineTransactionDatabase()
    private val writer = ReentrantLock()
    private var cleanup: Thread? = null

    init {
        @Suppress("DEPRECATION")
        run {
            doAnswer { writer.lock(); null }.`when`(room.database).beginTransaction()
            doAnswer { writer.unlock(); null }.`when`(room.database).endTransaction()
        }
    }

    @Before
    fun installCoverMapper() = CoverUrlMapper.installForTest(CoverUrlMapper.createForTest())

    @After
    fun removeCoverMapper() = CoverUrlMapper.installForTest(null)

    @Test
    fun `play history fallback JSON survives a cleanup racing the marker flip`() = runBlocking {
        val key = PlayHistoryRoomStore.CUTOVER_STATE_METADATA_KEY
        room.metadata.put(key, ROOM_PRIMARY)
        val store = partialMock<PlayHistoryRoomStore>(room.database)
        doAnswer { roomPrimaryData(key, emptyList<PlayedEntry>()) }.`when`(store).readIfRoomPrimary()
        doAnswer { failRoomWrite() }.`when`(store).writeIncremental(anyList(), anyList(), anyLong())
        doAnswer { failRoomWrite() }.`when`(store).importLegacyAndPromote(anyList(), anyLong())
        doAnswer { cleanupWhileMarking(it, key, "play_history.json") }.`when`(store).markLegacyJsonPrimary(anyLong())
        val history = listOf(
            PlayedEntry(id = 7, name = "song", artist = "artist", album = "netease", albumId = 1, durationMs = 100, coverUrl = null, playedAt = 7)
        )

        playHistoryRepository(store).updateHistory(history)

        awaitCleanupAndLegacyMarker(key)
        val restarted = playHistoryRepository(store)
        assertTrue(restarted.awaitInitialized())
        assertEquals(history, restarted.syncSnapshot())
    }

    @Test
    fun `playlist usage fallback JSON survives a cleanup racing the marker flip`() = runBlocking {
        val key = PlaylistUsageRoomStore.CUTOVER_STATE_METADATA_KEY
        room.metadata.put(key, ROOM_PRIMARY)
        val store = partialMock<PlaylistUsageRoomStore>(room.database)
        doAnswer { roomPrimaryData(key, emptyList<UsageEntry>()) }.`when`(store).readIfRoomPrimary()
        doAnswer { failRoomWrite() }.`when`(store).writeIncremental(anyList(), anyList(), anyLong())
        doAnswer { failRoomWrite() }.`when`(store).importLegacyAndPromote(anyList(), anyLong())
        doAnswer { cleanupWhileMarking(it, key, "playlist_usage.json") }.`when`(store).markLegacyJsonPrimary(anyLong())
        val repository = playlistUsageRepository(store)

        repository.applyMergedStatsAndPersist(
            listOf(SyncPlaylistUsageStat("netease:1", "netease", 1, name = "remote", trackCount = 3, lastOpenedAt = 200, firstOpenedAt = 100, openCount = 2))
        )

        awaitCleanupAndLegacyMarker(key)
        val restarted = playlistUsageRepository(store)
        assertTrue(restarted.awaitInitialized())
        assertEquals(listOf("remote"), restarted.frequentPlaylistsFlow.value.map(UsageEntry::name))
        assertEquals(repository.frequentPlaylistsFlow.value, restarted.frequentPlaylistsFlow.value)
    }

    @Test
    fun `local playlist playback fallback JSON survives a cleanup racing the marker flip`() = runBlocking {
        val key = LocalPlaylistPlaybackRoomStore.CUTOVER_STATE_METADATA_KEY
        room.metadata.put(key, ROOM_PRIMARY)
        val store = partialMock<LocalPlaylistPlaybackRoomStore>(room.database)
        doAnswer { roomPrimaryData(key, emptyList<LocalPlaylistPlaybackStat>()) }.`when`(store).readIfRoomPrimary()
        doAnswer { failRoomWrite() }.`when`(store).writeIncremental(anyList(), anyList(), anyLong())
        doAnswer { failRoomWrite() }.`when`(store).importLegacyAndPromote(anyList(), anyLong())
        doAnswer { cleanupWhileMarking(it, key, "local_playlist_playback_stats.json") }.`when`(store).markLegacyJsonPrimary(anyLong())
        val repository = localPlaylistPlaybackStatsRepository(store)

        repository.applyMergedStats(
            listOf(SyncLocalPlaylistPlaybackStat(1, totalPlayCount = 4, firstPlayedAt = 100, lastPlayedAt = 200)),
            emptyList()
        )

        awaitCleanupAndLegacyMarker(key)
        val restarted = localPlaylistPlaybackStatsRepository(store)
        assertTrue(restarted.awaitInitialized())
        assertEquals(listOf(4L), restarted.statsFlow.value.map(LocalPlaylistPlaybackStat::totalPlayCount))
        assertEquals(repository.statsFlow.value, restarted.statsFlow.value)
    }

    @Test
    fun `local playlist fallback JSON survives a cleanup racing the marker flip`() = runBlocking {
        val key = LocalPlaylistRoomStore.CUTOVER_STATE_METADATA_KEY
        room.metadata.put(key, ROOM_PRIMARY)
        val store = partialMock<LocalPlaylistRoomStore>(room.database, Gson())
        val roomPlaylist = LocalPlaylist(id = 1, name = "Road", songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION)
        doAnswer { roomPrimaryData(key, listOf(roomPlaylist)) }.`when`(store).readIfRoomPrimary()
        doAnswer { failRoomWrite() }.`when`(store).writeIncremental(anyList(), anyList(), anyString(), anyLong())
        doAnswer { failRoomWrite() }.`when`(store).importLegacyAndPromote(anyList(), anyString())
        doAnswer { null }.`when`(store).readPendingSyncMutationOutbox()
        doAnswer { Unit }.`when`(store).writePendingSyncMutationOutbox(any() ?: LocalPlaylistSyncMutationOutbox(emptyList()), anyLong())
        doAnswer { Unit }.`when`(store).clearPendingSyncMutationOutbox()
        doAnswer {
            cleanupWhileMarking(it, key, "local_playlists.json", "local_playlists.json.bak", "local_playlists.json.sync-pending.json")
        }.`when`(store).markLegacyJsonPrimary(anyString(), anyLong())

        localPlaylistRepository(store).renamePlaylist(1, "Night")

        awaitCleanupAndLegacyMarker(key)
        val restarted = localPlaylistRepository(store)
        assertTrue(restarted.awaitInitialized())
        assertEquals(listOf("Night"), restarted.playlists.value.map(LocalPlaylist::name))
    }

    private fun <T> roomPrimaryData(cutoverKey: String, data: T): T? =
        data.takeIf { room.metadata.value(cutoverKey) == ROOM_PRIMARY }

    private fun failRoomWrite(): Nothing = throw IOException("Room write failed")

    private fun cleanupWhileMarking(invocation: InvocationOnMock, cutoverKey: String, vararg legacyFiles: String): Any? {
        if (cleanup == null) cleanup = startCleanup(cutoverKey, legacyFiles.map { File(temporary.root, it) })
        return invocation.callRealMethod()
    }

    private fun startCleanup(cutoverKey: String, legacyFiles: List<File>): Thread {
        val cleanup = thread(name = "legacy-json-cleanup") {
            runBlocking {
                room.database.withTransaction {
                    val marker = room.database.syncMetadataDao().getMigrationMetadata(cutoverKey)?.value
                    if (marker == ROOM_PRIMARY) legacyFiles.forEach(File::delete)
                }
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (cleanup.isAlive && !writer.hasQueuedThread(cleanup)) {
            check(System.nanoTime() < deadline) { "Cleanup neither finished nor waited for the writer lock" }
            Thread.sleep(1)
        }
        return cleanup
    }

    private fun awaitCleanupAndLegacyMarker(cutoverKey: String) {
        val cleanup = checkNotNull(cleanup) { "The repository never flipped its marker to legacy_json" }
        cleanup.join(TimeUnit.SECONDS.toMillis(10))
        assertFalse(cleanup.isAlive)
        assertEquals(LEGACY_JSON, room.metadata.value(cutoverKey))
    }

    private inline fun <reified T> partialMock(vararg constructorArgs: Any): T =
        mock(T::class.java, withSettings().useConstructor(*constructorArgs).defaultAnswer(CALLS_REAL_METHODS))

    private fun context(): Context = mock(Context::class.java).also { context ->
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(temporary.root)
        `when`(context.getString(CoreCommonR.string.playlist_create)).thenReturn("Playlist")
        `when`(context.getString(CoreCommonR.string.favorite_my_music)).thenReturn("Favorites")
        `when`(context.getString(CoreCommonR.string.local_files)).thenReturn("Local Files")
    }

    private fun playHistoryRepository(store: PlayHistoryRoomStore): PlayHistoryRepository {
        val constructor = PlayHistoryRepository::class.java.getDeclaredConstructor(Context::class.java, PlayHistoryRoomStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(context(), store).also { runBlocking { it.awaitInitialLoad() } }
    }

    private fun playlistUsageRepository(store: PlaylistUsageRoomStore): PlaylistUsageRepository {
        val syncStorage = mock(SecureTokenStorage::class.java).also { storage ->
            `when`(storage.getPlaylistUsageDeletions()).thenReturn(emptyMap())
            `when`(storage.getPlaylistUsageDeletionsConfirmed()).thenReturn(emptyMap())
        }
        return PlaylistUsageRepository(context(), store).also { repository ->
            runBlocking { repository.awaitInitialLoad() }
            PlaylistUsageRepository::class.java.getDeclaredField("syncStorage\$delegate")
                .also { it.isAccessible = true }
                .set(repository, lazy { syncStorage })
        }
    }

    private fun localPlaylistPlaybackStatsRepository(store: LocalPlaylistPlaybackRoomStore): LocalPlaylistPlaybackStatsRepository {
        val constructor = LocalPlaylistPlaybackStatsRepository::class.java
            .getDeclaredConstructor(Context::class.java, LocalPlaylistPlaybackRoomStore::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(context(), store).also { runBlocking { it.awaitInitialLoad() } }
    }

    private fun localPlaylistRepository(store: LocalPlaylistRoomStore): LocalPlaylistRepository =
        LocalPlaylistRepository.createForTest(
            context = context(),
            file = File(temporary.root, "local_playlists.json"),
            roomStore = store
        )

    private companion object {
        const val ROOM_PRIMARY = "room_primary"
        const val LEGACY_JSON = "legacy_json"
    }
}
