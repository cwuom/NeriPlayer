package moe.ouom.neriplayer.ui.screen.tab.settings.page

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.ui.graphics.vector.ImageVector
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.ksp.annotations.AutoSettingsSectionEntry

internal enum class SettingsPage(
    val titleRes: Int,
    val descriptionRes: Int,
    val icon: ImageVector
) {
    General(
        AutoSettingsSchema.general.metadata,
        Icons.Outlined.Settings
    ),
    Theme(
        AutoSettingsSchema.theme.metadata,
        Icons.Outlined.Palette
    ),
    Accounts(
        titleRes = R.string.settings_login_platforms,
        descriptionRes = R.string.settings_accounts_desc,
        icon = Icons.Filled.AccountCircle
    ),
    Personalization(
        AutoSettingsSchema.personalization.metadata,
        Icons.Outlined.Tune
    ),
    Motion(
        AutoSettingsSchema.motion.metadata,
        Icons.Outlined.Bolt
    ),
    Lyrics(
        AutoSettingsSchema.lyrics.metadata,
        Icons.Outlined.Subtitles
    ),
    Network(
        AutoSettingsSchema.network.metadata,
        Icons.Outlined.Router
    ),
    Playback(
        AutoSettingsSchema.playback.metadata,
        Icons.AutoMirrored.Outlined.PlaylistPlay
    ),
    UsbExclusive(
        titleRes = R.string.settings_usb_exclusive_playback,
        descriptionRes = R.string.settings_usb_exclusive_page_desc,
        icon = Icons.Outlined.Usb
    ),
    PlaybackSource(
        titleRes = R.string.settings_playback_source,
        descriptionRes = R.string.settings_playback_source_desc,
        icon = Icons.Outlined.LibraryMusic
    ),
    AudioQuality(
        AutoSettingsSchema.audioQuality.metadata,
        Icons.Filled.Audiotrack
    ),
    Storage(
        AutoSettingsSchema.storage.metadata,
        Icons.Outlined.Storage
    ),
    TrafficManagement(
        AutoSettingsSchema.trafficManagement.metadata,
        Icons.Outlined.Analytics
    ),
    Downloads(
        AutoSettingsSchema.download.metadata,
        Icons.Outlined.Download
    ),
    Backup(
        AutoSettingsSchema.backup.metadata,
        Icons.Outlined.Sync
    ),
    ListenTogether(
        titleRes = R.string.listen_together_title,
        descriptionRes = R.string.settings_listen_together_expand,
        icon = Icons.Outlined.Cloud
    ),
    About(
        titleRes = R.string.settings_about,
        descriptionRes = R.string.settings_about_desc,
        icon = Icons.Outlined.Info
    );

    constructor(section: AutoSettingsSectionEntry, fallbackIcon: ImageVector) : this(
        titleRes = section.titleRes,
        descriptionRes = section.descriptionRes,
        icon = section.icon.toSettingsPageIcon(fallbackIcon)
    )
}

internal val SettingsHomePageGroups: List<List<SettingsPage>> = listOf(
    listOf(SettingsPage.Accounts),
    listOf(
        SettingsPage.Playback,
        SettingsPage.PlaybackSource,
        SettingsPage.AudioQuality,
        SettingsPage.Downloads,
        SettingsPage.Storage,
        SettingsPage.TrafficManagement
    ),
    listOf(
        SettingsPage.General,
        SettingsPage.Theme,
        SettingsPage.Personalization,
        SettingsPage.Motion,
        SettingsPage.Lyrics,
        SettingsPage.Network
    ),
    listOf(
        SettingsPage.Backup,
        SettingsPage.ListenTogether,
        SettingsPage.About
    )
)
