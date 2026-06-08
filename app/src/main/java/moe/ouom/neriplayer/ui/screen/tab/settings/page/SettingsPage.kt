package moe.ouom.neriplayer.ui.screen.tab.settings.page

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Audiotrack
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
import androidx.compose.ui.graphics.vector.ImageVector
import moe.ouom.neriplayer.R

internal enum class SettingsPage(
    val titleRes: Int,
    val descriptionRes: Int,
    val icon: ImageVector
) {
    General(
        titleRes = R.string.settings_general,
        descriptionRes = R.string.settings_general_desc,
        icon = Icons.Outlined.Settings
    ),
    Theme(
        titleRes = R.string.settings_theme,
        descriptionRes = R.string.settings_theme_desc,
        icon = Icons.Outlined.Palette
    ),
    Accounts(
        titleRes = R.string.settings_login_platforms,
        descriptionRes = R.string.settings_accounts_desc,
        icon = Icons.Filled.AccountCircle
    ),
    Personalization(
        titleRes = R.string.settings_personalization,
        descriptionRes = R.string.settings_personalization_expand,
        icon = Icons.Outlined.Tune
    ),
    Motion(
        titleRes = R.string.settings_motion,
        descriptionRes = R.string.settings_motion_expand,
        icon = Icons.Outlined.Bolt
    ),
    Lyrics(
        titleRes = R.string.settings_lyrics_offset,
        descriptionRes = R.string.settings_lyrics_offset_expand,
        icon = Icons.Outlined.Subtitles
    ),
    Network(
        titleRes = R.string.settings_network,
        descriptionRes = R.string.settings_network_expand,
        icon = Icons.Outlined.Router
    ),
    Playback(
        titleRes = R.string.settings_playback,
        descriptionRes = R.string.settings_playback_expand,
        icon = Icons.AutoMirrored.Outlined.PlaylistPlay
    ),
    PlaybackSource(
        titleRes = R.string.settings_playback_source,
        descriptionRes = R.string.settings_playback_source_desc,
        icon = Icons.Outlined.LibraryMusic
    ),
    AudioQuality(
        titleRes = R.string.settings_audio_quality,
        descriptionRes = R.string.settings_audio_quality_expand,
        icon = Icons.Filled.Audiotrack
    ),
    Storage(
        titleRes = R.string.settings_storage_cache,
        descriptionRes = R.string.settings_storage_expand,
        icon = Icons.Outlined.Storage
    ),
    Downloads(
        titleRes = R.string.settings_download_management,
        descriptionRes = R.string.settings_download_expand,
        icon = Icons.Outlined.Download
    ),
    Backup(
        titleRes = R.string.settings_backup_restore,
        descriptionRes = R.string.settings_backup_expand,
        icon = Icons.Outlined.Sync
    ),
    ListenTogether(
        titleRes = R.string.listen_together_title,
        descriptionRes = R.string.settings_listen_together_expand,
        icon = Icons.Outlined.Cloud
    ),
    About(
        titleRes = R.string.nav_about,
        descriptionRes = R.string.settings_about_desc,
        icon = Icons.Outlined.Info
    )
}
