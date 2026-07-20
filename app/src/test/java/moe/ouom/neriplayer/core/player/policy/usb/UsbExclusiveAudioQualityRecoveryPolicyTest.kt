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
    fun `consecutive small zero fill increments do not reopen healthy transport`() {
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

        assertFalse("reason=${decision.reason} debug=${decision.debug}", decision.shouldRecover)
        assertEquals("persistent_pcm_starvation", decision.reason)
    }

    @Test
    fun `minor background starvation with audible signal never reopens healthy transport`() {
        val baseline = evaluate(
            previous = UsbExclusiveAudioQualityRecoveryPolicy.reset(),
            nowMs = 30_000L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                sampleRate = 44_100,
                subslotBytes = 2,
                completedTransfers = 4_000L,
                playerSignalBytes = 5_500_000L,
                playerUnderrunBytes = 11_840L,
                playerZeroFillBytes = 11_840L,
                lastOutputPeak = 0.2f
            )
        )
        val firstTick = evaluate(
            previous = baseline.state,
            nowMs = 45_000L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                sampleRate = 44_100,
                subslotBytes = 2,
                completedTransfers = 4_534L,
                playerSignalBytes = 6_411_624L,
                playerUnderrunBytes = 14_952L,
                playerZeroFillBytes = 14_952L,
                lastOutputPeak = 0.007751f
            )
        )
        val secondTick = evaluate(
            previous = firstTick.state,
            nowMs = 60_000L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                sampleRate = 44_100,
                subslotBytes = 2,
                completedTransfers = 5_100L,
                playerSignalBytes = 7_100_000L,
                playerUnderrunBytes = 17_912L,
                playerZeroFillBytes = 17_912L,
                lastOutputPeak = 0.01f
            )
        )

        assertFalse(firstTick.shouldRecover)
        assertEquals("minor_pcm_starvation_with_signal", firstTick.reason)
        assertEquals(1, firstTick.state.consecutivePcmStarvationTicks)
        assertFalse(secondTick.shouldRecover)
        assertEquals("persistent_pcm_starvation", secondTick.reason)
        assertEquals(2, secondTick.state.consecutivePcmStarvationTicks)
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

        assertFalse("reason=${decision.reason} debug=${decision.debug}", decision.shouldRecover)
        assertEquals("persistent_pcm_starvation", decision.reason)
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
    fun `large stable zero fill gap does not reopen a healthy native transport`() {
        val baseline = evaluate(
            previous = UsbExclusiveAudioQualityRecoveryPolicy.reset(),
            nowMs = 2_100L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 24L,
                sampleRate = 192_000,
                playerSignalBytes = 2_503_168L,
                lastOutputPeak = 0.06f
            )
        )

        val decision = evaluate(
            previous = baseline.state,
            nowMs = 2_150L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 25L,
                sampleRate = 192_000,
                playerSignalBytes = 3_054_592L,
                playerUnderrunBytes = 565_808L,
                playerZeroFillBytes = 565_808L,
                lastOutputPeak = 0.06f
            )
        )

        assertFalse(decision.shouldRecover)
        assertEquals("large_pcm_starvation", decision.reason)
        assertTrue(decision.debug.contains("reopenSuppressed=true"))
    }

    @Test
    fun `consecutive dropped bytes after startup recover native playback`() {
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
                playerDroppedBytes = 384L
            )
        )

        assertFalse(armed.shouldRecover)
        assertEquals("armed_player_drop", armed.reason)

        val decision = evaluate(
            previous = armed.state,
            nowMs = 2_200L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 26L,
                playerDroppedBytes = 768L
            )
        )

        assertTrue("reason=${decision.reason} debug=${decision.debug}", decision.shouldRecover)
        assertEquals("player_pcm_dropped", decision.reason)
    }

    @Test
    fun `cached dropped byte sample keeps recovery armed until counters refresh`() {
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
                playerDroppedBytes = 384L
            )
        )

        val cached = evaluate(
            previous = armed.state,
            nowMs = 2_200L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 25L,
                playerDroppedBytes = 384L
            )
        )

        assertFalse(cached.shouldRecover)
        assertEquals("awaiting_player_drop_sample", cached.reason)
        assertEquals(1, cached.state.consecutivePlayerDropTicks)

        val decision = evaluate(
            previous = cached.state,
            nowMs = 2_250L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 26L,
                playerDroppedBytes = 768L
            )
        )

        assertTrue("reason=${decision.reason} debug=${decision.debug}", decision.shouldRecover)
        assertEquals("player_pcm_dropped", decision.reason)
    }

    @Test
    fun `historical iso packet errors do not repeat recovery without a burst`() {
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

        val minorIncrement = evaluate(
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

        assertFalse(minorIncrement.shouldRecover)
        assertEquals("minor_iso_packet_error", minorIncrement.reason)

        val burst = evaluate(
            previous = minorIncrement.state,
            nowMs = 2_400L,
            transportStartedAtMs = 1_000L,
            metrics = metrics(
                completedTransfers = 27L,
                isoPacketErrors = 6L,
                isoPacketErrorTransfers = 3L,
                isoPacketErrorScore = 4
            )
        )

        assertTrue(burst.shouldRecover)
        assertEquals("iso_packet_error", burst.reason)
    }

    @Test
    fun `startup iso packet error does not reopen native playback immediately`() {
        val baseline = evaluate(
            previous = UsbExclusiveAudioQualityRecoveryPolicy.reset(),
            nowMs = 10_100L,
            transportStartedAtMs = 10_000L,
            metrics = metrics(completedTransfers = 0L)
        )

        val decision = evaluate(
            previous = baseline.state,
            nowMs = 10_450L,
            transportStartedAtMs = 10_000L,
            metrics = metrics(
                completedTransfers = 56L,
                isoPacketErrors = 1L,
                isoPacketErrorTransfers = 1L,
                isoPacketErrorScore = 1
            )
        )

        assertFalse(decision.shouldRecover)
        assertEquals("startup_iso_packet_error", decision.reason)
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
        sampleRate: Int = 96_000,
        subslotBytes: Int = 4,
        isoPacketErrors: Long = 0L,
        isoPacketErrorTransfers: Long = 0L,
        isoPacketErrorScore: Int = 0,
        playerSignalBytes: Long? = null,
        playerDroppedBytes: Long = 0L,
        playerUnderrunBytes: Long = 0L,
        playerZeroFillBytes: Long = 0L,
        lastOutputPeak: Float? = null
    ): UsbExclusiveRuntimeMetrics {
        return UsbExclusiveRuntimeMetrics(
            source = "player_pcm",
            sampleRate = sampleRate,
            channelCount = 2,
            subslotBytes = subslotBytes,
            completedTransfers = completedTransfers,
            isoPacketErrors = isoPacketErrors,
            isoPacketErrorTransfers = isoPacketErrorTransfers,
            isoPacketErrorScore = isoPacketErrorScore,
            playerSignalBytes = playerSignalBytes,
            playerDroppedBytes = playerDroppedBytes,
            playerUnderrunBytes = playerUnderrunBytes,
            playerZeroFillBytes = playerZeroFillBytes,
            lastOutputPeak = lastOutputPeak,
            transportFailed = false,
            running = true,
            paused = false,
            lastError = "none"
        )
    }
}
