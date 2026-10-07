package moe.ouom.neriplayer.data.local.audioimport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.local.LocalAudioScanPhase
import moe.ouom.neriplayer.data.model.local.LocalAudioScanProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class LocalAudioScanStageHeartbeatTest {
    @Test
    fun `slow stages keep reporting waiting heartbeats until the block returns`() = runTest {
        val released = CompletableDeferred<String>()
        val reported = CopyOnWriteArrayList<LocalAudioScanProgress>()
        val emitter = LocalAudioScanProgressEmitter(scanId = 9L, startedAt = 0L) { progress ->
            reported += progress
            if (reported.size == 3) released.complete("indexed")
        }
        emitter.lastProcessed = 4
        emitter.lastTotal = 12
        emitter.lastDiscoveredSongs = 2
        emitter.lastVisitedDirectories = 5

        val result = LocalAudioImportManager.awaitScanStage(
            emitter,
            LocalAudioScanPhase.QUERYING_MEDIA_STORE
        ) {
            released.await()
        }

        assertEquals("indexed", result)
        assertTrue(reported.size >= 3)
        assertEquals(
            setOf(
                LocalAudioScanProgress(
                    scanId = 9L,
                    phase = LocalAudioScanPhase.QUERYING_MEDIA_STORE,
                    processed = 4,
                    total = 12,
                    discoveredSongs = 2,
                    visitedDirectories = 5,
                    waitingForProvider = true
                )
            ),
            reported.map { it.copy(elapsedMs = 0L, phaseElapsedMs = 0L) }.toSet()
        )
        assertEquals(LocalAudioScanPhase.QUERYING_MEDIA_STORE, emitter.currentPhase)
    }

    @Test
    fun `stage failures surface after the waiting heartbeat`() = runTest {
        val reported = CopyOnWriteArrayList<LocalAudioScanProgress>()
        val emitter = LocalAudioScanProgressEmitter(scanId = 3L, startedAt = 0L) { reported += it }

        val failure = runCatching {
            LocalAudioImportManager.awaitScanStage<String>(emitter, LocalAudioScanPhase.TRAVERSING) {
                throw IOException("provider disconnected")
            }
        }.exceptionOrNull()

        val root = generateSequence(failure) { it.cause }.last()
        assertTrue(root is IOException)
        assertEquals("provider disconnected", root.message)
        assertEquals(LocalAudioScanPhase.TRAVERSING, reported.first().phase)
        assertTrue(reported.all { it.scanId == 3L && it.waitingForProvider })
    }
}
