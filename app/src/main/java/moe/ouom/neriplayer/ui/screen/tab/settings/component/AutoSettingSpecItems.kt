package moe.ouom.neriplayer.ui.screen.tab.settings.component

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.outlined.AdsClick
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BluetoothAudio
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.settings.AutoSettingSpecRepository
import moe.ouom.neriplayer.ksp.annotations.AutoSettingIcon
import moe.ouom.neriplayer.ksp.annotations.AutoSettingSpec
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch

@Composable
internal fun rememberAutoSettingSpecRepository(): AutoSettingSpecRepository {
    val context = LocalContext.current
    return remember(context) { AutoSettingSpecRepository(context) }
}

@Composable
internal fun <T> AutoSettingSpecListItem(
    setting: AutoSettingSpec<T>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDefaultIcon: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val title = autoSettingSpecString(setting.titleRes) ?: setting.key
    val description = autoSettingSpecString(setting.descriptionRes)
    val clickableModifier = if (onClick == null) {
        modifier
    } else {
        modifier.settingsItemClickable(enabled = enabled, onClick = onClick)
    }
    val defaultLeadingContent: (@Composable () -> Unit)? = if (showDefaultIcon) {
        {
            AutoSettingSpecIcon(
                painter = autoSettingSpecIconPainter(setting.iconRes),
                imageVector = autoSettingSpecIconVector(setting.icon),
                contentDescription = title
            )
        }
    } else {
        null
    }
    val defaultSupportingContent: (@Composable () -> Unit)? = description?.let { text ->
        { Text(text) }
    }

    ListItem(
        modifier = clickableModifier,
        leadingContent = leadingContent ?: defaultLeadingContent,
        headlineContent = { Text(title) },
        supportingContent = supportingContent ?: defaultSupportingContent,
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
internal fun AutoSettingSpecSwitchItem(
    setting: AutoSettingSpec<Boolean>,
    modifier: Modifier = Modifier,
    repository: AutoSettingSpecRepository = rememberAutoSettingSpecRepository(),
    enabled: Boolean = true,
    showDefaultIcon: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    afterCheckedChange: ((Boolean) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val flow = remember(repository, setting) { repository.flow(setting) }
    val checked by flow.collectAsState(initial = setting.defaultValue)

    AutoSettingSpecSwitchItem(
        setting = setting,
        checked = checked,
        onCheckedChange = { value ->
            scope.launch {
                repository.set(setting, value)
                afterCheckedChange?.invoke(value)
            }
        },
        modifier = modifier,
        enabled = enabled,
        showDefaultIcon = showDefaultIcon,
        leadingContent = leadingContent,
        supportingContent = supportingContent
    )
}

@Composable
internal fun AutoSettingSpecSwitchItem(
    setting: AutoSettingSpec<Boolean>,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDefaultIcon: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null
) {
    AutoSettingSpecListItem(
        setting = setting,
        modifier = modifier,
        enabled = enabled,
        showDefaultIcon = showDefaultIcon,
        leadingContent = leadingContent,
        supportingContent = supportingContent,
        trailingContent = {
            MiuixSettingsSwitch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        },
        onClick = {
            onCheckedChange(!checked)
        }
    )
}

@Composable
private fun autoSettingSpecIconPainter(iconRes: Int): Painter? {
    return if (iconRes == 0) null else painterResource(iconRes)
}

private fun autoSettingSpecIconVector(icon: AutoSettingIcon): ImageVector? {
    return when (icon) {
        AutoSettingIcon.None -> null
        AutoSettingIcon.AccountCircle -> Icons.Filled.AccountCircle
        AutoSettingIcon.AdsClick -> Icons.Outlined.AdsClick
        AutoSettingIcon.Analytics -> Icons.Outlined.Analytics
        AutoSettingIcon.Audiotrack -> Icons.Filled.Audiotrack
        AutoSettingIcon.AutoAwesome -> Icons.Outlined.AutoAwesome
        AutoSettingIcon.BlurOn -> Icons.Outlined.BlurOn
        AutoSettingIcon.BluetoothAudio -> Icons.Outlined.BluetoothAudio
        AutoSettingIcon.Bolt -> Icons.Outlined.Bolt
        AutoSettingIcon.Brightness4 -> Icons.Outlined.Brightness4
        AutoSettingIcon.Cloud -> Icons.Outlined.Cloud
        AutoSettingIcon.Colorize -> Icons.Outlined.Colorize
        AutoSettingIcon.Download -> Icons.Outlined.Download
        AutoSettingIcon.Error -> Icons.Outlined.Error
        AutoSettingIcon.Home -> Icons.Outlined.Home
        AutoSettingIcon.Info -> Icons.Outlined.Info
        AutoSettingIcon.Keyboard -> Icons.Outlined.Keyboard
        AutoSettingIcon.Layers -> Icons.Outlined.Layers
        AutoSettingIcon.LibraryMusic -> Icons.Outlined.LibraryMusic
        AutoSettingIcon.Palette -> Icons.Outlined.Palette
        AutoSettingIcon.PlaylistPlay -> Icons.AutoMirrored.Outlined.PlaylistPlay
        AutoSettingIcon.Router -> Icons.Outlined.Router
        AutoSettingIcon.Settings -> Icons.Outlined.Settings
        AutoSettingIcon.Storage -> Icons.Outlined.Storage
        AutoSettingIcon.Subtitles -> Icons.Outlined.Subtitles
        AutoSettingIcon.Sync -> Icons.Outlined.Sync
        AutoSettingIcon.Tune -> Icons.Outlined.Tune
        AutoSettingIcon.Wallpaper -> Icons.Outlined.Wallpaper
    }
}

@Composable
private fun autoSettingSpecString(resId: Int): String? {
    return if (resId == 0) null else stringResource(resId)
}

@Composable
private fun AutoSettingSpecIcon(
    painter: Painter?,
    imageVector: ImageVector?,
    contentDescription: String
) {
    if (painter != null) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
        return
    }
    if (imageVector != null) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurface
        )
        return
    }
    Icon(
        imageVector = Icons.Outlined.Settings,
        contentDescription = contentDescription,
        modifier = Modifier.size(24.dp),
        tint = MaterialTheme.colorScheme.onSurface
    )
}
