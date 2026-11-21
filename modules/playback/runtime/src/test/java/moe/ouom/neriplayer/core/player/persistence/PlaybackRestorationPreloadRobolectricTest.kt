package moe.ouom.neriplayer.core.player.persistence

import android.app.Application
import android.os.Looper
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.playback.PersistedSongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], manifest = Config.NONE)
class PlaybackRestorationPreloadRobolectricTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val reads = CopyOnWriteArrayList<Pair<String, Thread>>()
    private lateinit var database: NeriUserDataDatabase
    private lateinit var store: PlaybackQueueRoomStore
    private lateinit var stateFile: File
    private lateinit var playbackFile: File

    @Before
    fun setUp() {
        stateFile = File(temporary.root, "last_playlist.json")
        playbackFile = File(temporary.root, "last_playback_state.json")
        database = Room.inMemoryDatabaseBuilder(app, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(Dispatchers.Unconfined, RoomDatabase.QueryCallback { sql, args ->
                val query = sql.trimStart()
                if (query.startsWith("SELECT", ignoreCase = true) &&
                    ((query.contains("migration_metadata", ignoreCase = true) &&
                        args.contains(PlaybackQueueRoomStore.CUTOVER_STATE_METADATA_KEY)) ||
                        query.contains("playback_queue_state", ignoreCase = true) ||
                        query.contains("playback_queue_song", ignoreCase = true))
                ) {
                    reads += query to Thread.currentThread()
                }
            }).build()
        database.openHelper.writableDatabase
        store = PlaybackQueueRoomStore(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `fresh empty preload does not synchronously read storage again on main`() {
        assertEmptyPreloadDoesNotReadAgain(roomPrimary = false)
    }

    @Test
    fun `cleared room primary preload does not synchronously read storage again on main`() {
        assertEmptyPreloadDoesNotReadAgain(roomPrimary = true)
    }

    @Test
    fun `unprovided preload retains synchronous fallback and completes its reads before returning to main`() {
        val caller = Thread.currentThread()
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        var fallbackCalls = 0
        var appliedSnapshots = 0

        completeInitialRestoration(
            restorationPreload = RestoredPlayerStatePreload.NeedsFallback,
            applySnapshot = { appliedSnapshots += 1 },
            restoreState = {
                fallbackCalls += 1
                readFallback()
            }
        )

        assertSame(caller, Thread.currentThread())
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        assertEquals(1, fallbackCalls)
        assertEquals(0, appliedSnapshots)
        assertFalse("the real fallback must execute Room SELECTs", reads.isEmpty())
        assertTrue("the main caller synchronously waits for IO reads", reads.all { it.second !== caller })
    }

    private fun assertEmptyPreloadDoesNotReadAgain(roomPrimary: Boolean) {
        if (roomPrimary) runBlocking { store.clear(now = 10L) }
        reads.clear()
        assertFalse(stateFile.exists())
        assertFalse(playbackFile.exists())
        val restored = preload()
        assertEquals(RestoredPlayerStatePreload.Completed(null), restored)
        val preloadReads = reads.size
        assertTrue("the preload must execute real Room SELECTs", preloadReads > 0)
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        var fallbackCalls = 0
        var appliedSnapshots = 0

        completeInitialRestoration(
            restorationPreload = restored,
            applySnapshot = { appliedSnapshots += 1 },
            restoreState = {
                fallbackCalls += 1
                readFallback()
            }
        )

        println("empty-preload roomPrimary=$roomPrimary preloadReads=$preloadReads totalReads=${reads.size}")
        assertEquals("successful empty preload must not read Room a second time", preloadReads, reads.size)
        assertEquals(0, fallbackCalls)
        assertEquals(0, appliedSnapshots)
    }

    private fun readFallback() = loadRestoredStateSnapshot(
        app = app,
        stateFile = stateFile,
        playbackStateFile = playbackFile,
        keepLastPlaybackProgressEnabled = true,
        keepPlaybackModeStateEnabled = true,
        createRoomStore = { store }
    )

    private fun preload(
        readLyricOverrides: (Set<String>) -> List<SyncSong> = { emptyList() },
        createRoomStore: () -> PlaybackQueueRoomStore = { store }
    ) = runBlocking {
        preloadRestoredPlayerState(
            app = app,
            keepLastPlaybackProgressEnabled = true,
            keepPlaybackModeStateEnabled = true,
            startupStateFile = stateFile,
            startupPlaybackStateFile = playbackFile,
            createRoomStore = createRoomStore,
            readLyricOverrides = readLyricOverrides
        )
    }

    @Test
    fun `orphan playback file retains fallback even without a queue file`() {
        playbackFile.writeText("null")

        assertNeedsFallback(preload())
    }

    @Test
    fun `malformed and null legacy queues retain fallback`() {
        listOf("null", "[1,2]", "{", "{\"playlist\":null,\"index\":0}").forEach { json ->
            stateFile.writeText(json)

            assertNeedsFallback(preload())
        }
    }

    @Test
    fun `room queue read failure cannot be classified as a successful empty preload`() {
        runBlocking { store.clear(now = 10L) }
        database.openHelper.writableDatabase.execSQL("DROP TABLE playback_queue_state")

        assertNeedsFallback(preload())
    }

    @Test
    fun `room marker read failure cannot be classified as a successful empty preload`() {
        database.openHelper.writableDatabase.execSQL("DROP TABLE migration_metadata")

        assertNeedsFallback(preload())
    }

    @Test
    fun `valid legacy queue still applies when room read and import fail`() {
        runBlocking { store.clear(now = 10L) }
        database.openHelper.writableDatabase.execSQL("DROP TABLE playback_queue_state")
        stateFile.writeText(Gson().toJson(queue()))

        val snapshot = assertCompletedSnapshot(preload())

        assertEquals(listOf(11L), snapshot.playlist.map { it.id })
        assertEquals(1_500L, snapshot.resumePositionMs)
        assertEquals(2, snapshot.repeatMode)
    }

    @Test
    fun `valid legacy queue still applies when room marker and import fail`() {
        database.openHelper.writableDatabase.execSQL("DROP TABLE migration_metadata")
        stateFile.writeText(Gson().toJson(queue()))

        assertEquals(11L, assertCompletedSnapshot(preload()).playlist.single().id)
    }

    @Test
    fun `damaged playback overlay preserves the valid legacy queue snapshot`() {
        stateFile.writeText(Gson().toJson(queue()))
        playbackFile.writeText("[1,2]")

        assertEquals(1_500L, assertCompletedSnapshot(preload()).resumePositionMs)
    }

    @Test
    fun `persisted empty queue remains a snapshot with its playback mode`() {
        runBlocking { store.replaceSnapshot(queue().copy(playlist = emptyList(), index = -1), now = 10L) }

        val snapshot = assertCompletedSnapshot(preload())

        assertTrue(snapshot.playlist.isEmpty())
        assertEquals(-1, snapshot.currentIndex)
        assertEquals(2, snapshot.repeatMode)
    }

    @Test
    fun `nonempty room queue keeps its progress and applies only once without rereading storage`() {
        runBlocking { store.replaceSnapshot(queue(), now = 10L) }

        val snapshot = assertCompletedSnapshot(preload())

        assertEquals(11L, snapshot.playlist.single().id)
        assertEquals(1_500L, snapshot.resumePositionMs)
    }

    @Test
    fun `projection failure retains fallback instead of reporting a completed empty preload`() {
        runBlocking { store.replaceSnapshot(queue(), now = 10L) }

        val preload = preload(readLyricOverrides = { throw IllegalStateException("projection fixture") })

        assertSame(RestoredPlayerStatePreload.NeedsFallback, preload)
        var fallbackCalls = 0
        completeInitialRestoration(preload, { throw AssertionError("must not apply") }, { fallbackCalls += 1 })
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun `database creation failure preserves its type message and original cause instead of becoming fallback`() {
        val failure = IllegalStateException("database construction fixture")

        val thrown = runCatching { preload(createRoomStore = { throw failure }) }.exceptionOrNull()

        assertEquals(failure.javaClass, thrown?.javaClass)
        assertEquals(failure.message, thrown?.message)
        assertTrue("coroutine exception recovery must preserve the original database failure",
            generateSequence(thrown) { it.cause }.any { it === failure })
    }

    @Test
    fun `projection cancellation propagates without applying or falling back`() {
        runBlocking { store.replaceSnapshot(queue(), now = 10L) }
        val cancellation = CancellationException("projection cancellation fixture")
        var completed = false

        val thrown = runCatching {
            val preload = preload(readLyricOverrides = { throw cancellation })
            completed = true
            completeInitialRestoration(preload, { throw AssertionError("must not apply") },
                { throw AssertionError("must not fall back") })
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertFalse(completed)
    }

    @Test
    fun `an already cancelled preload does not create its database or enter a restoration branch`() {
        val cancelledJob = Job().apply { cancel() }
        var databaseCreations = 0
        var completed = false

        val thrown = runCatching {
            runBlocking {
                withContext(cancelledJob) {
                    val preload = preloadRestoredPlayerState(
                        app, true, true, stateFile, playbackFile,
                        createRoomStore = { databaseCreations += 1; store }
                    )
                    completed = true
                    completeInitialRestoration(preload, { throw AssertionError("must not apply") },
                        { throw AssertionError("must not fall back") })
                }
            }
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(0, databaseCreations)
        assertFalse(completed)
    }

    private fun assertNeedsFallback(preload: RestoredPlayerStatePreload) {
        assertSame(RestoredPlayerStatePreload.NeedsFallback, preload)
        var fallbackCalls = 0
        var appliedSnapshots = 0
        completeInitialRestoration(
            preload,
            applySnapshot = { appliedSnapshots += 1 },
            restoreState = { fallbackCalls += 1; readFallback() }
        )
        assertEquals(1, fallbackCalls)
        assertEquals(0, appliedSnapshots)
    }

    private fun assertCompletedSnapshot(preload: RestoredPlayerStatePreload): RestoredPlayerStateSnapshot {
        assertTrue(preload is RestoredPlayerStatePreload.Completed)
        val expected = requireNotNull((preload as? RestoredPlayerStatePreload.Completed)?.snapshot)
        val before = reads.size
        val applied = mutableListOf<RestoredPlayerStateSnapshot>()
        completeInitialRestoration(preload, { applied += it }, { throw AssertionError("must not reread storage") })
        assertEquals(before, reads.size)
        assertEquals(1, applied.size)
        assertSame(expected, applied.single())
        return applied.single()
    }

    private fun queue() = PersistedState(
        playlist = listOf(PersistedSongItem(
            id = 11L, name = "Synthetic", artist = "Artist", album = "Album", albumId = 0L,
            durationMs = 10_000L, coverUrl = null, channelId = "netease", audioId = "11"
        )), index = 0, positionMs = 1_500L, repeatMode = 2
    )
}
