package moe.ouom.neriplayer.core.player.persistence

import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.playback.PersistedPlaybackState
import moe.ouom.neriplayer.data.model.playback.PersistedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackQueueRoomFallbackTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val queue = PersistedState(playlist = emptyList(), index = 3)
    private val playback = PersistedPlaybackState(index = 3, positionMs = 1_234L)
    private val stateFile by lazy { File(temporaryFolder.root, "queue.json") }
    private val playbackFile by lazy { File(temporaryFolder.root, "playback.json") }
    private val legacyStore by lazy { PlaybackQueueLegacyStore(stateFile, playbackFile, Gson()) }

    @Test
    fun `nothing requested writes nothing`() = runTest {
        val store = RecordingStore()

        assertEquals(PlaybackQueuePersistTarget.NONE, persist(store, writeQueue = false, writePlayback = false))
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun `each request writes only the matching room table and retires legacy json`() = runTest {
        for ((writeQueue, writePlayback, expectedCall) in listOf(
            Triple(true, false, "replaceSnapshot"),
            Triple(false, true, "updatePlaybackState"),
            Triple(true, true, "replaceSnapshot")
        )) {
            legacyStore.write(queue, playback)
            val store = RecordingStore()

            assertEquals(PlaybackQueuePersistTarget.ROOM, persist(store, writeQueue, writePlayback))
            assertEquals(listOf(expectedCall), store.calls)
            assertFalse(stateFile.exists())
        }
    }

    @Test
    fun `lazy queue provider wins over the captured queue`() = runTest {
        val provided = PersistedState(playlist = emptyList(), index = 9)
        val store = RecordingStore()

        persist(store, writeQueue = true, writePlayback = false, queueState = null, provider = { provided })

        assertSame(provided, store.replacedSnapshot)
    }

    @Test
    fun `room failure falls back to legacy json and marks it primary`() = runTest {
        val roomError = IllegalStateException("room down")
        val failures = mutableListOf<Throwable>()
        val store = RecordingStore(failure = roomError)

        val target = persist(store, writeQueue = true, writePlayback = true, onFailure = failures::add)

        assertEquals(PlaybackQueuePersistTarget.LEGACY_JSON, target)
        assertEquals(listOf<Throwable>(roomError), failures)
        assertEquals(listOf("replaceSnapshot", "markLegacyJsonPrimary"), store.calls)
        assertEquals(3, legacyStore.read()?.index)
    }

    @Test
    fun `failing to mark legacy json primary is reported and rethrown`() = runTest {
        val roomError = IllegalStateException("room down")
        val markerError = IllegalStateException("marker down")
        val failures = mutableListOf<Throwable>()
        val store = RecordingStore(failure = roomError, markerFailure = markerError)

        val thrown = runCatching {
            persist(store, writeQueue = false, writePlayback = true, onFailure = failures::add)
        }.exceptionOrNull()

        assertSame(markerError, thrown)
        assertEquals(listOf<Throwable>(roomError, markerError), failures)
        assertTrue(stateFile.exists())
    }

    @Test
    fun `missing queue clears room and legacy json`() = runTest {
        legacyStore.write(queue, playback)
        val store = RecordingStore()

        val target = persist(store, writeQueue = true, writePlayback = false, queueState = null)

        assertEquals(PlaybackQueuePersistTarget.ROOM, target)
        assertEquals(listOf("clear"), store.calls)
        assertFalse(stateFile.exists())
        assertFalse(playbackFile.exists())
    }

    @Test
    fun `failed clear writes an empty legacy queue that keeps the playback state`() = runTest {
        val clearError = IllegalStateException("clear failed")
        val failures = mutableListOf<Throwable>()
        val store = RecordingStore(failure = clearError)

        val target = persist(store, writeQueue = true, writePlayback = true, queueState = null, onFailure = failures::add)

        assertEquals(PlaybackQueuePersistTarget.LEGACY_JSON, target)
        assertEquals(listOf<Throwable>(clearError), failures)
        assertEquals(listOf("clear", "markLegacyJsonPrimary"), store.calls)
        val restored = legacyStore.read()
        assertEquals(emptyList<Any>(), restored?.playlist)
        assertEquals(3, restored?.index)
        assertEquals(1_234L, restored?.positionMs)
    }

    @Test
    fun `failed clear propagates a marker failure`() = runTest {
        val markerError = IllegalStateException("marker down")
        val store = RecordingStore(failure = IllegalStateException("clear failed"), markerFailure = markerError)

        val thrown = runCatching {
            persist(store, writeQueue = true, writePlayback = false, queueState = null)
        }.exceptionOrNull()

        assertSame(markerError, thrown)
    }

    private suspend fun persist(
        store: RecordingStore,
        writeQueue: Boolean,
        writePlayback: Boolean,
        queueState: PersistedState? = queue,
        provider: (() -> PersistedState)? = null,
        onFailure: (Throwable) -> Unit = {}
    ) = persistPlaybackQueueWithRoomFallback(
        roomStore = store,
        legacyStore = legacyStore,
        queueState = queueState,
        playbackState = playback,
        shouldWriteQueueState = writeQueue,
        shouldWritePlaybackState = writePlayback,
        onRoomFailure = onFailure,
        queueStateProvider = provider
    )

    private class RecordingStore(
        private val failure: Throwable? = null,
        private val markerFailure: Throwable? = null
    ) : PlaybackQueueStateStore {
        val calls = mutableListOf<String>()
        var replacedSnapshot: PersistedState? = null

        override suspend fun replaceSnapshot(state: PersistedState, now: Long) {
            calls += "replaceSnapshot"
            replacedSnapshot = state
            failure?.let { throw it }
        }

        override suspend fun updatePlaybackState(state: PersistedPlaybackState, now: Long) {
            calls += "updatePlaybackState"
            failure?.let { throw it }
        }

        override suspend fun clear(now: Long) {
            calls += "clear"
            failure?.let { throw it }
        }

        override suspend fun markLegacyJsonPrimary(now: Long) {
            calls += "markLegacyJsonPrimary"
            markerFailure?.let { throw it }
        }
    }
}
