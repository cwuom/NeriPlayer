package moe.ouom.neriplayer.data.sync.schedule

import moe.ouom.neriplayer.data.model.sync.SyncProvider
import moe.ouom.neriplayer.data.model.sync.SyncWorkerFailureDecision
import moe.ouom.neriplayer.data.model.sync.SyncWorkerFailureKind
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome

object SyncWorkerFailurePolicy {
    fun decide(
        provider: SyncProvider,
        kind: SyncWorkerFailureKind,
        manual: Boolean,
        unexpected: Boolean,
        silentFailure: Boolean
    ): SyncWorkerFailureDecision {
        if (kind == SyncWorkerFailureKind.ALREADY_RUNNING) return SyncWorkerFailureDecision(SyncWorkerOutcome.RETRY, false)
        return when (provider) {
            SyncProvider.GITHUB -> github(kind, manual, unexpected, silentFailure)
            SyncProvider.WEBDAV -> webDav(kind, manual, unexpected)
        }
    }

    private fun github(kind: SyncWorkerFailureKind, manual: Boolean, unexpected: Boolean, silent: Boolean): SyncWorkerFailureDecision {
        val authentication = kind == SyncWorkerFailureKind.AUTHENTICATION
        return SyncWorkerFailureDecision(
            outcome = if (authentication && !unexpected) SyncWorkerOutcome.FAILURE else SyncWorkerOutcome.RETRY,
            notify = authentication || manual || !silent
        )
    }

    private fun webDav(kind: SyncWorkerFailureKind, manual: Boolean, unexpected: Boolean): SyncWorkerFailureDecision {
        val permanent = kind == SyncWorkerFailureKind.AUTHENTICATION ||
            kind == SyncWorkerFailureKind.MISSING_CONDITION || kind == SyncWorkerFailureKind.CONFIGURATION
        return SyncWorkerFailureDecision(
            outcome = if (permanent && !unexpected) SyncWorkerOutcome.FAILURE else SyncWorkerOutcome.RETRY,
            notify = manual || (permanent && !unexpected)
        )
    }
}
