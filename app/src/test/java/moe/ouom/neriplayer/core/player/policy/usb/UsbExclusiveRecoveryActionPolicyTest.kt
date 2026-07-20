package moe.ouom.neriplayer.core.player.policy.usb

import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveRecoveryAction
import moe.ouom.neriplayer.core.player.usb.transport.UsbExclusiveRecoveryActionAckStatus
import moe.ouom.neriplayer.core.player.usb.transport.usbRuntimeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbExclusiveRecoveryActionPolicyTest {

    @Test
    fun `native-owned low level actions are diagnostics only`() {
        val policy = UsbExclusiveRecoveryActionPolicy()
        val decision = policy.evaluate(
            metrics = v2ActionReport(
                recommendedAction = "SAME_HANDLE_REARM",
                actionOwner = "native"
            ).usbRuntimeMetrics(),
            activeStreamGeneration = 9L,
            nowMs = 1_000L
        )

        assertFalse(decision.shouldExecute)
        assertFalse(decision.shouldAcknowledge)
        assertEquals(UsbExclusiveRecoveryRouteAction.None, decision.routeAction)
        assertEquals("native_owned_action", decision.reason)
    }

    @Test
    fun `kotlin fresh open action executes once per action key`() {
        val policy = UsbExclusiveRecoveryActionPolicy()
        val metrics = v2ActionReport(
            recommendedAction = "FRESH_OPEN",
            actionOwner = "kotlin"
        ).usbRuntimeMetrics()

        val first = policy.evaluate(metrics, activeStreamGeneration = 9L, nowMs = 1_000L)
        assertTrue(
            policy.completeAcknowledgement(
                first,
                UsbExclusiveRecoveryActionAckStatus.Acked
            )
        )
        val duplicate = policy.evaluate(metrics, activeStreamGeneration = 9L, nowMs = 1_001L)

        assertTrue(first.shouldExecute)
        assertTrue(first.shouldAcknowledge)
        assertEquals(UsbExclusiveRecoveryRouteAction.FreshOpen, first.routeAction)
        assertEquals(1, first.freshOpenBudgetUsed)
        assertFalse(duplicate.shouldExecute)
        assertEquals("duplicate_action", duplicate.reason)
    }

    @Test
    fun `kotlin stop preserve intent action executes once`() {
        val policy = UsbExclusiveRecoveryActionPolicy()
        val decision = policy.evaluate(
            metrics = v2ActionReport(
                recommendedAction = "STOP_PRESERVE_INTENT",
                actionOwner = "kotlin"
            ).usbRuntimeMetrics(),
            activeStreamGeneration = 9L,
            nowMs = 1_000L
        )

        assertTrue(decision.shouldExecute)
        assertTrue(decision.shouldAcknowledge)
        assertEquals(UsbExclusiveRecoveryRouteAction.StopPreserveIntent, decision.routeAction)
        assertEquals(UsbExclusiveRecoveryAction.StopPreserveIntent, decision.nativeAction)
    }

    @Test
    fun `stale native stream generation is ignored`() {
        val policy = UsbExclusiveRecoveryActionPolicy()
        val decision = policy.evaluate(
            metrics = v2ActionReport(
                recommendedAction = "FRESH_OPEN",
                actionOwner = "kotlin",
                actionGeneration = 8L
            ).usbRuntimeMetrics(),
            activeStreamGeneration = 9L,
            nowMs = 1_000L
        )

        assertFalse(decision.shouldExecute)
        assertFalse(decision.shouldAcknowledge)
        assertEquals("stale_generation", decision.reason)
    }

    @Test
    fun `fresh open budget crosses action ids in the same recovery epoch`() {
        val policy = UsbExclusiveRecoveryActionPolicy(maxFreshOpenActionsPerEpoch = 2)

        val first = policy.evaluate(
            metrics = v2ActionReport(actionId = 1L).usbRuntimeMetrics(),
            activeStreamGeneration = 9L,
            nowMs = 1_000L
        )
        assertTrue(
            policy.completeAcknowledgement(first, UsbExclusiveRecoveryActionAckStatus.Acked)
        )
        val second = policy.evaluate(
            metrics = v2ActionReport(actionId = 2L).usbRuntimeMetrics(),
            activeStreamGeneration = 9L,
            nowMs = 1_001L
        )
        assertTrue(
            policy.completeAcknowledgement(second, UsbExclusiveRecoveryActionAckStatus.Acked)
        )
        val exhausted = policy.evaluate(
            metrics = v2ActionReport(actionId = 3L).usbRuntimeMetrics(),
            activeStreamGeneration = 9L,
            nowMs = 1_002L
        )

        assertEquals(UsbExclusiveRecoveryRouteAction.FreshOpen, first.routeAction)
        assertEquals(UsbExclusiveRecoveryRouteAction.FreshOpen, second.routeAction)
        assertEquals(UsbExclusiveRecoveryRouteAction.StopPreserveIntent, exhausted.routeAction)
        assertEquals("fresh_open_budget_exhausted", exhausted.reason)
        assertEquals(2, exhausted.freshOpenBudgetUsed)
    }

    @Test
    fun `failed native ack releases dedupe and does not consume fresh open budget`() {
        val policy = UsbExclusiveRecoveryActionPolicy(maxFreshOpenActionsPerEpoch = 1)
        val metrics = v2ActionReport().usbRuntimeMetrics()

        val rejected = policy.evaluate(metrics, activeStreamGeneration = 9L, nowMs = 1_000L)

        assertFalse(
            policy.completeAcknowledgement(
                rejected,
                UsbExclusiveRecoveryActionAckStatus.NoPending
            )
        )

        val retry = policy.evaluate(metrics, activeStreamGeneration = 9L, nowMs = 1_001L)

        assertTrue(retry.shouldExecute)
        assertEquals(UsbExclusiveRecoveryRouteAction.FreshOpen, retry.routeAction)
        assertEquals(1, retry.freshOpenBudgetUsed)
        assertTrue(
            policy.completeAcknowledgement(
                retry,
                UsbExclusiveRecoveryActionAckStatus.Acked
            )
        )
        assertEquals(
            "duplicate_action",
            policy.evaluate(metrics, activeStreamGeneration = 9L, nowMs = 1_002L).reason
        )
    }

    @Test
    fun `stable playback grace resets fresh open budget`() {
        val policy = UsbExclusiveRecoveryActionPolicy(
            maxFreshOpenActionsPerEpoch = 1,
            stableGraceMs = 100L
        )

        val first = policy.evaluate(
            metrics = v2ActionReport(actionId = 1L).usbRuntimeMetrics(),
            activeStreamGeneration = 9L,
            nowMs = 1_000L
        )
        assertEquals(UsbExclusiveRecoveryRouteAction.FreshOpen, first.routeAction)
        assertTrue(
            policy.completeAcknowledgement(first, UsbExclusiveRecoveryActionAckStatus.Acked)
        )
        assertEquals(
            UsbExclusiveRecoveryRouteAction.StopPreserveIntent,
            policy.evaluate(
                metrics = v2ActionReport(actionId = 2L).usbRuntimeMetrics(),
                activeStreamGeneration = 9L,
                nowMs = 1_001L
            ).routeAction
        )

        policy.evaluate(
            stableReport().usbRuntimeMetrics(),
            activeStreamGeneration = 9L,
            nowMs = 2_000L
        )
        policy.evaluate(
            stableReport().usbRuntimeMetrics(),
            activeStreamGeneration = 9L,
            nowMs = 2_050L
        )
        policy.evaluate(
            stableReport().usbRuntimeMetrics(),
            activeStreamGeneration = 9L,
            nowMs = 2_101L
        )

        val afterGrace = policy.evaluate(
            metrics = v2ActionReport(actionId = 3L).usbRuntimeMetrics(),
            activeStreamGeneration = 9L,
            nowMs = 2_102L
        )

        assertEquals(UsbExclusiveRecoveryRouteAction.FreshOpen, afterGrace.routeAction)
        assertEquals(1, afterGrace.freshOpenBudgetUsed)
    }

    @Test
    fun `stable recovery reset requires locked reusable playback`() {
        assertTrue(isStableForUsbExclusiveRecoveryReset(stableReport().usbRuntimeMetrics()))
        assertFalse(
            isStableForUsbExclusiveRecoveryReset(
                stableReport(feedbackState = "Holdover").usbRuntimeMetrics()
            )
        )
        assertFalse(
            isStableForUsbExclusiveRecoveryReset(
                stableReport(playbackReady = false).usbRuntimeMetrics()
            )
        )
    }

    @Test
    fun `ack status enum keeps all native contract branches explicit`() {
        assertEquals(
            listOf(
                UsbExclusiveRecoveryActionAckStatus.Acked,
                UsbExclusiveRecoveryActionAckStatus.AlreadyAcked,
                UsbExclusiveRecoveryActionAckStatus.GenerationMismatch,
                UsbExclusiveRecoveryActionAckStatus.HandleClosing,
                UsbExclusiveRecoveryActionAckStatus.NoPending
            ),
            UsbExclusiveRecoveryActionAckStatus.values().toList()
        )
    }

    @Test
    fun `route action only executes after native ack accepts it`() {
        val policy = UsbExclusiveRecoveryActionPolicy()
        val decision = policy.evaluate(
            metrics = v2ActionReport().usbRuntimeMetrics(),
            activeStreamGeneration = 9L,
            nowMs = 1_000L
        )

        assertTrue(
            shouldExecuteUsbExclusiveRecoveryRouteActionAfterAck(
                decision,
                UsbExclusiveRecoveryActionAckStatus.Acked
            )
        )
        assertTrue(
            shouldExecuteUsbExclusiveRecoveryRouteActionAfterAck(
                decision,
                UsbExclusiveRecoveryActionAckStatus.AlreadyAcked
            )
        )
        assertFalse(
            shouldExecuteUsbExclusiveRecoveryRouteActionAfterAck(
                decision,
                UsbExclusiveRecoveryActionAckStatus.GenerationMismatch
            )
        )
        assertFalse(
            shouldExecuteUsbExclusiveRecoveryRouteActionAfterAck(
                decision,
                UsbExclusiveRecoveryActionAckStatus.HandleClosing
            )
        )
        assertFalse(
            shouldExecuteUsbExclusiveRecoveryRouteActionAfterAck(
                decision,
                UsbExclusiveRecoveryActionAckStatus.NoPending
            )
        )
    }

    private fun v2ActionReport(
        recommendedAction: String = "FRESH_OPEN",
        actionOwner: String = "kotlin",
        recoveryEpoch: Long = 4L,
        actionGeneration: Long = 9L,
        actionId: Long = 7L,
        candidateId: String = "cs43131",
        terminalFailure: Boolean = actionOwner == "kotlin",
        actionLatched: Boolean = actionOwner == "kotlin"
    ): String {
        return buildString {
            append("reportVersion=2 source=player_pcm syncType=async running=true ")
            append("deviceOnline=true transportFailed=false feedbackMode=explicit ")
            append("feedbackEndpoint=0x84 feedbackState=Failed ")
            append("transportRunning=false feedbackReady=false realPcmReleased=false ")
            append("canAcceptPcm=false playbackReady=false feedbackReusable=false ")
            append("terminalFailure=$terminalFailure nativeStreamGeneration=$actionGeneration ")
            append("candidateId=$candidateId recoveryEpoch=$recoveryEpoch ")
            append("recommendedAction=$recommendedAction actionId=$actionId ")
            append("actionGeneration=$actionGeneration actionOwner=$actionOwner ")
            append("actionLatched=$actionLatched lastError=none")
        }
    }

    private fun stableReport(
        feedbackState: String = "Locked",
        playbackReady: Boolean = true
    ): String {
        return buildString {
            append("reportVersion=2 source=player_pcm syncType=async running=true ")
            append("deviceOnline=true transportFailed=false feedbackMode=explicit ")
            append("feedbackEndpoint=0x84 feedbackState=$feedbackState ")
            append("transportRunning=true feedbackReady=true realPcmReleased=true ")
            append("canAcceptPcm=true playbackReady=$playbackReady feedbackReusable=true ")
            append("terminalFailure=false nativeStreamGeneration=9 ")
            append("candidateId=cs43131 recoveryEpoch=4 ")
            append("recommendedAction=NONE actionId=0 ")
            append("actionGeneration=9 actionOwner=none ")
            append("actionLatched=false lastError=none")
        }
    }
}
