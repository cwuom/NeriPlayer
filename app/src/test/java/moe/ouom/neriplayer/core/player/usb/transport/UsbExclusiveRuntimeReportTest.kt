package moe.ouom.neriplayer.core.player.usb.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveRuntimeReportTest {

    @Test
    fun `runtime metrics use explicit free bytes for queue full detection`() {
        val metrics = buildString {
            append("source=player_pcm sampleRate=48000 channels=2 subslotBytes=2 ")
            append("transferBytes=3072 lastTransferBytes=3072 ")
            append("pcmLevel=287000/288000 pcmFreeBytes=0 ")
            append("pcmBackpressureEvents=3 pcmBackpressureCurrentMs=120 ")
            append("pcmBackpressureMaxMs=240 running=true transportFailed=false lastError=none")
        }.usbRuntimeMetrics()

        assertTrue(metrics.isQueueFull)
        assertTrue(metrics.isBenignBackpressure)
        assertEquals(0L, metrics.pcmFreeBytes)
        assertEquals(3L, metrics.pcmBackpressureEvents)
        assertEquals(120L, metrics.pcmBackpressureCurrentMs)
        assertEquals(240L, metrics.pcmBackpressureMaxMs)
        assertEquals(48000, metrics.sampleRate)
        assertEquals(2, metrics.channelCount)
        assertEquals(2, metrics.subslotBytes)
        assertEquals(4, metrics.outputFrameBytes)
        assertEquals(3072L, metrics.transferBytes)
        assertEquals(3072L, metrics.lastTransferBytes)
    }

    @Test
    fun `runtime metrics fall back to pcm level for old reports`() {
        val metrics = buildString {
            append("source=player_pcm pcmLevel=288000/288000 ")
            append("running=true transportFailed=false lastError=none")
        }.usbRuntimeMetrics()

        assertTrue(metrics.isQueueFull)
        assertTrue(metrics.isBenignBackpressure)
    }

    @Test
    fun `transport failure is never treated as benign backpressure`() {
        val metrics = buildString {
            append("source=player_pcm pcmLevel=288000/288000 pcmFreeBytes=0 ")
            append("running=true transportFailed=true lastError=transfer_status=5")
        }.usbRuntimeMetrics()

        assertTrue(metrics.isQueueFull)
        assertFalse(metrics.isBenignBackpressure)
        assertEquals(UsbExclusiveErrorCode.TransportFailed, metrics.errorCode)
    }

    @Test
    fun `runtime metrics expose recoverable iso packet errors`() {
        val metrics = buildString {
            append("source=player_pcm isoPacketErrors=2 isoPacketErrorTransfers=1 ")
            append("isoPacketErrorScore=2 running=true transportFailed=false lastError=none")
        }.usbRuntimeMetrics()

        assertEquals(2L, metrics.isoPacketErrors)
        assertEquals(1L, metrics.isoPacketErrorTransfers)
        assertEquals(2, metrics.isoPacketErrorScore)
        assertTrue(metrics.hasHealthyTransport)
        assertTrue(metrics.hasPlayerPcmAudioQualityDegradation)
    }

    @Test
    fun `runtime metrics expose player quality counters`() {
        val metrics = buildString {
            append("source=player_pcm running=true paused=false ")
            append("playerDroppedBytes=384 playerUnderrunBytes=768 ")
            append("playerZeroFillBytes=768 playerPausedZeroFillBytes=1536 lastError=none")
        }.usbRuntimeMetrics()

        assertEquals(384L, metrics.playerDroppedBytes)
        assertEquals(768L, metrics.playerUnderrunBytes)
        assertEquals(768L, metrics.playerZeroFillBytes)
        assertEquals(1536L, metrics.playerPausedZeroFillBytes)
        assertTrue(metrics.hasPlayerPcmAudioQualityDegradation)
        assertTrue(metrics.hasPlayerPcmBufferStarvationCounters)
    }

    @Test
    fun `active zero fill counters need runtime policy before declaring degradation`() {
        val metrics = buildString {
            append("source=player_pcm running=true paused=false ")
            append("playerDroppedBytes=0 playerUnderrunBytes=768 ")
            append("playerZeroFillBytes=768 lastError=none")
        }.usbRuntimeMetrics()

        assertFalse(metrics.hasPlayerPcmAudioQualityDegradation)
        assertTrue(metrics.hasPlayerPcmBufferStarvationCounters)
    }

    @Test
    fun `paused zero fill does not mark active audio as degraded`() {
        val metrics = buildString {
            append("source=player_pcm running=true paused=true ")
            append("playerPausedZeroFillBytes=4096 playerZeroFillBytes=0 lastError=none")
        }.usbRuntimeMetrics()

        assertFalse(metrics.hasPlayerPcmAudioQualityDegradation)
        assertFalse(metrics.hasPlayerPcmBufferStarvationCounters)
    }

    @Test
    fun `runtime metrics classify feedback scheduler gaps`() {
        val metrics = "lastError=async_feedback_scheduler_unavailable".usbRuntimeMetrics()

        assertEquals(UsbExclusiveErrorCode.AsyncFeedbackUnsupported, metrics.errorCode)
        assertFalse(metrics.hasHealthyTransport)
    }

    @Test
    fun `runtime metrics classify first completion timeout`() {
        val metrics = buildString {
            append("source=player_pcm running=false transportFailed=true ")
            append("lastError=event_loop_first_completion_timeout")
        }.usbRuntimeMetrics()

        assertEquals(UsbExclusiveErrorCode.TransferFirstCompletionTimeout, metrics.errorCode)
        assertTrue(metrics.errorCode.requiresFreshNativeOpen)
    }

    @Test
    fun `runtime metrics classify native open deferred as non fatal gate`() {
        val metrics = "native_open_deferred:native_close_in_flight count=1".usbRuntimeMetrics()

        assertEquals(UsbExclusiveErrorCode.OpenDeferred, metrics.errorCode)
        assertFalse(metrics.errorCode.requiresFreshNativeOpen)
    }

    @Test
    fun `v2 explicit feedback report exposes typed readiness and reuse gate`() {
        val metrics = buildString {
            append("reportVersion=2 source=player_pcm syncType=async ")
            append("feedbackMode=explicit feedbackEndpoint=0x84 feedbackState=Locked ")
            append("feedbackClockFailure=holdover_timeout ")
            append("transportRunning=true feedbackReady=true realPcmReleased=true ")
            append("canAcceptPcm=true playbackReady=true feedbackReusable=true ")
            append("terminalFailure=false nativeStreamGeneration=9 candidateId=cs43131 ")
            append("recoveryEpoch=4 recommendedAction=Holdover actionId=7 ")
            append("actionGeneration=9 actionOwner=native actionLatched=false ")
            append("lastError=none")
        }.usbRuntimeMetrics()

        assertEquals(2, metrics.reportVersion)
        assertTrue(metrics.reportValid)
        assertEquals(UsbExclusiveFeedbackMode.Explicit, metrics.feedbackMode)
        assertEquals(0x84, metrics.feedbackEndpointAddress)
        assertEquals(UsbExclusiveFeedbackState.Locked, metrics.feedbackState)
        assertEquals(
            UsbExclusiveFeedbackClockFailure.HoldoverTimeout,
            metrics.feedbackClockFailure
        )
        assertEquals(UsbExclusiveRecoveryAction.Holdover, metrics.recommendedAction)
        assertEquals(UsbExclusiveRecoveryActionOwner.Native, metrics.actionOwner)
        assertTrue(metrics.playbackReady == true)
        assertTrue(metrics.hasHealthyTransport)
        assertTrue(metrics.canReuseNativePlayerSession)
    }

    @Test
    fun `v2 acquiring report stays nonfatal but not yet reusable`() {
        val metrics = buildString {
            append("reportVersion=2 source=player_pcm syncType=async ")
            append("feedbackMode=explicit feedbackEndpoint=0x84 feedbackState=Acquiring ")
            append("transportRunning=true feedbackReady=false realPcmReleased=false ")
            append("canAcceptPcm=false playbackReady=false feedbackReusable=false ")
            append("terminalFailure=false nativeStreamGeneration=9 candidateId=cs43131 ")
            append("recoveryEpoch=4 recommendedAction=Holdover actionId=7 ")
            append("actionGeneration=9 actionOwner=native actionLatched=false ")
            append("lastError=none")
        }.usbRuntimeMetrics()

        assertEquals(UsbExclusiveFeedbackState.Acquiring, metrics.feedbackState)
        assertFalse(metrics.hasHealthyTransport)
        assertFalse(metrics.canReuseNativePlayerSession)
        assertEquals(UsbExclusiveErrorCode.None, metrics.errorCode)
    }

    @Test
    fun `v2 duplicate or unknown typed fields fail closed`() {
        val duplicateMetrics = buildString {
            append("reportVersion=2 source=player_pcm ")
            append("feedbackMode=explicit feedbackMode=locked feedbackState=Locked ")
            append("transportRunning=true feedbackReady=true realPcmReleased=true ")
            append("canAcceptPcm=true playbackReady=true feedbackReusable=true ")
            append("terminalFailure=false nativeStreamGeneration=1 candidateId=cs43131 ")
            append("recoveryEpoch=2 recommendedAction=Holdover actionId=1 ")
            append("actionGeneration=1 actionOwner=native actionLatched=false lastError=none")
        }.usbRuntimeMetrics()

        assertFalse(duplicateMetrics.reportValid)
        assertEquals(UsbExclusiveErrorCode.NativeInternalError, duplicateMetrics.errorCode)

        val unknownMetrics = buildString {
            append("reportVersion=2 source=player_pcm ")
            append("feedbackMode=explicit feedbackState=Locked ")
            append("transportRunning=true feedbackReady=true realPcmReleased=true ")
            append("canAcceptPcm=true playbackReady=true feedbackReusable=true ")
            append("terminalFailure=false nativeStreamGeneration=1 candidateId=cs43131 ")
            append("recoveryEpoch=2 recommendedAction=Holdover actionId=1 ")
            append("actionGeneration=1 actionOwner=native actionLatched=false ")
            append("errorCode=not_a_real_error")
        }.usbRuntimeMetrics()

        assertFalse(unknownMetrics.reportValid)
        assertEquals(UsbExclusiveErrorCode.NativeInternalError, unknownMetrics.errorCode)
    }

    @Test
    fun `runtime metrics expose structured native fields`() {
        val metrics = buildString {
            append("source=player_pcm uacVersion=UAC2 syncType=adaptive feedback=none ")
            append("sampleRate=96000 channels=2 subslotBytes=3 ")
            append("completedTransfers=42 inFlight=8 deviceOnline=true paused=false ")
            append("running=true transportFailed=false lastError=none")
        }.usbRuntimeMetrics()

        assertEquals("player_pcm", metrics.source)
        assertEquals("UAC2", metrics.uacVersion)
        assertEquals("adaptive", metrics.syncType)
        assertEquals("none", metrics.feedback)
        assertEquals(42L, metrics.completedTransfers)
        assertEquals(8, metrics.inFlightTransfers)
        assertEquals(true, metrics.deviceOnline)
        assertEquals(false, metrics.paused)
        assertEquals(UsbExclusiveErrorCode.None, metrics.errorCode)
        assertTrue(metrics.hasHealthyTransport)
    }

    @Test
    fun `recovery action ack status parser accepts native tokens`() {
        assertEquals(
            UsbExclusiveRecoveryActionAckStatus.Acked,
            "ACKED".toUsbExclusiveRecoveryActionAckStatusOrNull()
        )
        assertEquals(
            UsbExclusiveRecoveryActionAckStatus.GenerationMismatch,
            "generation_mismatch".toUsbExclusiveRecoveryActionAckStatusOrNull()
        )
        assertEquals(null, "unexpected".toUsbExclusiveRecoveryActionAckStatusOrNull())
    }

    @Test
    fun `device offline report is classified as detached`() {
        val metrics = buildString {
            append("source=player_pcm deviceOnline=false running=false ")
            append("transportFailed=true lastError=LIBUSB_ERROR_NO_DEVICE")
        }.usbRuntimeMetrics()

        assertEquals(UsbExclusiveErrorCode.DeviceDetached, metrics.errorCode)
        assertFalse(metrics.hasHealthyTransport)
        assertTrue(metrics.errorCode.requiresFreshNativeOpen)
    }

    @Test
    fun `transport failed flag is classified even when last error is none`() {
        val metrics = "source=player_pcm transportFailed=true running=true lastError=none"
            .usbRuntimeMetrics()

        assertEquals(UsbExclusiveErrorCode.TransportFailed, metrics.errorCode)
        assertFalse(metrics.hasHealthyTransport)
    }

    @Test
    fun `legacy async reports cannot reuse the native session`() {
        val metrics = buildString {
            append("source=player_pcm syncType=async feedback=explicit:0x84 ")
            append("running=true transportFailed=false lastError=none")
        }.usbRuntimeMetrics()

        assertFalse(metrics.canReuseNativePlayerSession)
        assertEquals(UsbExclusiveErrorCode.None, metrics.errorCode)
    }

    @Test
    fun `legacy asynchronous token also fails closed for session reuse`() {
        val metrics = buildString {
            append("source=player_pcm syncType=asynchronous feedback=none ")
            append("running=true transportFailed=false lastError=none")
        }.usbRuntimeMetrics()

        assertFalse(metrics.canReuseNativePlayerSession)
        assertEquals(UsbExclusiveErrorCode.None, metrics.errorCode)
    }

    @Test
    fun `v2 readiness must match the contract formula`() {
        val metrics = validDisabledV2Report()
            .replace("transportRunning=true", "transportRunning=false")
            .usbRuntimeMetrics()

        assertFalse(metrics.reportValid)
        assertEquals("inconsistent_playbackReady", metrics.reportInvalidReason)
        assertEquals(UsbExclusiveErrorCode.NativeInternalError, metrics.errorCode)
    }

    @Test
    fun `v2 queue and peak fields reject impossible values`() {
        val negativeLevel = validDisabledV2Report()
            .replace("pcmLevel=0/1024", "pcmLevel=-1/1024")
            .usbRuntimeMetrics()
        val inconsistentFree = validDisabledV2Report()
            .replace("pcmFreeBytes=1024", "pcmFreeBytes=512")
            .usbRuntimeMetrics()
        val nonFinitePeak = validDisabledV2Report()
            .replace("outputPeak=0.0", "outputPeak=NaN")
            .usbRuntimeMetrics()

        assertFalse(negativeLevel.reportValid)
        assertEquals("invalid_pcmLevel", negativeLevel.reportInvalidReason)
        assertFalse(inconsistentFree.reportValid)
        assertEquals("inconsistent_pcmFreeBytes", inconsistentFree.reportInvalidReason)
        assertFalse(nonFinitePeak.reportValid)
        assertEquals("invalid_outputPeak", nonFinitePeak.reportInvalidReason)
    }

    @Test
    fun `v2 feedback ppm accepts signed drift`() {
        val metrics = buildString {
            append("reportVersion=2 source=player_pcm syncType=asynchronous ")
            append("deviceOnline=true transportFailed=false feedbackMode=explicit ")
            append("feedbackEndpoint=0x84 feedbackState=Locked feedbackRatePpm=-37 ")
            append("transportRunning=true feedbackReady=true realPcmReleased=true ")
            append("canAcceptPcm=true playbackReady=true feedbackReusable=true ")
            append("terminalFailure=false nativeStreamGeneration=9 candidateId=cs43131 ")
            append("recoveryEpoch=4 recommendedAction=NONE actionId=0 ")
            append("actionGeneration=9 actionOwner=none actionLatched=false lastError=none")
        }.usbRuntimeMetrics()

        assertTrue(metrics.reportValid)
        assertEquals(-37L, metrics.feedbackRatePpm)
    }

    @Test
    fun `v2 action identity and terminal state must stay symmetric`() {
        val noneWithActionId = validDisabledV2Report()
            .replace("actionId=0", "actionId=1")
            .usbRuntimeMetrics()
        val terminalWithoutFailure = terminalV2Report()
            .replace("terminalFailure=true", "terminalFailure=false")
            .usbRuntimeMetrics()
        val nativeActionLatched = validDisabledV2Report()
            .replace("recommendedAction=NONE", "recommendedAction=HOLDOVER")
            .replace("actionId=0", "actionId=1")
            .replace("actionOwner=none", "actionOwner=native")
            .replace("actionLatched=false", "actionLatched=true")
            .usbRuntimeMetrics()

        assertFalse(noneWithActionId.reportValid)
        assertEquals("invalid_none_action_state", noneWithActionId.reportInvalidReason)
        assertFalse(terminalWithoutFailure.reportValid)
        assertEquals("invalid_kotlin_action_state", terminalWithoutFailure.reportInvalidReason)
        assertFalse(nativeActionLatched.reportValid)
        assertEquals("invalid_native_action_state", nativeActionLatched.reportInvalidReason)
    }

    @Test
    fun `v2 generation identity must select the current native stream`() {
        val zeroGeneration = validDisabledV2Report()
            .replace("nativeStreamGeneration=9", "nativeStreamGeneration=0")
            .replace("actionGeneration=9", "actionGeneration=0")
            .usbRuntimeMetrics()
        val staleActionGeneration = validDisabledV2Report()
            .replace("actionGeneration=9", "actionGeneration=8")
            .usbRuntimeMetrics()

        assertFalse(zeroGeneration.reportValid)
        assertEquals("invalid_nativeStreamGeneration", zeroGeneration.reportInvalidReason)
        assertFalse(staleActionGeneration.reportValid)
        assertEquals("action_generation_mismatch", staleActionGeneration.reportInvalidReason)
    }

    @Test
    fun `live free bytes replace stale queue level for write planning`() {
        val metrics = UsbExclusiveRuntimeMetrics(
            pcmLevelBytes = 149_504L,
            pcmCapacityBytes = 192_000L,
            pcmFreeBytes = 42_496L
        ).withLivePcmFreeBytes(100_000L)

        assertEquals(92_000L, metrics.pcmLevelBytes)
        assertEquals(100_000L, metrics.pcmFreeBytes)
    }

    @Test
    fun `live free bytes are clamped to reported capacity`() {
        val metrics = UsbExclusiveRuntimeMetrics(
            pcmLevelBytes = 0L,
            pcmCapacityBytes = 192_000L,
            pcmFreeBytes = 192_000L
        ).withLivePcmFreeBytes(250_000L)

        assertEquals(0L, metrics.pcmLevelBytes)
        assertEquals(192_000L, metrics.pcmFreeBytes)
    }

    private fun validDisabledV2Report(): String {
        return buildString {
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
    }

    private fun terminalV2Report(): String {
        return validDisabledV2Report()
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
}
