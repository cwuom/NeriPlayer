package moe.ouom.neriplayer.core.startup

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
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
                for (attemptIndex in retryDelaysMs.indices) {
                    if (attemptIndex > 0) {
                        delay(retryDelaysMs[attemptIndex])
                    }

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
                        if (lastUpgradeResult?.isComplete == true) {
                            return@launchBackgroundIo
                        }
                        continue
                    }

                    lastResult = coordinator.execute(plan, confirmed = true)
                    if (
                        lastResult.status == LegacyJsonCleanupStatus.COMPLETED &&
                        lastUpgradeResult?.isComplete == true
                    ) {
                        NPLogger.d(
                            TAG,
                            "Legacy JSON cleanup completed: reason=$reason, " +
                                "deleted=${lastResult.deletedFiles.size}"
                        )
                        return@launchBackgroundIo
                    }
                }

                lastUpgradeResult?.takeUnless(LegacyDownloadUpgradeResult::isComplete)?.let { result ->
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

    suspend fun runDownloadUpgradeOnce(context: Context): LegacyDownloadUpgradeResult {
        return downloadUpgradeMutex.withLock {
            LegacyDownloadUpgradeCoordinator(context.applicationContext).execute()
        }
    }
}
