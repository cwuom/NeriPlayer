package moe.ouom.neriplayer.core.player.usb.sink

import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.usb.transport.usbRuntimeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveAudioSinkDecisionsTest {

    @Test
    fun `short focus failures are transport command failures`() {
        assertTrue("native_pause_failed".isShortFocusNativeFailure())
        assertTrue("native_PLAY_failed".isShortFocusNativeFailure())
        assertTrue("native_end_of_stream_start_failed".isShortFocusNativeFailure())
        assertTrue("transport_rejected".isShortFocusNativeFailure())
        assertFalse("native_flush_failed".isShortFocusNativeFailure())
    }

    @Test
    fun `high risk transfer failures include recoverable codes and libusb transfer markers`() {
        assertTrue("lastError=iso_packet_error".isHighRiskUsbTransferFailure())
        // permission wins the error-code mapping, so only the raw transfer marker can match
        assertTrue("permission LIBUSB_ERROR_IO".isHighRiskUsbTransferFailure())
        assertTrue("permission transfer_status=5".isHighRiskUsbTransferFailure())
        assertTrue("permission resubmit_failed".isHighRiskUsbTransferFailure())
        assertTrue("permission submit_failed".isHighRiskUsbTransferFailure())
        assertFalse("native_pause_failed".isHighRiskUsbTransferFailure())
    }

    @Test
    fun `native recovery is scheduled after failover only for non transport reasons`() {
        assertFalse("native_open_deferred:route_jitter".shouldScheduleNativeRecoveryAfterFailover())
        assertFalse("native_reopen_cooling_down".shouldScheduleNativeRecoveryAfterFailover())
        assertFalse("native_transport_failed".shouldScheduleNativeRecoveryAfterFailover())
        assertFalse("native_end_of_stream_start_failed".shouldScheduleNativeRecoveryAfterFailover())
        assertFalse("native_play_failed".shouldScheduleNativeRecoveryAfterFailover())
        assertTrue("native_flush_failed".shouldScheduleNativeRecoveryAfterFailover())
    }

    @Test
    fun `open gate retry covers transient gates cooldowns and disabled deferrals`() {
        assertTrue("native_open_deferred:route_jitter".shouldRetryAfterNativeOpenGate())
        assertTrue("native_reopen_cooling_down:3000".shouldRetryAfterNativeOpenGate())
        assertTrue("native_open_deferred:usb_exclusive_disabled".shouldRetryAfterNativeOpenGate())
        assertFalse("native_open_deferred:focus_loss".shouldRetryAfterNativeOpenGate())
        assertFalse("native_open_failed".shouldRetryAfterNativeOpenGate())
    }

    @Test
    fun `invalid and terminal structured reports are fatal`() {
        val invalid = "reportVersion=2 source=player_pcm"
        assertFalse(invalid.usbRuntimeMetrics().reportValid)
        assertTrue(isFatalNativeRuntime(invalid))

        val terminal = terminalV2Report()
        assertTrue(terminal.usbRuntimeMetrics().reportValid)
        assertTrue(isFatalNativeRuntime(terminal))
    }

    @Test
    fun `structured reports are fatal only for non deferred error codes`() {
        val healthy = healthyV2Report()
        assertTrue(healthy.usbRuntimeMetrics().reportValid)
        assertFalse(isFatalNativeRuntime(healthy))

        val deferred = "$healthy errorCode=OPEN_DEFERRED"
        assertTrue(deferred.usbRuntimeMetrics().reportValid)
        assertFalse(isFatalNativeRuntime(deferred))

        val rateUnsupported = "$healthy errorCode=SAMPLE_RATE_UNSUPPORTED"
        assertTrue(rateUnsupported.usbRuntimeMetrics().reportValid)
        assertTrue(isFatalNativeRuntime(rateUnsupported))
    }

    @Test
    fun `legacy reports keep benign backpressure alive and fail on errors`() {
        assertFalse(isFatalNativeRuntime(legacyFullQueueReport()))
        assertFalse(isFatalNativeRuntime("source=player_pcm running=true lastError=none"))
        assertTrue(isFatalNativeRuntime("source=player_pcm lastError=LIBUSB_ERROR_NO_DEVICE"))
        assertTrue(isFatalNativeRuntime("source=player_pcm transportFailed=true lastError=permission"))
        assertTrue(isFatalNativeRuntime("source=player_pcm lastError=sample_rate_unsupported"))
        assertFalse(isFatalNativeRuntime("source=player_pcm lastError= running=true"))
    }

    @Test
    fun `idle stalled writes flush only a full stopped healthy queue`() {
        val stopped = legacyFullQueueReport().replace("running=true", "running=false")
        assertTrue(shouldFlushIdleNativeQueueAfterStalledWrite(playing = false, runtimeReport = stopped))
        assertFalse(shouldFlushIdleNativeQueueAfterStalledWrite(playing = true, runtimeReport = stopped))
        assertFalse(
            shouldFlushIdleNativeQueueAfterStalledWrite(
                playing = false,
                runtimeReport = stopped.replace("source=player_pcm", "source=probe")
            )
        )
        assertFalse(shouldFlushIdleNativeQueueAfterStalledWrite(false, legacyFullQueueReport()))
        assertFalse(
            shouldFlushIdleNativeQueueAfterStalledWrite(
                playing = false,
                runtimeReport = stopped.replace("transportFailed=false", "transportFailed=true")
            )
        )
        assertFalse(
            shouldFlushIdleNativeQueueAfterStalledWrite(
                playing = false,
                runtimeReport = "source=player_pcm running=false pcmLevel=10/1024 pcmFreeBytes=1014"
            )
        )
    }

    @Test
    fun `first completion stall recovery waits for a started playing transport`() {
        assertTrue(isFirstCompletionStallRecoveryWindowOpen(true, true, 0, 1_000L, 1_220L))
        assertFalse(isFirstCompletionStallRecoveryWindowOpen(false, true, 0, 1_000L, 2_000L))
        assertFalse(isFirstCompletionStallRecoveryWindowOpen(true, false, 0, 1_000L, 2_000L))
        assertFalse(isFirstCompletionStallRecoveryWindowOpen(true, true, 1, 1_000L, 2_000L))
        assertFalse(isFirstCompletionStallRecoveryWindowOpen(true, true, 0, 0L, 2_000L))
        assertFalse(isFirstCompletionStallRecoveryWindowOpen(true, true, 0, 1_000L, 1_219L))
    }

    @Test
    fun `transport stalls before first completion only with transfers in flight and a full queue`() {
        val stalled = "source=player_pcm completedTransfers=0 inFlight=8 running=true " +
            "transportFailed=false pcmLevel=1024/1024 pcmFreeBytes=0 lastError=none"
        assertTrue(isNativeTransportStalledBeforeFirstCompletion(stalled))
        assertTrue(isNativeTransportStalledBeforeFirstCompletion(stalled.replace(" inFlight=8", "")))
        // a malformed counter invalidates the report itself
        assertFalse(isNativeTransportStalledBeforeFirstCompletion(stalled.replace("inFlight=8", "inFlight=many")))
        assertFalse(
            isNativeTransportStalledBeforeFirstCompletion(
                stalled.replace("completedTransfers=0", "completedTransfers=unknown")
            )
        )
        assertFalse(isNativeTransportStalledBeforeFirstCompletion(stalled.replace("source=player_pcm", "source=probe")))
        assertFalse(isNativeTransportStalledBeforeFirstCompletion(stalled.replace("completedTransfers=0", "completedTransfers=3")))
        assertFalse(isNativeTransportStalledBeforeFirstCompletion(stalled.replace("completedTransfers=0 ", "")))
        assertFalse(isNativeTransportStalledBeforeFirstCompletion(stalled.replace("inFlight=8", "inFlight=0")))
        assertFalse(isNativeTransportStalledBeforeFirstCompletion(stalled.replace("running=true", "running=false")))
        assertFalse(
            isNativeTransportStalledBeforeFirstCompletion(
                stalled.replace("transportFailed=false", "transportFailed=true")
            )
        )
        assertFalse(
            isNativeTransportStalledBeforeFirstCompletion(
                stalled.replace("pcmLevel=1024/1024 pcmFreeBytes=0", "pcmLevel=10/1024 pcmFreeBytes=1014")
            )
        )
        assertFalse(isNativeTransportStalledBeforeFirstCompletion(stalled.replace("lastError=none", "lastError=permission")))
    }

    @Test
    fun `short disruptions need a reopen for device loss and broken transfers`() {
        assertTrue("lastError=claim_interface".requiresNativeReopenForShortDisruption())
        assertTrue("permission transfer_status=5".requiresNativeReopenForShortDisruption())
        assertTrue("permission LIBUSB_ERROR_NO_DEVICE".requiresNativeReopenForShortDisruption())
        assertTrue("permission libusb_error_io".requiresNativeReopenForShortDisruption())
        assertTrue("permission submit_failed".requiresNativeReopenForShortDisruption())
        assertTrue("permission resubmit_failed".requiresNativeReopenForShortDisruption())
        assertFalse("native_pause_failed".requiresNativeReopenForShortDisruption())
    }

    @Test
    fun `transfer failures close the native path when the report shows a broken transport`() {
        assertTrue("source=player_pcm lastError=claim_interface".requiresNativeCloseForTransferFailure())
        assertTrue("permission transfer_status=5".requiresNativeCloseForTransferFailure())
        assertTrue("permission transportFailed=true".requiresNativeCloseForTransferFailure())
        assertTrue("lastError=permission inFlight=0".requiresNativeCloseForTransferFailure())
        assertTrue("lastError=permission LIBUSB_ERROR_ACCESS".requiresNativeCloseForTransferFailure())
        assertTrue("lastError=permission_transfer".requiresNativeCloseForTransferFailure())
        assertFalse("lastError=permission inFlight=4".requiresNativeCloseForTransferFailure())
        assertFalse("source=player_pcm lastError=none inFlight=0".requiresNativeCloseForTransferFailure())
        assertFalse("source=player_pcm inFlight=0".requiresNativeCloseForTransferFailure())
    }

    @Test
    fun `native failures retry unless they need a fresh open or are permanent`() {
        assertFalse(shouldRetryNativeFailure("LIBUSB_ERROR_NO_DEVICE"))
        assertFalse(shouldRetryNativeFailure("native_open_deferred:focus_loss"))
        assertFalse(shouldRetryNativeFailure("bit_depth_unsupported:32"))
        assertFalse(shouldRetryNativeFailure("no_selected_device"))
        assertFalse(shouldRetryNativeFailure("x_feedback_scheduler_busy"))
        assertFalse(shouldRetryNativeFailure("usb permission revoked"))
        assertTrue(shouldRetryNativeFailure("native_open_failed"))
    }

    @Test
    fun `format failures map to a user warning`() {
        assertEquals(
            CoreCommonR.string.settings_usb_exclusive_issue_sample_rate,
            nativeFormatWarningMessageResId("sample_rate_unsupported:384000")
        )
        assertEquals(
            CoreCommonR.string.settings_usb_exclusive_issue_bit_depth,
            nativeFormatWarningMessageResId("bit_depth_unsupported:32")
        )
        assertEquals(
            CoreCommonR.string.settings_usb_exclusive_issue_device,
            nativeFormatWarningMessageResId("channel_count_unsupported:8")
        )
        assertNull(nativeFormatWarningMessageResId("native_open_failed"))
    }

    private fun legacyFullQueueReport(): String =
        "source=player_pcm running=true transportFailed=false pcmLevel=1024/1024 " +
            "pcmFreeBytes=0 lastError=none"

    private fun healthyV2Report(): String = buildString {
        append("reportVersion=2 source=player_pcm syncType=adaptive running=true ")
        append("deviceOnline=true transportFailed=false feedbackMode=disabled ")
        append("feedbackState=disabled transportRunning=true feedbackReady=true ")
        append("realPcmReleased=true canAcceptPcm=true playbackReady=true ")
        append("feedbackReusable=true terminalFailure=false ")
        append("nativeStreamGeneration=9 candidateId=uac1-adaptive recoveryEpoch=4 ")
        append("recommendedAction=NONE actionId=0 actionGeneration=9 ")
        append("actionOwner=none actionLatched=false pcmLevel=0/1024 ")
        append("pcmFreeBytes=1024 pcmMaxLevelBytes=0 outputPeak=0.0 ")
        append("lastOutputPeak=0.0 lastError=none")
    }

    private fun terminalV2Report(): String = healthyV2Report()
        .replace("running=true", "running=false")
        .replace("transportRunning=true", "transportRunning=false")
        .replace("canAcceptPcm=true", "canAcceptPcm=false")
        .replace("playbackReady=true", "playbackReady=false")
        .replace("terminalFailure=false", "terminalFailure=true")
        .replace("recommendedAction=NONE", "recommendedAction=FRESH_OPEN")
        .replace("actionId=0", "actionId=17")
        .replace("actionOwner=none", "actionOwner=kotlin")
        .replace("actionLatched=false", "actionLatched=true")
}
