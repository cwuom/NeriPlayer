package moe.ouom.neriplayer.core.player.policy.usb

import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveRuntimeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveAudioQualityRecoveryPolicyTest {

    @Test
    fun `startup zero fill before first transfer only establishes baseline`() {
        val first = evaluate(
            previous = UsbExclusiveAudioQualityRecoveryPolicy.reset(),
            nowMs = 10_120L,
            transportStartedAtMs = 10_000L,
            metrics = metrics(
                completedTransfers = 0L,
                playerUnderrunBytes = 57_344L,
                playerZeroFillBytes = 57_344L
            )
        )

        assertFalse(first.shouldRecover)
        assertEquals("baseline", first.reason)

        val second = evaluate(
            previous = first.state,
            nowMs = 11_300L,
            transportStartedAtMs = 10_000L,
            metrics = metrics(
                completedTransfers = 0L,
                playerUnderrunBytes = 65_536L,
                playerZeroFillBytes = 65_536L
            )
        )

        assertFalse(second.shouldRecover)
        assertEquals("startup_pcm_starvation", second.reason)
    }

    @Test
    fun `first stable zero fill increment arms recovery without reopening immediately`() {
        val baseline = evaluate(
            previous = UsbExclusiveAudioQualityRecoveryPolicy.reset(),
            nowMs = 2_100L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(completedTransfers = 24L)
        )

        val decision = evaluate(
            previous = baseline.state,
            nowMs = 2_150L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 25L,
                playerUnderrunBytes = 1_536L,
                playerZeroFillBytes = 1_536L
            )
        )

        assertFalse(decision.shouldRecover)
        assertEquals("armed_pcm_starvation", decision.reason)
        assertEquals(1, decision.state.consecutivePcmStarvationTicks)
    }

    @Test
    fun `consecutive stable zero fill increments recover native playback`() {
        val baseline = evaluate(
            previous = UsbExclusiveAudioQualityRecoveryPolicy.reset(),
            nowMs = 2_100L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(completedTransfers = 24L)
        )
        val armed = evaluate(
            previous = baseline.state,
            nowMs = 2_150L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 25L,
                playerUnderrunBytes = 1_536L,
                playerZeroFillBytes = 1_536L
            )
        )

        val decision = evaluate(
            previous = armed.state,
            nowMs = 2_200L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 26L,
                playerUnderrunBytes = 3_072L,
                playerZeroFillBytes = 3_072L
            )
        )

        assertTrue(decision.shouldRecover)
        assertEquals("player_pcm_starvation", decision.reason)
    }

    @Test
    fun `cached zero fill report keeps armed recovery tick until fresh counters arrive`() {
        val baseline = evaluate(
            previous = UsbExclusiveAudioQualityRecoveryPolicy.reset(),
            nowMs = 2_100L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(completedTransfers = 24L)
        )
        val armed = evaluate(
            previous = baseline.state,
            nowMs = 2_150L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 25L,
                playerUnderrunBytes = 1_536L,
                playerZeroFillBytes = 1_536L
            )
        )

        val cached = evaluate(
            previous = armed.state,
            nowMs = 2_200L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 25L,
                playerUnderrunBytes = 1_536L,
                playerZeroFillBytes = 1_536L
            )
        )

        assertFalse(cached.shouldRecover)
        assertEquals("awaiting_pcm_starvation_sample", cached.reason)
        assertEquals(1, cached.state.consecutivePcmStarvationTicks)

        val decision = evaluate(
            previous = cached.state,
            nowMs = 4_200L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 26L,
                playerUnderrunBytes = 3_072L,
                playerZeroFillBytes = 3_072L
            )
        )

        assertTrue(decision.shouldRecover)
        assertEquals("player_pcm_starvation", decision.reason)
    }

    @Test
    fun `fresh healthy sample clears armed zero fill recovery tick`() {
        val baseline = evaluate(
            previous = UsbExclusiveAudioQualityRecoveryPolicy.reset(),
            nowMs = 2_100L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(completedTransfers = 24L)
        )
        val armed = evaluate(
            previous = baseline.state,
            nowMs = 2_150L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 25L,
                playerUnderrunBytes = 1_536L,
                playerZeroFillBytes = 1_536L
            )
        )

        val healthy = evaluate(
            previous = armed.state,
            nowMs = 2_200L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 26L,
                playerUnderrunBytes = 1_536L,
                playerZeroFillBytes = 1_536L
            )
        )

        assertFalse(healthy.shouldRecover)
        assertEquals("healthy", healthy.reason)
        assertEquals(0, healthy.state.consecutivePcmStarvationTicks)
    }

    @Test
    fun `large stable zero fill gap recovers without waiting another tick`() {
        val baseline = evaluate(
            previous = UsbExclusiveAudioQualityRecoveryPolicy.reset(),
            nowMs = 2_100L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(completedTransfers = 24L)
        )

        val decision = evaluate(
            previous = baseline.state,
            nowMs = 2_150L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 25L,
                playerUnderrunBytes = 80_000L,
                playerZeroFillBytes = 80_000L
            )
        )

        assertTrue(decision.shouldRecover)
        assertEquals("player_pcm_starvation", decision.reason)
    }

    @Test
    fun `dropped bytes after startup recover native playback`() {
        val baseline = evaluate(
            previous = UsbExclusiveAudioQualityRecoveryPolicy.reset(),
            nowMs = 2_100L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(completedTransfers = 24L)
        )

        val decision = evaluate(
            previous = baseline.state,
            nowMs = 2_150L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 25L,
                playerDroppedBytes = 384L
            )
        )

        assertTrue(decision.shouldRecover)
        assertEquals("player_pcm_dropped", decision.reason)
    }

    @Test
    fun `historical iso packet errors do not repeat recovery without a new increment`() {
        val historical = evaluate(
            previous = UsbExclusiveAudioQualityRecoveryPolicy.reset(),
            nowMs = 2_100L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 24L,
                isoPacketErrors = 2L,
                isoPacketErrorTransfers = 1L,
                isoPacketErrorScore = 2
            )
        )
        val repeated = evaluate(
            previous = historical.state,
            nowMs = 2_200L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 25L,
                isoPacketErrors = 2L,
                isoPacketErrorTransfers = 1L,
                isoPacketErrorScore = 2
            )
        )

        assertFalse(repeated.shouldRecover)

        val incremented = evaluate(
            previous = repeated.state,
            nowMs = 2_300L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 26L,
                isoPacketErrors = 3L,
                isoPacketErrorTransfers = 2L,
                isoPacketErrorScore = 3
            )
        )

        assertTrue(incremented.shouldRecover)
        assertEquals("iso_packet_error", incremented.reason)
    }

    private fun evaluate(
        previous: UsbExclusiveAudioQualityRecoveryState,
        metrics: UsbExclusiveRuntimeMetrics,
        nowMs: Long,
        transportStartedAtMs: Long
    ): UsbExclusiveAudioQualityRecoveryDecision {
        return UsbExclusiveAudioQualityRecoveryPolicy.evaluate(
            previous = previous,
            handle = 15L,
            metrics = metrics,
            nowMs = nowMs,
            transportStartedAtMs = transportStartedAtMs
        )
    }

    private fun metrics(
        completedTransfers: Long,
        isoPacketErrors: Long = 0L,
        isoPacketErrorTransfers: Long = 0L,
        isoPacketErrorScore: Int = 0,
        playerDroppedBytes: Long = 0L,
        playerUnderrunBytes: Long = 0L,
        playerZeroFillBytes: Long = 0L
    ): UsbExclusiveRuntimeMetrics {
        return UsbExclusiveRuntimeMetrics(
            source = "player_pcm",
            sampleRate = 96_000,
            channelCount = 2,
            subslotBytes = 4,
            completedTransfers = completedTransfers,
            isoPacketErrors = isoPacketErrors,
            isoPacketErrorTransfers = isoPacketErrorTransfers,
            isoPacketErrorScore = isoPacketErrorScore,
            playerDroppedBytes = playerDroppedBytes,
            playerUnderrunBytes = playerUnderrunBytes,
            playerZeroFillBytes = playerZeroFillBytes,
            transportFailed = false,
            running = true,
            paused = false,
            lastError = "none"
        )
    }
}
