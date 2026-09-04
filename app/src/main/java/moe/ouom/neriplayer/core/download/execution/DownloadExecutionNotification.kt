package moe.ouom.neriplayer.core.download.execution

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.BatchDownloadOverallProgress
import moe.ouom.neriplayer.core.download.DownloadStatus
import moe.ouom.neriplayer.core.download.DownloadTask
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.downloadProgressFraction
import moe.ouom.neriplayer.core.download.formatDownloadTransferProgress
import moe.ouom.neriplayer.data.model.displayName

internal const val DOWNLOAD_EXECUTION_NOTIFICATION_CHANNEL_ID = "download_execution"

/** 所有下载宿主共用一个通知，避免并发歌曲各自占一张通知卡片 */
internal const val DOWNLOAD_EXECUTION_NOTIFICATION_ID = 0x6000_0001

/** 旧版本按 operation 分配的通知区间，只用于升级后的残留清理 */
internal const val LEGACY_FOREGROUND_NOTIFICATION_MIN = 0x4000_0000
internal const val LEGACY_FOREGROUND_NOTIFICATION_MAX = 0x4fff_ffff
internal const val LEGACY_UIDT_NOTIFICATION_MIN = 0x5000_0000
internal const val LEGACY_UIDT_NOTIFICATION_MAX = 0x5fff_ffff

internal fun isLegacyDownloadExecutionNotificationId(id: Int): Boolean {
    return id in LEGACY_FOREGROUND_NOTIFICATION_MIN..LEGACY_FOREGROUND_NOTIFICATION_MAX ||
        id in LEGACY_UIDT_NOTIFICATION_MIN..LEGACY_UIDT_NOTIFICATION_MAX
}

internal data class DownloadExecutionNotificationSnapshot(
    val totalSongs: Int = 0,
    val completedSongs: Int = 0,
    val remainingSongs: Int = 0,
    val overallPercentage: Int? = null,
    val currentSong: String? = null,
    val currentTransfer: String? = null,
    val hasWork: Boolean = false
)

/**
 * 从现有下载展示状态计算通知摘要，不把未知的字节总量伪装成确定百分比
 */
internal fun deriveDownloadExecutionNotificationSnapshot(
    tasks: List<DownloadTask>,
    batchProgress: BatchDownloadOverallProgress?,
    hasActiveOperations: Boolean
): DownloadExecutionNotificationSnapshot {
    val activeTasks = tasks.filter { task ->
        task.status == DownloadStatus.QUEUED ||
            task.status == DownloadStatus.DOWNLOADING ||
            task.status == DownloadStatus.WAITING_NETWORK
    }
    val completedTaskCount = tasks.count { task -> task.status == DownloadStatus.COMPLETED }
    val taskTotal = tasks.count { task -> task.status != DownloadStatus.CANCELLED }
    val currentTask = activeTasks.firstOrNull { task ->
        task.status == DownloadStatus.DOWNLOADING
    } ?: activeTasks.firstOrNull()

    if (batchProgress != null && batchProgress.totalSongs > 0) {
        val normalizedCompletedSongs = batchProgress.completedSongs.coerceIn(
            0,
            batchProgress.totalSongs
        )
        return DownloadExecutionNotificationSnapshot(
            totalSongs = batchProgress.totalSongs,
            completedSongs = normalizedCompletedSongs,
            remainingSongs = (batchProgress.totalSongs - normalizedCompletedSongs)
                .coerceAtLeast(0),
            overallPercentage = batchProgress.percentage.coerceIn(0, 100),
            currentSong = currentTask?.song?.displayName()?.trim()?.takeIf(String::isNotBlank),
            currentTransfer = currentTask?.progress?.let(::formatDownloadTransferProgress),
            hasWork = hasActiveOperations || activeTasks.isNotEmpty() ||
                batchProgress.hasPendingSongs
        )
    }

    val totalSongs = taskTotal.coerceAtLeast(activeTasks.size)
    val completedSongs = completedTaskCount.coerceIn(0, totalSongs)
    val remainingSongs = (totalSongs - completedSongs).coerceAtLeast(0)
    val knownProgresses = activeTasks.mapNotNull { task ->
        task.progress?.takeIf { progress -> progress.totalBytes > 0L }
    }
    val overallPercentage = when {
        totalSongs <= 0 -> null
        activeTasks.isEmpty() && completedSongs >= totalSongs -> 100
        knownProgresses.isEmpty() && completedSongs == 0 -> null
        else -> {
            val completedFraction = completedSongs.toFloat()
            val activeFraction = activeTasks.sumOf { task ->
                task.progress?.let(::downloadProgressFraction)?.toDouble() ?: 0.0
            }.toFloat()
            ((completedFraction + activeFraction) / totalSongs.toFloat() * 100f)
                .toInt()
                .coerceIn(0, 99)
        }
    }
    return DownloadExecutionNotificationSnapshot(
        totalSongs = totalSongs,
        completedSongs = completedSongs,
        remainingSongs = remainingSongs,
        overallPercentage = overallPercentage,
        currentSong = currentTask?.song?.displayName()?.trim()?.takeIf(String::isNotBlank),
        currentTransfer = currentTask?.progress?.let(::formatDownloadTransferProgress),
        hasWork = hasActiveOperations || activeTasks.isNotEmpty()
    )
}

