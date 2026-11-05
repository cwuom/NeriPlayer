package moe.ouom.neriplayer.ui.screen.tab.settings.page

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.GraphicEq
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.ksp.annotations.AutoSettingsSectionEntry
import moe.ouom.neriplayer.R as AppR

internal enum class SettingsPage(
    val titleRes: Int,
    val descriptionRes: Int,
    val icon: ImageVector
) {
    General(
        AutoSettingsSchema.general.metadata,
        Icons.Outlined.Settings
    ),
    Language(
        titleRes = CoreCommonR.string.settings_page_language,
        descriptionRes = CoreCommonR.string.settings_page_language_desc,
        icon = Icons.Outlined.Translate
    ),
    AudioEffects(
        titleRes = AppR.string.settings_audio_effects_title,
        descriptionRes = AppR.string.settings_audio_effects_desc,
        icon = Icons.Outlined.GraphicEq
    ),
    Theme(
        AutoSettingsSchema.theme.metadata,
        Icons.Outlined.Palette
    ),
    Accounts(
        titleRes = CoreCommonR.string.settings_login_platforms,
        descriptionRes = CoreCommonR.string.settings_accounts_desc,
        icon = Icons.Filled.AccountCircle
    ),
    Personalization(
        titleRes = CoreCommonR.string.settings_page_home_start,
        descriptionRes = CoreCommonR.string.settings_page_home_start_desc,
        icon = Icons.Outlined.Tune
    ),
    NowPlaying(
        titleRes = CoreCommonR.string.settings_page_now_playing,
        descriptionRes = CoreCommonR.string.settings_page_now_playing_desc,
        icon = Icons.Outlined.Album
    ),
    Background(
        titleRes = CoreCommonR.string.settings_page_background,
        descriptionRes = CoreCommonR.string.settings_page_background_desc,
        icon = Icons.Outlined.Wallpaper
    ),
    Motion(
        AutoSettingsSchema.motion.metadata,
        Icons.Outlined.Bolt
    ),
    Lyrics(
        titleRes = CoreCommonR.string.settings_page_lyrics,
        descriptionRes = CoreCommonR.string.settings_page_lyrics_desc,
        icon = Icons.Outlined.Lyrics
    ),
    ExternalLyrics(
        titleRes = CoreCommonR.string.settings_page_external_lyrics,
        descriptionRes = CoreCommonR.string.settings_page_external_lyrics_desc,
        icon = Icons.Outlined.Subtitles
    ),
    XiaomiSuperIsland(
        titleRes = AutoSettingsSchema.lyrics.xiaomiSuperIslandLyricEnabled.titleRes,
        descriptionRes = AutoSettingsSchema.lyrics.xiaomiSuperIslandLyricEnabled.descriptionRes,
        icon = Icons.Outlined.Subtitles
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
        titleRes = CoreCommonR.string.settings_usb_exclusive_playback,
        descriptionRes = CoreCommonR.string.settings_usb_exclusive_page_desc,
        icon = Icons.Outlined.Usb
    ),
    PlaybackSource(
        titleRes = CoreCommonR.string.settings_playback_source,
        descriptionRes = CoreCommonR.string.settings_playback_source_desc,
        icon = Icons.Outlined.LibraryMusic
    ),
    MusicServers(
        titleRes = CoreCommonR.string.server_manage,
        descriptionRes = CoreCommonR.string.server_settings_desc,
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
    StorageCacheDetails(
        titleRes = CoreCommonR.string.settings_storage_cache_details,
        descriptionRes = CoreCommonR.string.settings_storage_cache_details_desc,
        icon = Icons.Outlined.Storage
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
        titleRes = CoreCommonR.string.listen_together_title,
        descriptionRes = CoreCommonR.string.settings_listen_together_expand,
        icon = Icons.Outlined.Cloud
    ),
    About(
        titleRes = CoreCommonR.string.settings_about,
        descriptionRes = CoreCommonR.string.settings_about_desc,
        icon = Icons.Outlined.Info
    );

    constructor(section: AutoSettingsSectionEntry, fallbackIcon: ImageVector) : this(
        titleRes = section.titleRes,
        descriptionRes = section.descriptionRes,
        icon = section.icon.toSettingsPageIcon(fallbackIcon)
    )
}

/** Mirrors the desktop settings layout: one group per concern, nothing hidden behind another page. */
/** Lets nested settings rows open a second-level page without threading callbacks through. */
internal val LocalSettingsPageNavigator = staticCompositionLocalOf<((SettingsPage) -> Unit)?> { null }

internal val SettingsHomePageGroups: List<List<SettingsPage>> = listOf(
    listOf(SettingsPage.Accounts),
    listOf(
        SettingsPage.General,
        SettingsPage.Language
    ),
    listOf(
        SettingsPage.Playback,
        SettingsPage.AudioQuality,
        SettingsPage.AudioEffects,
        SettingsPage.PlaybackSource,
        SettingsPage.UsbExclusive
    ),
    listOf(
        SettingsPage.Theme,
        SettingsPage.NowPlaying,
        SettingsPage.Background,
        SettingsPage.Personalization,
        SettingsPage.Motion
    ),
    listOf(
        SettingsPage.Lyrics,
        SettingsPage.ExternalLyrics
    ),
    listOf(
        SettingsPage.Downloads,
        SettingsPage.Storage,
        SettingsPage.Network,
        SettingsPage.TrafficManagement
    ),
    listOf(
        SettingsPage.Backup,
        SettingsPage.ListenTogether
    ),
    listOf(SettingsPage.About)
)

/** Card indices of the shared lyrics section rendered on each lyrics page, in display order. */
internal val LyricsPageCards = listOf(4, 1, 3)
internal val ExternalLyricsPageCards = listOf(0, 2)

internal fun SettingsPage.backTargetPage(): SettingsPage? = when (this) {
    SettingsPage.MusicServers -> SettingsPage.Accounts
    SettingsPage.StorageCacheDetails -> SettingsPage.Storage
    SettingsPage.XiaomiSuperIsland -> SettingsPage.ExternalLyrics
    else -> null
}
