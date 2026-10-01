package moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.operation

import android.content.Context
import android.content.res.Resources
import moe.ouom.neriplayer.core.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadDirectoryChangeDecision
import moe.ouom.neriplayer.core.download.storage.migration.plan.ManagedDownloadMigrationPolicy
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.DOWNLOAD_DIRECTORY_PREFLIGHT_TIMEOUT_MS
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.PendingDownloadDirectoryChange
import moe.ouom.neriplayer.ui.screen.tab.settings.download.directory.runDownloadDirectoryPreflight
import moe.ouom.neriplayer.util.time.elapsedMillisSince

internal enum class DownloadDirectoryPreparationResult {
    KEEP_PERSISTED_PERMISSION,
    RELEASE_PERSISTED_PERMISSION
}

internal interface DownloadDirectoryPreparationGateway {
    fun areEquivalent(firstUri: String?, secondUri: String?): Boolean
    suspend fun decide(
        previousUri: String?,
        targetUri: String?
    ): Result<ManagedDownloadDirectoryChangeDecision>?
}

internal class AndroidDownloadDirectoryPreparationGateway(
    private val context: Context
) : DownloadDirectoryPreparationGateway {
    override fun areEquivalent(firstUri: String?, secondUri: String?): Boolean =
        ManagedDownloadStorage.areEquivalentDirectoryUris(firstUri, secondUri)

    override suspend fun decide(
        previousUri: String?,
        targetUri: String?
    ): Result<ManagedDownloadDirectoryChangeDecision>? = runDownloadDirectoryPreflight {
        ManagedDownloadMigrationPolicy.resolveDirectoryChangeAfterProbes(
            fromDirectoryUri = previousUri,
            toDirectoryUri = targetUri,
            probeSourceHasManagedEntries = {
                val startedAtNanos = System.nanoTime()
                ManagedDownloadStorage.hasMigratableDownloads(context, previousUri)
                    .also { present ->
                        NPLogger.d(
                            "DownloadDirectoryPreflight",
                            "directory_preflight stage=source_presence status=complete " +
                                    "present=$present elapsedMs=${elapsedMillisSince(startedAtNanos)}"
                        )
                    }
            },
            probeTargetHasManagedEntries = {
                val startedAtNanos = System.nanoTime()
                ManagedDownloadStorage.hasMigratableDownloads(context, targetUri).also { present ->
                    NPLogger.d(
                        "DownloadDirectoryPreflight",
                        "directory_preflight stage=target_presence status=complete " +
                                "present=$present elapsedMs=${elapsedMillisSince(startedAtNanos)}"
                    )
                }
            },
            probeTargetNonEmpty = {
                val startedAtNanos = System.nanoTime()
                ManagedDownloadStorage.hasActualDirectoryEntries(context, targetUri)
                    .also { nonEmpty ->
                        NPLogger.d(
                            "DownloadDirectoryPreflight",
                            "directory_preflight stage=target_non_empty status=complete " +
                                    "nonEmpty=$nonEmpty elapsedMs=${
                                        elapsedMillisSince(
                                            startedAtNanos
                                        )
                                    }"
                        )
                    }
            }
        )
    }
}

internal interface DownloadDirectoryPreparationActionPort {
    fun isBlocked(): Boolean
    suspend fun apply(
        targetUri: String?,
        targetSummary: String,
        previousUri: String?,
        shouldReleasePreviousPermission: Boolean
    )
    fun showPending(change: PendingDownloadDirectoryChange)
}

internal fun downloadDirectoryChangeDirection(previousUri: String?, targetUri: String?): String =
    when {
        targetUri.isNullOrBlank() -> "to_default"
        previousUri.isNullOrBlank() -> "from_default"
        else -> "between_custom_roots"
    }

private data class DownloadDirectoryPreparationRequest(
    val previousUri: String?,
    val targetUri: String?,
    val targetSummary: String,
    val releaseTargetPermissionOnCancel: Boolean,
    val startedAtNanos: Long
)

private enum class DownloadDirectoryPreparationStart {
    BLOCKED,
    UNCHANGED,
    EQUIVALENT,
    PROBE
}

