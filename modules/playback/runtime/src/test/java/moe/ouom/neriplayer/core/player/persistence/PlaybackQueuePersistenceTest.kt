package moe.ouom.neriplayer.core.player.persistence

import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.playback.PersistedPlaybackState
import moe.ouom.neriplayer.data.model.playback.PersistedState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackQueuePersistenceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `cancelled queue replacement does not write legacy files`() = runTest {
        assertCancellationDoesNotFallback(PersistedState(emptyList(), -1), writeQueue = true)
    }

    @Test
    fun `cancelled playback update does not write legacy files`() = runTest {
        assertCancellationDoesNotFallback(PersistedState(emptyList(), -1), writeQueue = false)
    }

    @Test
    fun `cancelled queue clear does not publish an empty legacy queue`() = runTest {
        assertCancellationDoesNotFallback(null, writeQueue = true)
    }

    private suspend fun assertCancellationDoesNotFallback(
        queue: PersistedState?,
        writeQueue: Boolean
    ) {
        val cancellation = CancellationException("superseded playback save")
        val failures = mutableListOf<Throwable>()
        val stateFile = File(temporaryFolder.root, "queue.json")
        val playbackFile = File(temporaryFolder.root, "playback.json")
        val store = object : PlaybackQueueStateStore {
            override suspend fun replaceSnapshot(state: PersistedState, now: Long): Unit =
                throw cancellation

            override suspend fun updatePlaybackState(state: PersistedPlaybackState, now: Long): Unit =
                throw cancellation

            override suspend fun clear(now: Long): Unit = throw cancellation

            override suspend fun markLegacyJsonPrimary(now: Long) {
                error("cancelled save must not change the primary store")
            }
        }

        val thrown = try {
            persistPlaybackQueueWithRoomFallback(
                roomStore = store,
                legacyStore = PlaybackQueueLegacyStore(stateFile, playbackFile, Gson()),
                queueState = queue,
                playbackState = PersistedPlaybackState(index = -1),
                shouldWriteQueueState = writeQueue,
                shouldWritePlaybackState = true,
                onRoomFailure = failures::add
            )
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, thrown)
        assertFalse(stateFile.exists())
        assertFalse(playbackFile.exists())
        assertTrue(failures.isEmpty())
    }
}
