package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Tune
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledAlertDialog as AlertDialog
import moe.ouom.neriplayer.ui.component.overlay.ApplyOverlayWindowNavigationBarPolicy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.shape.CircleShape
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape
import moe.ouom.neriplayer.ui.theme.NeriDesign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.haptic.HapticOutlinedButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.R as AppR

private val MaterialOnboardingCardShape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
private val MaterialOnboardingControlShape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
private val CleanOnboardingCardShape = ContinuousRoundedCornerShape(22.dp)

// 引导页卡片随界面风格切换: Material 大圆角, 简洁风格连续曲率圆角与胶囊控件
internal val OnboardingCardShape: Shape
    @Composable
    @ReadOnlyComposable
    get() = if (NeriDesign.isClean) CleanOnboardingCardShape else MaterialOnboardingCardShape

internal val OnboardingControlShape: Shape
    @Composable
    @ReadOnlyComposable
    get() = if (NeriDesign.isClean) CircleShape else MaterialOnboardingControlShape

@Composable
internal fun OnboardingGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = OnboardingCardShape,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: @Composable BoxScope.() -> Unit
) {
    AdvancedGlassSurface(
        role = AdvancedGlassRole.SemanticCard,
        modifier = modifier,
        shape = shape,
        fallbackColor = color,
        tintColor = color,
        suppressInactiveNavigationSurface = true,
        content = content
    )
}

internal fun shouldConfirmStartupPlaybackSourceFallback(
    requestedEnabled: Boolean,
    currentlyEnabled: Boolean
): Boolean = requestedEnabled && !currentlyEnabled

