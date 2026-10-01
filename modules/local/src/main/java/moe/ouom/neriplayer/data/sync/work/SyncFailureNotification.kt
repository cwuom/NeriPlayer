package moe.ouom.neriplayer.data.sync.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import moe.ouom.neriplayer.common.R as CoreCommonR

internal class SyncFailureNotification(
    private val context: Context,
    private val channelId: String,
    private val notificationId: Int,
    @param:StringRes private val channelName: Int,
    @param:StringRes private val channelDescription: Int,
    @param:StringRes private val title: Int
) {
    fun show(message: String) {
        if (!canNotify()) return
        deliver(message)
    }

    private fun deliver(message: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(channelId, context.getString(channelName), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(channelDescription)
        manager.createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(CoreCommonR.drawable.ic_notification_small)
            .setContentTitle(context.getString(title)).setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true).build()
        manager.notify(notificationId, notification)
    }

    private fun canNotify(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