internal class DownloadDirectoryPreparationOwner(
    private val gateway: DownloadDirectoryPreparationGateway,
    private val actions: DownloadDirectoryPreparationActionPort,
    private val resources: Resources,
    private val onInlineMessageChange: (String?) -> Unit,
    private val onShowMessage: (String) -> Unit
) {
    suspend fun prepare(
        currentUri: String?,
        targetUri: String?,
        targetSummary: String,
        releaseTargetPermissionOnCancel: Boolean
    ): DownloadDirectoryPreparationResult {
        val request = DownloadDirectoryPreparationRequest(
            previousUri = currentUri?.takeIf(String::isNotBlank),
            targetUri = targetUri,
            targetSummary = targetSummary,
            releaseTargetPermissionOnCancel = releaseTargetPermissionOnCancel,
            startedAtNanos = System.nanoTime()
        )
        return actionFor(chooseStart(request))(request)
    }

    private fun chooseStart(request: DownloadDirectoryPreparationRequest): DownloadDirectoryPreparationStart {
        if (actions.isBlocked()) return DownloadDirectoryPreparationStart.BLOCKED
        val direction = downloadDirectoryChangeDirection(request.previousUri, request.targetUri)
        NPLogger.d("DownloadDirectoryPreflight", "directory_preflight stage=start direction=$direction")
        if (request.previousUri == request.targetUri) return DownloadDirectoryPreparationStart.UNCHANGED
        if (gateway.areEquivalent(request.previousUri, request.targetUri)) {
            return DownloadDirectoryPreparationStart.EQUIVALENT
        }
        return DownloadDirectoryPreparationStart.PROBE
    }

    private fun actionFor(
        start: DownloadDirectoryPreparationStart
    ): suspend (DownloadDirectoryPreparationRequest) -> DownloadDirectoryPreparationResult = when (start) {
        DownloadDirectoryPreparationStart.BLOCKED -> { _ ->
            DownloadDirectoryPreparationResult.RELEASE_PERSISTED_PERMISSION
        }
        DownloadDirectoryPreparationStart.UNCHANGED -> ::keepUnchangedDirectory
        DownloadDirectoryPreparationStart.EQUIVALENT -> ::applyEquivalentDirectory
        DownloadDirectoryPreparationStart.PROBE -> ::probeDirectoryChange
    }

    private suspend fun keepUnchangedDirectory(
        request: DownloadDirectoryPreparationRequest
    ): DownloadDirectoryPreparationResult {
        onInlineMessageChange(resources.getString(appliedDownloadDirectoryMessageId(request.targetUri)))
        return DownloadDirectoryPreparationResult.KEEP_PERSISTED_PERMISSION
    }

    private suspend fun applyEquivalentDirectory(
        request: DownloadDirectoryPreparationRequest
    ): DownloadDirectoryPreparationResult {
        actions.apply(request.targetUri, request.targetSummary, request.previousUri, false)
        return DownloadDirectoryPreparationResult.KEEP_PERSISTED_PERMISSION
    }

    private suspend fun probeDirectoryChange(
        request: DownloadDirectoryPreparationRequest
    ): DownloadDirectoryPreparationResult = probeAndApply(
        previousUri = request.previousUri,
        targetUri = request.targetUri,
        targetSummary = request.targetSummary,
        releaseTargetPermissionOnCancel = request.releaseTargetPermissionOnCancel,
        direction = downloadDirectoryChangeDirection(request.previousUri, request.targetUri),
        startedAtNanos = request.startedAtNanos
    )

    private suspend fun probeAndApply(
        previousUri: String?,
        targetUri: String?,
        targetSummary: String,
        releaseTargetPermissionOnCancel: Boolean,
        direction: String,
        startedAtNanos: Long
    ): DownloadDirectoryPreparationResult {
        val decisionResult = gateway.decide(previousUri, targetUri)
        if (decisionResult == null || decisionResult.isFailure) {
            showRetryableProbeFailure(decisionResult?.exceptionOrNull())
            return DownloadDirectoryPreparationResult.RELEASE_PERSISTED_PERMISSION
        }
        val decision = decisionResult.getOrThrow()
        NPLogger.d(
            "DownloadDirectoryPreflight",
            "directory_preflight stage=decision status=complete direction=$direction " +
                "decision=$decision elapsedMs=${elapsedMillisSince(startedAtNanos)}"
        )
        applyDecision(
            decision = decision,
            previousUri = previousUri,
            targetUri = targetUri,
            targetSummary = targetSummary,
            releaseTargetPermissionOnCancel = releaseTargetPermissionOnCancel
        )
        return DownloadDirectoryPreparationResult.KEEP_PERSISTED_PERMISSION
    }

    private fun showRetryableProbeFailure(error: Throwable?) {
        NPLogger.w(
            "DownloadDirectoryPreflight",
            "directory_preflight stage=decision status=retryable " +
                "timeoutMs=${DOWNLOAD_DIRECTORY_PREFLIGHT_TIMEOUT_MS} " +
                "errorType=${error?.javaClass?.simpleName ?: "timeout"}"
        )
        val message = resources.getString(CoreCommonR.string.managed_library_processing_retry)
        onInlineMessageChange(message)
        onShowMessage(message)
    }

    private suspend fun applyDecision(
        decision: ManagedDownloadDirectoryChangeDecision,
        previousUri: String?,
        targetUri: String?,
        targetSummary: String,
        releaseTargetPermissionOnCancel: Boolean
    ) {
        when (decision) {
            ManagedDownloadDirectoryChangeDecision.APPLY_DIRECTLY ->
                actions.apply(targetUri, targetSummary, previousUri, !previousUri.isNullOrBlank())
            ManagedDownloadDirectoryChangeDecision.REATTACH_EXISTING_TARGET ->
                actions.apply(targetUri, targetSummary, previousUri, false)
            ManagedDownloadDirectoryChangeDecision.CONFIRM_MIGRATION,
            ManagedDownloadDirectoryChangeDecision.CONFIRM_MIGRATION_WITH_NON_EMPTY_TARGET ->
                actions.showPending(
                    PendingDownloadDirectoryChange(
                        previousUri = previousUri,
                        targetUri = targetUri,
                        targetSummary = targetSummary,
                        releaseTargetPermissionOnCancel = releaseTargetPermissionOnCancel,
                        targetNonEmpty = decision ==
                                ManagedDownloadDirectoryChangeDecision.CONFIRM_MIGRATION_WITH_NON_EMPTY_TARGET
                    )
                )
        }
    }
}