@Composable
internal fun StartupPermissionContent(
    notificationPermissionSupported: Boolean,
    notificationPermissionGranted: Boolean,
    localMediaPermissionGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onRequestLocalMediaPermission: () -> Unit
) {
    PermissionGuidanceItem(
        icon = Icons.Rounded.NotificationsActive,
        title = stringResource(AppR.string.onboarding_permission_notification_title),
        description = stringResource(
            if (notificationPermissionSupported) {
                AppR.string.onboarding_permission_notification_desc
            } else {
                AppR.string.onboarding_permission_notification_legacy_desc
            }
        ),
        granted = notificationPermissionGranted || !notificationPermissionSupported,
        onRequest = onRequestNotificationPermission
    )
    Spacer(Modifier.height(12.dp))
    PermissionGuidanceItem(
        icon = Icons.Outlined.LibraryMusic,
        title = stringResource(AppR.string.onboarding_permission_media_title),
        description = stringResource(AppR.string.onboarding_permission_media_desc),
        granted = localMediaPermissionGranted,
        onRequest = onRequestLocalMediaPermission
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = stringResource(AppR.string.onboarding_permissions_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun StartupPlaybackSourceContent(
    autoSourceSwitchEnabled: Boolean,
    localSourceFallbackEnabled: Boolean,
    onSetFallbackEnabled: (Boolean) -> Unit
) {
    var showConfirmation by remember { mutableStateOf(false) }
    val fallbackEnabled = autoSourceSwitchEnabled && localSourceFallbackEnabled
    val partiallyEnabled = autoSourceSwitchEnabled.xor(localSourceFallbackEnabled)

    OnboardingGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = OnboardingCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(AppR.string.onboarding_playback_sources_switch_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(AppR.string.onboarding_playback_sources_switch_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (partiallyEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(AppR.string.onboarding_playback_sources_partial_status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            Switch(
                checked = fallbackEnabled,
                onCheckedChange = { enabled ->
                    if (shouldConfirmStartupPlaybackSourceFallback(enabled, fallbackEnabled)) {
                        showConfirmation = true
                    } else {
                        onSetFallbackEnabled(enabled)
                    }
                }
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    ControlSection(
        title = stringResource(AppR.string.onboarding_playback_sources_detail_title),
        description = stringResource(AppR.string.onboarding_playback_sources_detail_desc)
    ) {
        Text(
            text = stringResource(AppR.string.onboarding_playback_sources_detail_bullets),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = {
                Text(stringResource(AppR.string.onboarding_playback_sources_confirm_title))
            },
            text = {
                Text(stringResource(AppR.string.onboarding_playback_sources_confirm_desc))
            },
            confirmButton = {
                ApplyOverlayWindowNavigationBarPolicy()
                HapticTextButton(
                    onClick = {
                        showConfirmation = false
                        onSetFallbackEnabled(true)
                    }
                ) {
                    Text(stringResource(AppR.string.onboarding_playback_sources_confirm_enable))
                }
            },
            dismissButton = {
                HapticTextButton(onClick = { showConfirmation = false }) {
                    Text(stringResource(CoreCommonR.string.action_cancel))
                }
            }
        )
    }
}

@Composable
internal fun StartupLearningGuideContent() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LearningGuideItem(
            icon = Icons.Filled.PlayArrow,
            title = stringResource(AppR.string.onboarding_learning_now_playing_title),
            body = stringResource(AppR.string.onboarding_learning_now_playing_desc)
        )
        LearningGuideItem(
            icon = Icons.Outlined.LibraryMusic,
            title = stringResource(AppR.string.onboarding_learning_lyrics_title),
            body = stringResource(AppR.string.onboarding_learning_lyrics_desc)
        )
        LearningGuideItem(
            icon = Icons.AutoMirrored.Outlined.QueueMusic,
            title = stringResource(AppR.string.onboarding_learning_playlist_selection_title),
            body = stringResource(AppR.string.onboarding_learning_playlist_selection_desc)
        )
        LearningGuideItem(
            icon = Icons.AutoMirrored.Outlined.PlaylistAdd,
            title = stringResource(AppR.string.onboarding_learning_download_title),
            body = stringResource(AppR.string.onboarding_learning_download_desc)
        )
        LearningGuideItem(
            icon = Icons.Outlined.CloudSync,
            title = stringResource(AppR.string.onboarding_learning_backup_title),
            body = stringResource(AppR.string.onboarding_learning_backup_desc)
        )
        LearningGuideItem(
            icon = Icons.Filled.SpeakerGroup,
            title = stringResource(AppR.string.onboarding_learning_listen_together_title),
            body = stringResource(AppR.string.onboarding_learning_listen_together_desc)
        )
        LearningGuideItem(
            icon = Icons.Outlined.Tune,
            title = stringResource(AppR.string.onboarding_learning_queue_title),
            body = stringResource(AppR.string.onboarding_learning_queue_desc)
        )
    }
}

@Composable
private fun LearningGuideItem(
    icon: ImageVector,
    title: String,
    body: String
) {
    val colors = MaterialTheme.colorScheme
    OnboardingGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = OnboardingCardShape,
        color = colors.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = OnboardingControlShape,
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PermissionGuidanceItem(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    OnboardingGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = OnboardingCardShape,
        color = colors.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tile by animateColorAsState(
                targetValue = if (granted) colors.primary else colors.primaryContainer,
                animationSpec = tween(320),
                label = "startup_permission_tile"
            )
            // 授权后图标位换成对勾, 卡片本身就能看出状态
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(tile, OnboardingControlShape),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = granted,
                    transitionSpec = {
                        (fadeIn(tween(200)) + scaleIn(spring(0.5f, 500f), initialScale = 0.5f))
                            .togetherWith(fadeOut(tween(120)))
                    },
                    label = "startup_permission_icon"
                ) { isGranted ->
                    Icon(
                        imageVector = if (isGranted) Icons.Rounded.Check else icon,
                        contentDescription = null,
                        tint = if (isGranted) colors.onPrimary else colors.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            HapticOutlinedButton(
                onClick = onRequest,
                enabled = !granted,
                shape = OnboardingControlShape,
                modifier = Modifier.widthIn(min = 72.dp)
            ) {
                Text(
                    text = if (granted) {
                        stringResource(AppR.string.onboarding_permission_granted)
                    } else {
                        stringResource(AppR.string.onboarding_permission_allow)
                    },
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ControlSection(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    OnboardingGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = OnboardingCardShape,
        color = colors.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            content()
        }
    }
}
