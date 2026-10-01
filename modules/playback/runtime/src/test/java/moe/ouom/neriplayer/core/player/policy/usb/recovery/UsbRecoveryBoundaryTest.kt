package moe.ouom.neriplayer.core.player.policy.usb.recovery

import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveRecoveryActionPolicy
import moe.ouom.neriplayer.core.player.policy.usb.UsbExclusiveSameHandleRecoveryPolicy
import moe.ouom.neriplayer.core.player.policy.usb.isStableForUsbExclusiveRecoveryReset
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveErrorCode
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveFeedbackMode
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveFeedbackState
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRecoveryAction
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRecoveryActionAckStatus
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRecoveryActionOwner
import moe.ouom.neriplayer.data.model.playback.usb.UsbExclusiveRuntimeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbRecoveryBoundaryTest {
    @Test
    fun `unknown stream generation can recover but stale and non terminal actions cannot`() {
        val action = UsbExclusiveRuntimeMetrics(
            reportVersion = 2, recommendedAction = UsbExclusiveRecoveryAction.FreshOpen,
            actionOwner = UsbExclusiveRecoveryActionOwner.Kotlin, recoveryEpoch = 4, actionGeneration = 9, actionId = 7
        )
        assertEquals("fresh_open", UsbExclusiveRecoveryActionPolicy().evaluate(action, null, 1_000).reason)
        assertEquals("stale_generation", UsbExclusiveRecoveryActionPolicy().evaluate(action, 8, 1_000).reason)
        assertEquals("non_terminal_kotlin_action", UsbExclusiveRecoveryActionPolicy().evaluate(
            action.copy(recommendedAction = UsbExclusiveRecoveryAction.SameHandleRearm), 9, 1_000
        ).reason)
    }

    @Test
    fun `rejected or incomplete acknowledgements do not consume the fresh open budget`() {
        val policy = UsbExclusiveRecoveryActionPolicy(maxFreshOpenActionsPerEpoch = 1)
        val action = UsbExclusiveRuntimeMetrics(
            reportVersion = 2, recommendedAction = UsbExclusiveRecoveryAction.FreshOpen,
            actionOwner = UsbExclusiveRecoveryActionOwner.Kotlin, recoveryEpoch = 4, actionGeneration = 9, actionId = 7
        )
        val decision = policy.evaluate(action, 9, 1_000)
        assertFalse(policy.completeAcknowledgement(decision,
            UsbExclusiveRecoveryActionAckStatus.GenerationMismatch))
        val retry = policy.evaluate(action, 9, 1_001)
        assertEquals("fresh_open", retry.reason)
        assertEquals(1, retry.freshOpenBudgetUsed)
        assertFalse(policy.completeAcknowledgement(retry.copy(recoveryBudgetKey = null),
            UsbExclusiveRecoveryActionAckStatus.Acked))
        val stop = policy.evaluate(action.copy(recommendedAction = UsbExclusiveRecoveryAction.StopPreserveIntent), 9, 1_002)
        assertTrue(policy.completeAcknowledgement(stop.copy(actionKey = null),
            UsbExclusiveRecoveryActionAckStatus.Acked))
    }

    @Test
    fun `invalid recovery budgets and grace windows are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { UsbExclusiveRecoveryActionPolicy(maxFreshOpenActionsPerEpoch = 0) }
        assertThrows(IllegalArgumentException::class.java) { UsbExclusiveRecoveryActionPolicy(stableGraceMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { UsbExclusiveRecoveryActionPolicy(retainedActionLimit = 0) }
        assertThrows(IllegalArgumentException::class.java) { UsbExclusiveSameHandleRecoveryPolicy(maxAttemptsPerRecoveryWindow = 0) }
        assertThrows(IllegalArgumentException::class.java) { UsbExclusiveSameHandleRecoveryPolicy(stableGraceMs = -1) }
    }

    @Test
    fun `every feedback readiness condition must hold before recovery budgets reset`() {
        val stable = UsbExclusiveRuntimeMetrics(
            reportVersion = 2, playbackReady = true, feedbackMode = UsbExclusiveFeedbackMode.Explicit,
            feedbackState = UsbExclusiveFeedbackState.Locked, feedbackReady = true,
            realPcmReleased = true, canAcceptPcm = true, feedbackReusable = true
        )
        assertTrue(isStableForUsbExclusiveRecoveryReset(stable))
        listOf(
            stable.copy(reportValid = false), stable.copy(reportVersion = 1),
            stable.copy(playbackReady = false), stable.copy(terminalFailure = true),
            stable.copy(transportFailed = true), stable.copy(errorCode = UsbExclusiveErrorCode.TransportFailed),
            stable.copy(feedbackState = UsbExclusiveFeedbackState.Disabled), stable.copy(feedbackReady = false),
            stable.copy(realPcmReleased = false), stable.copy(canAcceptPcm = false), stable.copy(feedbackReusable = false)
        ).forEach { assertFalse(isStableForUsbExclusiveRecoveryReset(it)) }
        assertTrue(isStableForUsbExclusiveRecoveryReset(stable.copy(feedbackMode = UsbExclusiveFeedbackMode.Disabled)))
    }

    @Test
    fun `missing action key fields cannot enter recovery acknowledgement`() {
        val action = UsbExclusiveRuntimeMetrics(
            reportVersion = 2, recommendedAction = UsbExclusiveRecoveryAction.FreshOpen,
            actionOwner = UsbExclusiveRecoveryActionOwner.Kotlin, recoveryEpoch = 4, actionGeneration = 9, actionId = 7
        )
        listOf(action.copy(recoveryEpoch = null), action.copy(actionGeneration = null), action.copy(actionId = null))
            .forEach { metrics ->
                assertEquals("missing_action_key", UsbExclusiveRecoveryActionPolicy().evaluate(metrics, 9, 1_000).reason)
            }
    }

    @Test
    fun `same handle recovery reports the first failed transport requirement`() {
        val terminal = UsbExclusiveRuntimeMetrics(
            reportVersion = 2, source = "player_pcm", deviceOnline = true, transportFailed = true,
            terminalFailure = true, running = false, transportRunning = false, inFlightTransfers = 1,
            feedbackMode = UsbExclusiveFeedbackMode.Explicit, errorCode = UsbExclusiveErrorCode.TransferCompletionStalled
        )
        val rejected = listOf(
            terminal.copy(reportValid = false) to "invalid_report", terminal.copy(reportVersion = 1) to "invalid_report",
            terminal.copy(source = "generated") to "not_player_pcm", terminal.copy(deviceOnline = false) to "device_offline",
            terminal.copy(transportFailed = false) to "transport_not_terminal", terminal.copy(terminalFailure = false) to "transport_not_terminal",
            terminal.copy(running = true) to "transport_still_running", terminal.copy(transportRunning = true) to "transport_still_running",
            terminal.copy(inFlightTransfers = null) to "no_in_flight_transfer", terminal.copy(inFlightTransfers = 0) to "no_in_flight_transfer"
        )
        rejected.forEach { (metrics, reason) ->
            assertEquals(reason, UsbExclusiveSameHandleRecoveryPolicy().evaluate(15, metrics, 1_000).reason)
        }
        assertTrue(UsbExclusiveSameHandleRecoveryPolicy().evaluate(15, terminal, 1_000).shouldAttempt)
    }
}
