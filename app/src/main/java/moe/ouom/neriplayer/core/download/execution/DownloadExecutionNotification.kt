package moe.ouom.neriplayer.core.download.execution

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import moe.ouom.neriplayer.R

internal const val DOWNLOAD_EXECUTION_NOTIFICATION_CHANNEL_ID = "download_execution"

/** 构建统一的后台下载通知，避免把内部调度标识暴露给用户 */
internal fun buildDownloadExecutionNotification(context: Context): Notification {
    val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.createNotificationChannel(
        NotificationChannel(
            DOWNLOAD_EXECUTION_NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.download_execution_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
    )
    return NotificationCompat.Builder(
        context,
        DOWNLOAD_EXECUTION_NOTIFICATION_CHANNEL_ID
    )
        .setSmallIcon(R.drawable.ic_notification_small)
        .setContentTitle(context.getString(R.string.download_execution_notification_title))
        .setContentText(context.getString(R.string.download_execution_notification_content))
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(0, 0, true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
}
