package moe.ouom.neriplayer.ui.screen.tab.settings.page

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import moe.ouom.neriplayer.ksp.annotations.AutoSettingIcon

internal fun AutoSettingIcon.toSettingsPageIcon(fallbackIcon: ImageVector): ImageVector {
    return when (this) {
        AutoSettingIcon.Audiotrack -> Icons.Filled.Audiotrack
        AutoSettingIcon.Analytics -> Icons.Outlined.Analytics
        AutoSettingIcon.Bolt -> Icons.Outlined.Bolt
        AutoSettingIcon.Download -> Icons.Outlined.Download
        AutoSettingIcon.Info -> Icons.Outlined.Info
        AutoSettingIcon.Palette -> Icons.Outlined.Palette
        AutoSettingIcon.PlaylistPlay -> Icons.AutoMirrored.Outlined.PlaylistPlay
        AutoSettingIcon.Router -> Icons.Outlined.Router
        AutoSettingIcon.Settings -> Icons.Outlined.Settings
        AutoSettingIcon.Storage -> Icons.Outlined.Storage
        AutoSettingIcon.Subtitles -> Icons.Outlined.Subtitles
        AutoSettingIcon.Sync -> Icons.Outlined.Sync
        AutoSettingIcon.Tune -> Icons.Outlined.Tune
        else -> fallbackIcon
    }
}
