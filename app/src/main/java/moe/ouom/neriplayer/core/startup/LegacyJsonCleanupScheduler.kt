package moe.ouom.neriplayer.core.startup

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingBusyException
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingPhase
import moe.ouom.neriplayer.core.download.ManagedLibraryProcessingReason
import moe.ouom.neriplayer.core.download.storage.queue.DownloadRecoveryRoomStore
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.store.LegacyDownloadUpgradeCoordinator
import moe.ouom.neriplayer.data.local.database.store.LegacyDownloadUpgradeResult
import moe.ouom.neriplayer.data.local.database.store.LegacyJsonCleanupCoordinator
import moe.ouom.neriplayer.data.local.database.store.LegacyJsonCleanupResult
import moe.ouom.neriplayer.data.local.database.store.LegacyJsonCleanupStatus

internal object LegacyJsonCleanupScheduler {
    private const val TAG = "NERI-LegacyJsonCleanup"
    private val running = AtomicBoolean(false)
    private val quarantineRecoveryRunning = AtomicBoolean(false)
    private val pendingReason = AtomicReference<String?>(null)
    private val downloadUpgradeMutex = Mutex()
    private val retryDelaysMs = longArrayOf(
        0L,
        1_500L,
        3_000L,
        5_000L,
        8_000L,
        13_000L
    )

    fun schedule(context: Context, reason: String) {
        val appContext = context.applicationContext
        if (!running.compareAndSet(false, true)) {
            pendingReason.set(reason)
            return
        }

        AppContainer.launchBackgroundIo {
            try {
                val coordinator = LegacyJsonCleanupCoordinator(appContext)
                var lastUpgradeResult: LegacyDownloadUpgradeResult? = null
                var lastResult: LegacyJsonCleanupResult? = null
                val upgradeGate = LegacyDownloadUpgradeDrainGate()
                for (attemptIndex in retryDelaysMs.indices) {
                    if (attemptIndex > 0) {
                        delay(retryDelaysMs[attemptIndex])
                    }

                    if (upgradeGate.claimAttempt()) {
                        lastUpgradeResult = try {
                            runDownloadUpgradeOnce(appContext)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            NPLogger.w(
                                TAG,
                                "Legacy download upgrade pending: ${error.message}"
                            )
                            null
                        }
                        if ((lastUpgradeResult?.rowsCompleted ?: 0) > 0) {
                            try {
                                GlobalDownloadManager.reconcileMaterializedLegacyDownloads(appContext)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                NPLogger.w(
                                    TAG,
                                    "Legacy download finalization pending: ${error.message}"
                                )
                            }
                        }
                    }
                    runCatching {
                        DownloadRecoveryRoomStore(appContext).bootstrapLegacyFilesOnce()
                    }.onFailure { error ->
                        NPLogger.w(
                            TAG,
                            "Legacy download queue bootstrap pending: ${error.message}"
                        )
                    }
                    val plan = coordinator.buildPlan()
                    if (plan.targets.none { it.exists }) {
                        if (lastUpgradeResult?.isSettled == true) {
                            return@launchBackgroundIo
                        }
                        continue
                    }

                    lastResult = coordinator.execute(plan, confirmed = true)
                    if (
                        lastResult.status == LegacyJsonCleanupStatus.COMPLETED &&
                        lastUpgradeResult?.isSettled == true
                    ) {
                        NPLogger.d(
                            TAG,
                            "Legacy JSON cleanup completed: reason=$reason, " +
                                "deleted=${lastResult.deletedFiles.size}"
                        )
                        return@launchBackgroundIo
                    }
                    if (
                        lastResult.status != LegacyJsonCleanupStatus.PARTIAL_FAILURE &&
                        lastUpgradeResult?.isUserClearSuppressed == true &&
                        plan.isBlockedOnlyByUserClearedDownloadQueues
                    ) {
                        return@launchBackgroundIo
                    }
                }

                lastUpgradeResult?.takeUnless(LegacyDownloadUpgradeResult::isSettled)?.let { result ->
                    NPLogger.d(
                        TAG,
                        "Legacy download upgrade pending: rows=${result.rowsPending}, " +
                            "completed=${result.rowsCompleted}, " +
                            "payloadTableCleaned=${result.temporaryTableCleaned}, " +
                            "legacyTablesCleaned=${result.legacyProjectionTablesCleaned}"
                    )
                }
                lastResult?.let { result ->
                    NPLogger.d(
                        TAG,
                        "Legacy JSON cleanup pending: reason=$reason, status=${result.status}, " +
                            "deleted=${result.deletedFiles.size}, " +
                            "blocked=${result.blockedFiles}, failed=${result.failedFiles}"
                    )
                }
            } finally {
                running.set(false)
                pendingReason.getAndSet(null)?.let { nextReason ->
                    schedule(appContext, nextReason)
                }
            }
        }
    }