/** 构建统一的后台下载通知，并展示批量数量与当前歌曲传输进度 */
internal fun buildDownloadExecutionNotification(
    context: Context,
    snapshot: DownloadExecutionNotificationSnapshot = currentDownloadExecutionNotificationSnapshot()
): Notification {
    val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(
        NotificationChannel(
            DOWNLOAD_EXECUTION_NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.download_execution_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
    )
    val contentText = notificationContent(context, snapshot)
    val detailText = notificationDetail(context, snapshot, contentText)
    val percentage = snapshot.overallPercentage
    return NotificationCompat.Builder(
        context,
        DOWNLOAD_EXECUTION_NOTIFICATION_CHANNEL_ID
    )
        .setSmallIcon(R.drawable.ic_notification_small)
        .setContentTitle(context.getString(R.string.download_execution_notification_title))
        .setContentText(contentText)
        .setSubText(snapshot.currentSong)
        .setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setOngoing(true)
        .setAutoCancel(false)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setProgress(100, percentage ?: 0, percentage == null)
        .setContentInfo(percentage?.let { value -> "$value%" })
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
}

private fun notificationContent(
    context: Context,
    snapshot: DownloadExecutionNotificationSnapshot
): String {
    if (snapshot.totalSongs <= 0) {
        return context.getString(R.string.download_execution_notification_content)
    }
    return if (snapshot.overallPercentage == null) {
        context.getString(
            R.string.download_execution_notification_progress,
            snapshot.completedSongs,
            snapshot.totalSongs,
            snapshot.remainingSongs
        )
    } else {
        context.getString(
            R.string.download_execution_notification_progress_with_percentage,
            snapshot.completedSongs,
            snapshot.totalSongs,
            snapshot.remainingSongs,
            snapshot.overallPercentage
        )
    }
}

private fun notificationDetail(
    context: Context,
    snapshot: DownloadExecutionNotificationSnapshot,
    fallback: String
): String {
    val currentSong = snapshot.currentSong ?: return fallback
    val transfer = snapshot.currentTransfer
    val currentDetail = if (transfer.isNullOrBlank()) {
        context.getString(
            R.string.download_execution_notification_current_unknown,
            currentSong
        )
    } else {
        context.getString(
            R.string.download_execution_notification_current_detail,
            currentSong,
            transfer
        )
    }
    return listOf(fallback, currentDetail)
        .filter(String::isNotBlank)
        .joinToString("\n")
}

private fun currentDownloadExecutionNotificationSnapshot():
    DownloadExecutionNotificationSnapshot {
    return deriveDownloadExecutionNotificationSnapshot(
        tasks = GlobalDownloadManager.downloadTasks.value,
        batchProgress = GlobalDownloadManager.batchDownloadProgressFlow.value,
        hasActiveOperations = GlobalDownloadManager.activeDownloadOperationsFlow.value
    )
}

/** 负责在多个宿主之间复用通知 ID，同时让下载流变化能够刷新同一张卡片 */
internal object DownloadExecutionNotificationController {
    private const val REFRESH_AFTER_RELEASE_MS = 300L
    private const val LEGACY_CLEANUP_INTERVAL_MS = 2_000L
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeOwners = ConcurrentHashMap.newKeySet<String>()
    private var observerJob: Job? = null
    private var lastLegacyCleanupElapsedMs = 0L

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        ensureObserver(appContext)
        refresh(appContext)
    }

    fun acquire(context: Context, owner: String) {
        val appContext = context.applicationContext
        activeOwners += owner
        ensureObserver(appContext)
        refresh(appContext)
    }

    fun release(context: Context, owner: String) {
        val appContext = context.applicationContext
        activeOwners.remove(owner)
        refresh(appContext)
        scope.launch {
            delay(REFRESH_AFTER_RELEASE_MS)
            refresh(appContext)
        }
    }

    fun refresh(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            val snapshot = currentDownloadExecutionNotificationSnapshot()
            val shouldShow = activeOwners.isNotEmpty() || snapshot.hasWork
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return
            runCatching {
                if (shouldShow) {
                    manager.notify(
                        DOWNLOAD_EXECUTION_NOTIFICATION_ID,
                        buildDownloadExecutionNotification(appContext, snapshot)
                    )
                } else {
                    manager.cancel(DOWNLOAD_EXECUTION_NOTIFICATION_ID)
                }
                cancelLegacyNotificationsIfDue(manager)
            }
        }
    }

    private fun cancelLegacyNotificationsIfDue(manager: NotificationManager) {
        val now = SystemClock.elapsedRealtime()
        if (
            lastLegacyCleanupElapsedMs != 0L &&
                now - lastLegacyCleanupElapsedMs < LEGACY_CLEANUP_INTERVAL_MS
        ) {
            return
        }
        lastLegacyCleanupElapsedMs = now
        manager.activeNotifications
            .asSequence()
            .filter { notification ->
                isLegacyDownloadExecutionNotificationId(notification.id)
            }
            .forEach { notification ->
                manager.cancel(notification.tag, notification.id)
            }
    }

    private fun ensureObserver(context: Context) {
        synchronized(lock) {
            if (observerJob?.isActive == true) return
            observerJob = scope.launch {
                combine(
                    GlobalDownloadManager.downloadTasks,
                    GlobalDownloadManager.batchDownloadProgressFlow,
                    GlobalDownloadManager.activeDownloadOperationsFlow
                ) { tasks, batchProgress, hasActiveOperations ->
                    deriveDownloadExecutionNotificationSnapshot(
                        tasks = tasks,
                        batchProgress = batchProgress,
                        hasActiveOperations = hasActiveOperations
                    )
                }.distinctUntilChanged().collect {
                    refresh(context)
                }
            }
        }
    }
}
