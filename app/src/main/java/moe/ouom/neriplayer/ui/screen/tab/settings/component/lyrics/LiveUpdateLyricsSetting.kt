package moe.ouom.neriplayer.ui.screen.tab.settings.component.lyrics

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.player.service.lyrics.LiveUpdateNotificationStatus
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.ui.screen.tab.settings.component.AutoSettingSpecListItem
import moe.ouom.neriplayer.ui.screen.tab.settings.component.openNotificationSettings
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch

/** Live Update 能否真正出现取决于多处系统开关，按用户最先需要处理的顺序给出一个状态 */
internal enum class LiveUpdateSetupStatus(@param:StringRes val messageRes: Int, val actionable: Boolean) {
    Unsupported(CoreCommonR.string.settings_live_update_status_unsupported, actionable = false),
    NotificationsDisabled(CoreCommonR.string.settings_live_update_status_notifications_off, actionable = true),
    ChannelBlocked(CoreCommonR.string.settings_live_update_status_channel_off, actionable = true),
    PromotionDisabled(CoreCommonR.string.settings_live_update_status_promotion_off, actionable = true),
    SystemDeclined(CoreCommonR.string.settings_live_update_status_declined, actionable = false),
    Ready(CoreCommonR.string.settings_live_update_status_ready, actionable = false),
}

internal fun resolveLiveUpdateSetupStatus(
    sdkInt: Int,
    notificationsEnabled: Boolean,
    channelBlocked: Boolean,
    canPostPromoted: Boolean?,
    systemDeclinedPromotion: Boolean,
): LiveUpdateSetupStatus = when {
    sdkInt < Build.VERSION_CODES.BAKLAVA -> LiveUpdateSetupStatus.Unsupported
    !notificationsEnabled -> LiveUpdateSetupStatus.NotificationsDisabled
    channelBlocked -> LiveUpdateSetupStatus.ChannelBlocked
    canPostPromoted == false -> LiveUpdateSetupStatus.PromotionDisabled
    systemDeclinedPromotion -> LiveUpdateSetupStatus.SystemDeclined
    else -> LiveUpdateSetupStatus.Ready
}

private data class LiveUpdateSystemProbe(
    val notificationsEnabled: Boolean,
    val channelBlocked: Boolean,
    val canPostPromoted: Boolean?,
)

@Composable
internal fun LiveUpdateLyricsSetting(
    repository: AutoSettingsRepository,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by repository.liveUpdateLyricEnabledFlow.collectAsState(initial = false)
    val systemDeclined by LiveUpdateNotificationStatus.systemDeclinedPromotion.collectAsState()
    var probe by remember(context) { mutableStateOf(probeLiveUpdateSystem(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // 用户从系统设置返回时重新读取开关状态
    DisposableEffect(context, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) probe = probeLiveUpdateSystem(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val status = resolveLiveUpdateSetupStatus(
        sdkInt = Build.VERSION.SDK_INT,
        notificationsEnabled = probe.notificationsEnabled,
        channelBlocked = probe.channelBlocked,
        canPostPromoted = probe.canPostPromoted,
        systemDeclinedPromotion = systemDeclined,
    )
    // 不支持的系统上只允许关闭，避免用户开启后什么也看不到
    val toggleable = status != LiveUpdateSetupStatus.Unsupported || enabled
    val setEnabled: (Boolean) -> Unit = { next -> scope.launch { repository.setLiveUpdateLyricEnabled(next) } }
    AutoSettingSpecListItem(
        setting = AutoSettingsSchema.lyrics.liveUpdateLyricEnabled,
        enabled = toggleable,
        supportingContent = {
            Column {
                Text(stringResource(CoreCommonR.string.settings_live_update_lyric_enabled_desc))
                if (enabled || status == LiveUpdateSetupStatus.Unsupported) {
                    Text(
                        text = stringResource(status.messageRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status == LiveUpdateSetupStatus.Ready) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
        trailingContent = {
            MiuixSettingsSwitch(checked = enabled, enabled = toggleable, onCheckedChange = setEnabled)
        },
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished,
        onClick = {
            if (enabled && status.actionable) openLiveUpdateSystemSettings(context, status) else setEnabled(!enabled)
        },
    )
}

private fun probeLiveUpdateSystem(context: Context): LiveUpdateSystemProbe {
    val manager = NotificationManagerCompat.from(context)
    val channel = manager.getNotificationChannel(LiveUpdateNotificationStatus.CHANNEL_ID)
    return LiveUpdateSystemProbe(
        notificationsEnabled = manager.areNotificationsEnabled(),
        channelBlocked = channel?.importance == NotificationManager.IMPORTANCE_NONE,
        canPostPromoted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            runCatching { manager.canPostPromotedNotifications() }.getOrNull()
        } else {
            null
        },
    )
}

/** 精确页面在部分厂商系统上不存在，失败时退回应用通知设置 */
private fun openLiveUpdateSystemSettings(context: Context, status: LiveUpdateSetupStatus) {
    val precise = when (status) {
        LiveUpdateSetupStatus.ChannelBlocked -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, LiveUpdateNotificationStatus.CHANNEL_ID)
        LiveUpdateSetupStatus.PromotionDisabled -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            null
        }
        else -> null
    }
    if (precise != null) {
        try {
            context.startActivity(precise.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // 继续退回通用的应用通知设置
        }
    }
    openNotificationSettings(context)
}
