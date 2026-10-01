package moe.ouom.neriplayer.data.sync.schedule

import java.io.IOException
import moe.ouom.neriplayer.data.model.sync.SyncProvider
import moe.ouom.neriplayer.data.model.sync.SyncWorkerFailureKind
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkerFailurePolicyTest {
    @Test
    fun `github authentication always notifies and returned expiry stops retry`() {
        for (manual in listOf(false, true)) for (silent in listOf(false, true)) for (unexpected in listOf(false, true)) {
            val decision = SyncWorkerFailurePolicy.decide(SyncProvider.GITHUB, SyncWorkerFailureKind.AUTHENTICATION, manual, unexpected, silent)
            assertEquals(true, decision.notify)
            assertEquals(if (unexpected) SyncWorkerOutcome.RETRY else SyncWorkerOutcome.FAILURE, decision.outcome)
        }
    }

    @Test
    fun `generic github failures follow manual and silent settings`() {
        for (manual in listOf(false, true)) for (silent in listOf(false, true)) {
            val decision = SyncWorkerFailurePolicy.decide(SyncProvider.GITHUB, SyncWorkerFailureKind.OTHER, manual, false, silent)
            assertEquals(manual || !silent, decision.notify)
            assertEquals(SyncWorkerOutcome.RETRY, decision.outcome)
        }
    }

    @Test
    fun `webdav permanent failures notify and fail only for returned sync errors`() {
        for (kind in listOf(SyncWorkerFailureKind.AUTHENTICATION, SyncWorkerFailureKind.MISSING_CONDITION, SyncWorkerFailureKind.CONFIGURATION, SyncWorkerFailureKind.OTHER)) {
            for (manual in listOf(false, true)) for (unexpected in listOf(false, true)) {
                val decision = SyncWorkerFailurePolicy.decide(SyncProvider.WEBDAV, kind, manual, unexpected, false)
                val permanent = kind != SyncWorkerFailureKind.OTHER && !unexpected
                assertEquals(manual || permanent, decision.notify)
                assertEquals(if (permanent) SyncWorkerOutcome.FAILURE else SyncWorkerOutcome.RETRY, decision.outcome)
            }
        }
    }

    @Test
    fun `shared sync lock is retried quietly for both providers`() {
        for (provider in SyncProvider.entries) {
            val decision = SyncWorkerFailurePolicy.decide(provider, SyncWorkerFailureKind.ALREADY_RUNNING, true, false, false)
            assertEquals(false, decision.notify)
            assertEquals(SyncWorkerOutcome.RETRY, decision.outcome)
        }
    }

    @Test
    fun `classifier matches subclasses and leaves unknown and null errors generic`() {
        val classifier = SyncWorkerFailureClassifier(mapOf(IOException::class.java to SyncWorkerFailureKind.AUTHENTICATION))
        assertEquals(SyncWorkerFailureKind.AUTHENTICATION, classifier.classify(object : IOException() {}))
        assertEquals(SyncWorkerFailureKind.OTHER, classifier.classify(IllegalStateException()))
        assertEquals(SyncWorkerFailureKind.OTHER, classifier.classify(null))
    }

    @Test
    fun `delayed scheduling preserves user append and automatic eligibility semantics`() {
        assertEquals(false, SyncWorkSchedulingPolicy.canSchedule(false, false, true))
        assertEquals(false, SyncWorkSchedulingPolicy.canSchedule(false, true, false))
        assertEquals(true, SyncWorkSchedulingPolicy.canSchedule(false, true, true))
        assertEquals(true, SyncWorkSchedulingPolicy.canSchedule(true, false, false))
        for (user in listOf(false, true)) for (append in listOf(false, true)) {
            assertEquals(user || append, SyncWorkSchedulingPolicy.append(user, append))
        }
    }
}
