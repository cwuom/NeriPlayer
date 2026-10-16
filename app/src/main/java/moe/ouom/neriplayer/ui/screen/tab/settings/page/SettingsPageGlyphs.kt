package moe.ouom.neriplayer.ui.screen.tab.settings.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.SpeakerNotes
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.theme.ContinuousRoundedCornerShape

/** Clean-style section glyph: a rounded white symbol on its own hue, as in system settings. */
internal data class SettingsPageGlyph(val icon: ImageVector, val tint: Color)

// hues stay in one saturation band so the list reads as a set rather than a rainbow
private val Indigo = Color(0xFF5E5CE6)
private val Graphite = Color(0xFF8E8E93)
private val Blue = Color(0xFF0A84FF)
private val Red = Color(0xFFFF453A)
private val Orange = Color(0xFFFF9F0A)
private val Pink = Color(0xFFFF375F)
private val Teal = Color(0xFF30B0C7)
private val Green = Color(0xFF30D158)
private val Purple = Color(0xFFBF5AF2)
private val Cyan = Color(0xFF40C8E0)
private val Violet = Color(0xFF7D7AFF)
private val Mint = Color(0xFF00C7BE)
private val Slate = Color(0xFF636366)

internal fun SettingsPage.cleanGlyph(): SettingsPageGlyph = when (this) {
    SettingsPage.Accounts -> SettingsPageGlyph(Icons.Rounded.AccountCircle, Indigo)
    SettingsPage.General -> SettingsPageGlyph(Icons.Rounded.Settings, Graphite)
    SettingsPage.Language -> SettingsPageGlyph(Icons.Rounded.Translate, Blue)
    SettingsPage.Playback -> SettingsPageGlyph(Icons.Rounded.PlayCircle, Red)
    SettingsPage.AudioQuality -> SettingsPageGlyph(Icons.Rounded.HighQuality, Orange)
    SettingsPage.AudioEffects -> SettingsPageGlyph(Icons.Rounded.GraphicEq, Pink)
    SettingsPage.PlaybackSource -> SettingsPageGlyph(Icons.AutoMirrored.Rounded.AltRoute, Teal)
    SettingsPage.UsbExclusive -> SettingsPageGlyph(Icons.Rounded.Usb, Green)
    SettingsPage.Theme -> SettingsPageGlyph(Icons.Rounded.Palette, Purple)
    SettingsPage.NowPlaying -> SettingsPageGlyph(Icons.Rounded.Album, Pink)
    SettingsPage.Background -> SettingsPageGlyph(Icons.Rounded.Wallpaper, Cyan)
    SettingsPage.Personalization -> SettingsPageGlyph(Icons.Rounded.Home, Blue)
    SettingsPage.Motion -> SettingsPageGlyph(Icons.Rounded.AutoAwesome, Orange)
    SettingsPage.Lyrics -> SettingsPageGlyph(Icons.Rounded.Lyrics, Violet)
    SettingsPage.ExternalLyrics -> SettingsPageGlyph(Icons.AutoMirrored.Rounded.SpeakerNotes, Mint)
    SettingsPage.XiaomiSuperIsland -> SettingsPageGlyph(Icons.Rounded.Notifications, Orange)
    SettingsPage.Downloads -> SettingsPageGlyph(Icons.Rounded.Download, Green)
    SettingsPage.Storage,
    SettingsPage.StorageCacheDetails -> SettingsPageGlyph(Icons.Rounded.Storage, Slate)
    SettingsPage.Network -> SettingsPageGlyph(Icons.Rounded.Wifi, Blue)
    SettingsPage.TrafficManagement -> SettingsPageGlyph(Icons.Rounded.DataUsage, Green)
    SettingsPage.Backup -> SettingsPageGlyph(Icons.Rounded.CloudSync, Blue)
    SettingsPage.ListenTogether -> SettingsPageGlyph(Icons.Rounded.Headphones, Pink)
    SettingsPage.About -> SettingsPageGlyph(Icons.Rounded.Info, Graphite)
}

@Composable
internal fun SettingsPageGlyphTile(
    page: SettingsPage,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp
) {
    val glyph = page.cleanGlyph()
    Box(
        modifier = modifier
            .size(size)
            .clip(ContinuousRoundedCornerShape(size * 0.28f))
            .background(glyph.tint),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = glyph.icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.62f)
        )
    }
}
