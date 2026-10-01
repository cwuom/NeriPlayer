package moe.ouom.neriplayer.ui.settings

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
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.ZoomInMap
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.settings.generated.AutoSettingInfo
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsSectionScope
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsSwitchBindings
import moe.ouom.neriplayer.ksp.annotations.AutoSettingIcon
import moe.ouom.neriplayer.ui.screen.tab.settings.component.settingsItemClickable
import moe.ouom.neriplayer.ui.screen.tab.settings.miuix.MiuixSettingsSwitch
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsHighlightTarget

@Composable
fun AutoSettingsListItem(
    setting: AutoSettingInfo,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDefaultIcon: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val title = autoSettingsString(setting.titleRes) ?: setting.keyName
    val description = autoSettingsString(setting.descriptionRes)
    val highlightedModifier = modifier.settingsHighlightTarget(
        targetId = "setting:${setting.keyName}",
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
    val clickableModifier = if (onClick == null) {
        highlightedModifier
    } else {
        highlightedModifier.settingsItemClickable(enabled = enabled, onClick = onClick)
    }
    val autoLeadingContent: (@Composable () -> Unit)? = when {
        leadingContent != null -> leadingContent
        showDefaultIcon -> {
            {
                AutoSettingsIcon(
                    painter = autoSettingsIconPainter(setting.iconRes),
                    imageVector = autoSettingsIconVector(setting.icon),
                    contentDescription = title
                )
            }
        }
        else -> null
    }
    ListItem(
        modifier = clickableModifier,
        leadingContent = autoLeadingContent,
        headlineContent = { Text(title) },
        supportingContent = supportingContent ?: description?.let { text ->
            { Text(text) }
        },
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
fun AutoSettingsSwitchItems(
    repository: AutoSettingsRepository,
    scope: CoroutineScope,
    sectionScope: AutoSettingsSectionScope,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    AutoSettingsSwitchItems(
        repository = repository,
        scope = scope,
        section = sectionScope.key,
        highlightTargetId = highlightTargetId,
        highlightPulse = highlightPulse,
        onHighlightFinished = onHighlightFinished
    )
}

@Composable
fun AutoSettingsSwitchItems(
    repository: AutoSettingsRepository,
    scope: CoroutineScope,
    section: String,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val bindings = remember(section) {
        AutoSettingsSwitchBindings.inSection(section)
            .filterNot { it.setting.keyName in unavailableSettingKeys }
    }
    bindings.forEach { binding ->
        key(binding.setting.keyName) {
            val flow = remember(repository, binding) { binding.read(repository) }
            val checked by flow.collectAsState(initial = binding.defaultValue)
            val setting = binding.setting
            AutoSettingsSwitchItem(
                checked = checked,
                titleRes = setting.titleRes,
                fallbackTitle = setting.keyName,
                descriptionRes = setting.descriptionRes,
                iconPainter = autoSettingsIconPainter(setting.iconRes),
                icon = setting.icon,
                targetId = "setting:${setting.keyName}",
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished,
                onCheckedChange = { value ->
                    scope.launch { binding.write(repository, value) }
                }
            )
        }
    }
}

@Composable
private fun autoSettingsIconPainter(iconRes: Int): Painter? {
    return if (iconRes == 0) null else painterResource(iconRes)
}

private fun autoSettingsIconVector(icon: AutoSettingIcon): ImageVector? {
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
        AutoSettingIcon.FormatSize -> Icons.Outlined.FormatSize
        AutoSettingIcon.Home -> Icons.Outlined.Home
        AutoSettingIcon.Info -> Icons.Outlined.Info
        AutoSettingIcon.Keyboard -> Icons.Outlined.Keyboard
        AutoSettingIcon.Layers -> Icons.Outlined.Layers
        AutoSettingIcon.LibraryMusic -> Icons.Outlined.LibraryMusic
        AutoSettingIcon.Palette -> Icons.Outlined.Palette
        AutoSettingIcon.PlaylistPlay -> Icons.AutoMirrored.Outlined.PlaylistPlay
        AutoSettingIcon.Public -> Icons.Outlined.Public
        AutoSettingIcon.RecordVoiceOver -> Icons.Outlined.RecordVoiceOver
        AutoSettingIcon.Router -> Icons.Outlined.Router
        AutoSettingIcon.Settings -> Icons.Outlined.Settings
        AutoSettingIcon.Storage -> Icons.Outlined.Storage
        AutoSettingIcon.Subtitles -> Icons.Outlined.Subtitles
        AutoSettingIcon.Sync -> Icons.Outlined.Sync
        AutoSettingIcon.Tab -> Icons.Outlined.Tab
        AutoSettingIcon.Translate -> Icons.Outlined.Translate
        AutoSettingIcon.Tune -> Icons.Outlined.Tune
        AutoSettingIcon.Usb -> Icons.Outlined.Usb
        AutoSettingIcon.Wallpaper -> Icons.Outlined.Wallpaper
        AutoSettingIcon.ZoomInMap -> Icons.Outlined.ZoomInMap
    }
}

@Composable
private fun autoSettingsString(resId: Int): String? {
    return if (resId == 0) null else stringResource(resId)
}

@Composable
private fun AutoSettingsSwitchItem(
    checked: Boolean,
    titleRes: Int,
    fallbackTitle: String,
    descriptionRes: Int,
    iconPainter: Painter?,
    icon: AutoSettingIcon,
    targetId: String,
    highlightTargetId: String?,
    highlightPulse: Int,
    onHighlightFinished: (() -> Unit)?,
    onCheckedChange: (Boolean) -> Unit
) {
    val title = autoSettingsString(titleRes) ?: fallbackTitle
    ListItem(
        modifier = Modifier
            .settingsHighlightTarget(
                targetId = targetId,
                highlightTargetId = highlightTargetId,
                highlightPulse = highlightPulse,
                onHighlightFinished = onHighlightFinished
            )
            .settingsItemClickable { onCheckedChange(!checked) },
        leadingContent = {
            AutoSettingsIcon(
                painter = iconPainter,
                imageVector = autoSettingsIconVector(icon),
                contentDescription = title
            )
        },
        headlineContent = { Text(title) },
        supportingContent = if (descriptionRes == 0) null else {
            { Text(stringResource(descriptionRes)) }
        },
        trailingContent = {
            MiuixSettingsSwitch(checked = checked, onCheckedChange = onCheckedChange)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun AutoSettingsIcon(
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
