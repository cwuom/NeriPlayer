package moe.ouom.neriplayer.core.player.policy.usb

import moe.ouom.neriplayer.core.player.usb.transport.usbRuntimeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveSameHandleRecoveryPolicyTest {

    @Test
    fun `explicit feedback completion stall gets bounded same handle attempts`() {
        val policy = UsbExclusiveSameHandleRecoveryPolicy(
            maxAttemptsPerRecoveryWindow = 2
        )
        val metrics = stalledExplicitFeedbackReport("TransferCompletionStalled")
            .usbRuntimeMetrics()

        val first = policy.evaluate(handle = 3L, metrics = metrics, nowMs = 1_000L)
        val second = policy.evaluate(handle = 3L, metrics = metrics, nowMs = 1_100L)
        val exhausted = policy.evaluate(handle = 3L, metrics = metrics, nowMs = 1_200L)

        assertTrue(first.shouldAttempt)
        assertEquals(1, first.attempt)
        assertTrue(second.shouldAttempt)
        assertEquals(2, second.attempt)
        assertFalse(exhausted.shouldAttempt)
        assertEquals("attempt_budget_exhausted", exhausted.reason)
    }

    @Test
    fun `adaptive no feedback completion stall bypasses repeated same handle restart`() {
        val policy = UsbExclusiveSameHandleRecoveryPolicy()

        val decision = policy.evaluate(
            handle = 3L,
            metrics = stalledNoFeedbackReport(
                syncType = "adaptive",
                errorCode = "TransferCompletionStalled"
            ).usbRuntimeMetrics(),
            nowMs = 1_000L
        )

        assertFalse(decision.shouldAttempt)
        assertEquals("no_feedback_endpoint_requires_fresh_open", decision.reason)
    }

    @Test
    fun `synchronous no feedback first completion timeout bypasses same handle restart`() {
        val policy = UsbExclusiveSameHandleRecoveryPolicy()

        val decision = policy.evaluate(
            handle = 3L,
            metrics = stalledNoFeedbackReport(
                syncType = "synchronous",
                errorCode = "TransferFirstCompletionTimeout"
            ).usbRuntimeMetrics(),
            nowMs = 1_000L
        )

        assertFalse(decision.shouldAttempt)
        assertEquals("no_feedback_endpoint_requires_fresh_open", decision.reason)
    }

    @Test
    fun `device detach and io failures never reuse handle`() {
        val policy = UsbExclusiveSameHandleRecoveryPolicy()
        val detached = stalledNoFeedbackReport(
            syncType = "adaptive",
            errorCode = "DeviceDetached"
        )
            .replace("deviceOnline=true", "deviceOnline=false")
            .usbRuntimeMetrics()
        val ioFailure = stalledNoFeedbackReport(
            syncType = "adaptive",
            errorCode = "TransportFailed"
        ).usbRuntimeMetrics()

        assertFalse(policy.evaluate(3L, detached, 1_000L).shouldAttempt)
        assertFalse(policy.evaluate(3L, ioFailure, 1_001L).shouldAttempt)
    }

    @Test
    fun `non terminal transfer warning never restarts an active handle`() {
        val policy = UsbExclusiveSameHandleRecoveryPolicy()
        val active = activeWarningReport().usbRuntimeMetrics()

        val decision = policy.evaluate(3L, active, 1_000L)

        assertFalse(decision.shouldAttempt)
        assertEquals("transport_not_terminal", decision.reason)
    }

    @Test
    fun `stable playback restores same handle recovery budget`() {
        val policy = UsbExclusiveSameHandleRecoveryPolicy(
            maxAttemptsPerRecoveryWindow = 1,
            stableGraceMs = 100L
        )
        val stalled = stalledExplicitFeedbackReport("TransferFirstCompletionTimeout")
            .usbRuntimeMetrics()
        val stable = stableExplicitFeedbackReport().usbRuntimeMetrics()

        assertTrue(policy.evaluate(3L, stalled, 1_000L).shouldAttempt)
        assertFalse(policy.evaluate(3L, stalled, 1_001L).shouldAttempt)
        policy.evaluate(3L, stable, 2_000L)
        policy.evaluate(3L, stable, 2_101L)

        assertTrue(policy.evaluate(3L, stalled, 2_102L).shouldAttempt)
    }

    private fun stalledNoFeedbackReport(syncType: String, errorCode: String): String {
        return buildString {
            append("reportVersion=2 source=player_pcm syncType=$syncType running=false ")
            append("deviceOnline=true transportFailed=true feedbackMode=disabled ")
            append("feedbackState=disabled transportRunning=false feedbackReady=true ")
            append("realPcmReleased=true canAcceptPcm=false playbackReady=false ")
            append("feedbackReusable=true terminalFailure=true nativeStreamGeneration=9 ")
            append("candidateId=uac2-$syncType recoveryEpoch=4 ")
            append("recommendedAction=FRESH_OPEN actionId=7 actionGeneration=9 ")
            append("actionOwner=kotlin actionLatched=true errorCode=$errorCode ")
            append("inFlight=10 lastError=event_loop_completion_stalled")
        }
    }

    private fun stalledExplicitFeedbackReport(errorCode: String): String {
        return buildString {
            append("reportVersion=2 source=player_pcm syncType=asynchronous running=false ")
            append("deviceOnline=true transportFailed=true feedbackMode=explicit ")
            append("feedbackEndpoint=0x84 feedbackState=Locked transportRunning=false ")
            append("feedbackReady=true realPcmReleased=true canAcceptPcm=false ")
            append("playbackReady=false feedbackReusable=true terminalFailure=true ")
            append("nativeStreamGeneration=9 candidateId=uac2-explicit recoveryEpoch=4 ")
            append("recommendedAction=FRESH_OPEN actionId=7 actionGeneration=9 ")
            append("actionOwner=kotlin actionLatched=true errorCode=$errorCode ")
            append("inFlight=16 lastError=event_loop_completion_stalled")
        }
    }

    private fun stableExplicitFeedbackReport(): String {
        return buildString {
            append("reportVersion=2 source=player_pcm syncType=asynchronous running=true ")
            append("deviceOnline=true transportFailed=false feedbackMode=explicit ")
            append("feedbackEndpoint=0x84 feedbackState=Locked transportRunning=true ")
            append("feedbackReady=true ")
            append("realPcmReleased=true canAcceptPcm=true playbackReady=true ")
            append("feedbackReusable=true terminalFailure=false nativeStreamGeneration=10 ")
            append("candidateId=uac2-explicit recoveryEpoch=4 ")
            append("recommendedAction=NONE actionId=0 actionGeneration=10 ")
            append("actionOwner=none actionLatched=false errorCode=None ")
            append("inFlight=10 lastError=none")
        }
    }

    private fun activeWarningReport(): String {
        return buildString {
            append("reportVersion=2 source=player_pcm syncType=adaptive running=true ")
            append("deviceOnline=true transportFailed=false feedbackMode=disabled ")
            append("feedbackState=disabled transportRunning=true feedbackReady=true ")
            append("realPcmReleased=true canAcceptPcm=true playbackReady=true ")
            append("feedbackReusable=true terminalFailure=false nativeStreamGeneration=9 ")
            append("candidateId=uac2-adaptive recoveryEpoch=4 ")
            append("recommendedAction=NONE actionId=0 actionGeneration=9 ")
            append("actionOwner=none actionLatched=false errorCode=TransferCompletionStalled ")
            append("inFlight=10 lastError=event_loop_completion_stalled")
        }
    }
}
