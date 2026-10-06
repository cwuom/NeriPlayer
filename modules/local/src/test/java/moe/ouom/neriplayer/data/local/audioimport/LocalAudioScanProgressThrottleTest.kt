package moe.ouom.neriplayer.data.local.audioimport

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.local.LocalAudioScanPhase
import moe.ouom.neriplayer.data.model.local.LocalAudioScanProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Mockito.mockStatic

class LocalAudioScanProgressThrottleTest {
    @Test
    fun `progress is throttled inside the report interval unless forced`() {
        val reported = mutableListOf<LocalAudioScanProgress>()
        val emitter = LocalAudioScanProgressEmitter(scanId = 9L, startedAt = 1_000L, onProgress = reported::add)

        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }
                .thenReturn(1_020L, 1_040L, 1_045L, 1_200L, 1_210L)

            emitter.emit(LocalAudioScanPhase.TRAVERSING, 1, 10, 0, 1)
            emitter.emit(LocalAudioScanPhase.TRAVERSING, 2, 10, 1, 2)
            emitter.emit(LocalAudioScanPhase.TRAVERSING, 3, 10, 2, 3, force = true)
            emitter.emit(LocalAudioScanPhase.HYDRATING_METADATA, 0, 4, 2, 3)
            emitter.emitWaitingHeartbeat(LocalAudioScanPhase.HYDRATING_METADATA)
        }

        assertEquals(
            listOf(
                progress(LocalAudioScanPhase.TRAVERSING, 1, 10, 0, 1, elapsedMs = 20L, phaseElapsedMs = 0L),
                progress(LocalAudioScanPhase.TRAVERSING, 3, 10, 2, 3, elapsedMs = 45L, phaseElapsedMs = 25L),
                progress(LocalAudioScanPhase.HYDRATING_METADATA, 0, 4, 2, 3, elapsedMs = 200L, phaseElapsedMs = 0L),
                progress(
                    LocalAudioScanPhase.HYDRATING_METADATA,
                    0,
                    4,
                    2,
                    3,
                    elapsedMs = 210L,
                    phaseElapsedMs = 10L,
                    waitingForProvider = true
                )
            ),
            reported
        )
    }

    @Test
    fun `failing progress callbacks do not interrupt the scan`() {
        var attempts = 0
        val emitter = LocalAudioScanProgressEmitter(scanId = 1L, startedAt = 0L) {
            attempts += 1
            throw IllegalStateException("ui gone")
        }

        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(100L)

            emitter.emit(LocalAudioScanPhase.QUERYING_MEDIA_STORE, 5, 8, 5, 0, force = true)
        }

        assertEquals(1, attempts)
        assertEquals(LocalAudioScanPhase.QUERYING_MEDIA_STORE, emitter.currentPhase)
        assertEquals(5, emitter.lastProcessed)
    }

    @Test
    fun `cancellation from the progress callback is propagated`() {
        val emitter = LocalAudioScanProgressEmitter(scanId = 1L, startedAt = 0L) {
            throw CancellationException("scan cancelled")
        }

        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(100L)

            assertThrows(CancellationException::class.java) {
                emitter.emit(LocalAudioScanPhase.TRAVERSING, 1, 1, 1, 1, force = true)
            }
        }
    }

    private fun progress(
        phase: LocalAudioScanPhase,
        processed: Int,
        total: Int,
        discoveredSongs: Int,
        visitedDirectories: Int,
        elapsedMs: Long,
        phaseElapsedMs: Long,
        waitingForProvider: Boolean = false
    ) = LocalAudioScanProgress(
        scanId = 9L,
        phase = phase,
        processed = processed,
        total = total,
        discoveredSongs = discoveredSongs,
        visitedDirectories = visitedDirectories,
        elapsedMs = elapsedMs,
        phaseElapsedMs = phaseElapsedMs,
        waitingForProvider = waitingForProvider
    )
}
