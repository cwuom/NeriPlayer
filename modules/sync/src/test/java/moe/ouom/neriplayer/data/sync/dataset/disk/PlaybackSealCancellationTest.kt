package moe.ouom.neriplayer.data.sync.dataset.disk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncDataset
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackSealCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `canceled empty legacy staging releases its lease before dispatcher return`() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileSyncPlaybackDatasetStore(directory)
        var unreceived: SyncDataset? = null
        try {
            val failure = runCatching {
                withContext(Dispatchers.Default) {
                    currentCoroutineContext().cancel()
                    // 只保留引用以便 red 失败后清理，caller 仍无法收到这个返回值
                    store.fromLegacy(SyncData()).also { unreceived = it }
                }
            }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertTrue("Canceled empty staging retained an active dataset lease", directory.listFiles().orEmpty().isEmpty())
            assertNull(unreceived)
        } finally {
            unreceived?.close()
        }
    }

    @Test
    fun `canceled tracks only single run releases its lease before dispatcher return`() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileSyncPlaybackDatasetStore(directory)
        var unreceived: SyncPlaybackSource? = null
        try {
            val failure = runCatching {
                store.newSink().use { sink ->
                    sink.appendTracks(listOf(SyncTrackStat(identityKey = "only-track")))
                    withContext(Dispatchers.Default) {
                        currentCoroutineContext().cancel()
                        sink.seal().also { unreceived = it }
                    }
                }
            }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertTrue("Canceled single-run staging retained an active dataset lease", directory.listFiles().orEmpty().isEmpty())
            assertNull(unreceived)
        } finally {
            unreceived?.close()
        }
    }

    @Test
    fun `ordered empty and tracks only staging refuse canceled transfer and release their leases`() = runBlocking {
        for (tracksOnly in listOf(false, true)) {
            val directory = temporary.newFolder()
            val store = FileSyncPlaybackDatasetStore(directory)
            var unreceived: SyncPlaybackSource? = null
            try {
                val failure = runCatching {
                    store.newOrderedSink().use { sink ->
                        if (tracksOnly) sink.appendTracks(listOf(SyncTrackStat(identityKey = "only-track")))
                        withContext(Dispatchers.Default) {
                            currentCoroutineContext().cancel()
                            sink.seal().also { unreceived = it }
                        }
                    }
                }.exceptionOrNull()

                assertTrue(failure is CancellationException)
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                assertNull(unreceived)
            } finally {
                unreceived?.close()
            }
        }
    }

    @Test
    fun `empty and buffered sorter finish detect cancellation before creating a final run`() = runBlocking {
        for (buffered in listOf(false, true)) {
            val directory = temporary.newFolder()
            var unreceived: PlaybackFile? = null
            val failure = runCatching {
                PlaybackFileSorter(directory, "tracks", trackCodec, dayFirst = false).use { sorter ->
                    if (buffered) sorter.append(SyncTrackStat(identityKey = "only-track"))
                    withContext(Dispatchers.Default) {
                        currentCoroutineContext().cancel()
                        sorter.finish().also { unreceived = it }
                    }
                }
            }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertNull("Canceled finish returned a run before prompt cancellation discarded it", unreceived)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }
}
