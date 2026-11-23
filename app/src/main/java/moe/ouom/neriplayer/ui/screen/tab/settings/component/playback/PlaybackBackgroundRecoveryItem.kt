package moe.ouom.neriplayer.ui.screen.tab.settings.component.playback

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.system.BackgroundBehaviorAllowance
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.util.platform.openAppBackgroundSettings
import moe.ouom.neriplayer.util.platform.readBackgroundBehaviorAllowance
import moe.ouom.neriplayer.util.platform.requestIgnoreBatteryOptimizationsCompat

internal const val PLAYBACK_BACKGROUND_RECOVERY_ITEM_TAG = "settings_playback_background_recovery"

/**
 * 进程被游戏挤占或 WebView 更新结束后, Android 12+ 不允许后台重启的服务再进前台;
 * 只有关闭电池优化的应用能被系统拉起并续播, 所以由用户主动点按申请, 不自动弹窗
 */
@Composable
internal fun PlaybackBackgroundRecoveryItem(
    readAllowance: () -> BackgroundBehaviorAllowance = defaultAllowanceReader(),
    onRequest: (BackgroundBehaviorAllowance) -> Unit = defaultRequestHandler()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var allowance by remember { mutableStateOf(readAllowance()) }
    // 从系统设置返回时重新读取, 否则状态停留在点按前
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) allowance = readAllowance()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val fullyAllowed = allowance.fullyAllowed
    val title = stringResource(CoreCommonR.string.settings_playback_background_recovery)

    ListItem(
        modifier = Modifier
            .testTag(PLAYBACK_BACKGROUND_RECOVERY_ITEM_TAG)
            .settingsItemClickable(enabled = !fullyAllowed) { onRequest(allowance) },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.BatteryChargingFull,
                contentDescription = title,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                stringResource(
                    if (fullyAllowed) {
                        CoreCommonR.string.settings_playback_background_recovery_allowed
                    } else {
                        CoreCommonR.string.settings_playback_background_recovery_restricted
                    }
                )
            )
        },
        trailingContent = if (!fullyAllowed) {
            {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.64f)
                )
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun defaultAllowanceReader(): () -> BackgroundBehaviorAllowance {
    val context = LocalContext.current.applicationContext
    return remember(context) { { context.readBackgroundBehaviorAllowance() } }
}

@Composable
private fun defaultRequestHandler(): (BackgroundBehaviorAllowance) -> Unit {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        { allowance ->
            if (!allowance.ignoringBatteryOptimizations) {
                context.requestIgnoreBatteryOptimizationsCompat()
            } else {
                context.openAppBackgroundSettings()
            }
        }
    }
}