    fun scheduleQuarantineRecovery(context: Context) {
        if (!quarantineRecoveryRunning.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        AppContainer.launchBackgroundIo {
            try {
                val restored = downloadUpgradeMutex.withLock {
                    val snapshot = ManagedDownloadStorage.buildDownloadLibrarySnapshot(appContext)
                    LegacyDownloadUpgradeCoordinator(appContext)
                        .requeueResolvableQuarantinedRows(snapshot)
                }
                if (restored > 0) {
                    NPLogger.d(TAG, "恢复可验证的旧下载隔离载荷: rows=$restored")
                    schedule(appContext, "published-download-quarantine-recovery")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                NPLogger.w(TAG, "旧下载隔离载荷恢复待重试: ${error.message}")
            } finally {
                quarantineRecoveryRunning.set(false)
            }
        }
    }

    suspend fun runDownloadUpgradeOnce(context: Context): LegacyDownloadUpgradeResult {
        val appContext = context.applicationContext
        return downloadUpgradeMutex.withLock {
            ManagedLibraryProcessingCoordinator.restore(appContext)
            val operationId = ManagedLibraryProcessingCoordinator.tryBeginExclusive(
                context = appContext,
                reason = ManagedLibraryProcessingReason.LEGACY_DATABASE_UPGRADE,
                phase = ManagedLibraryProcessingPhase.UPGRADING_DATABASE,
                resumeWaitingOperation = true
            ) ?: throw ManagedLibraryProcessingBusyException(
                ManagedLibraryProcessingCoordinator.state.value.reason
            )
            try {
                LegacyDownloadUpgradeCoordinator(appContext).execute { processed, total ->
                    ManagedLibraryProcessingCoordinator.updateProgress(
                        operationId = operationId,
                        processed = processed,
                        total = total
                    )
                }.also { result ->
                        if (result.isSettled) {
                            if (result.rowsCompleted > 0) {
                                // database rows are durable now, but the visible
                                // catalog still needs one complete SAF rebuild
                                ManagedLibraryProcessingCoordinator.advancePhase(
                                    context = appContext,
                                    operationId = operationId,
                                    phase = ManagedLibraryProcessingPhase.REBUILDING_INDEX
                                )
                            } else {
                                ManagedLibraryProcessingCoordinator.complete(
                                    appContext,
                                    operationId
                                )
                            }
                        } else {
                            ManagedLibraryProcessingCoordinator.waitingForRetry(
                                appContext,
                                operationId
                            )
                        }
                    }
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    runCatching {
                        ManagedLibraryProcessingCoordinator.waitingForRetry(
                            appContext,
                            operationId
                        )
                    }
                }
                throw error
            } catch (error: Throwable) {
                runCatching {
                    ManagedLibraryProcessingCoordinator.waitingForRetry(
                        appContext,
                        operationId
                    )
                }
                throw error
            }
        }
    }
}

internal class LegacyDownloadUpgradeDrainGate {
    private var attempted = false

    fun claimAttempt(): Boolean {
        if (attempted) return false
        attempted = true
        return true
    }
}
