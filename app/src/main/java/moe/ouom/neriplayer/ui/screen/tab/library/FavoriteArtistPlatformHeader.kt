package moe.ouom.neriplayer.ui.screen.tab.library

import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.component.common.CleanSegmentedTabs
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_BILI_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_NETEASE_ARTIST
import moe.ouom.neriplayer.data.playlist.favorite.FAVORITE_SOURCE_YOUTUBE_ARTIST
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassRole
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassSurface
import moe.ouom.neriplayer.ui.haptic.HapticOutlinedButton
import moe.ouom.neriplayer.ui.haptic.performHapticFeedback
import moe.ouom.neriplayer.ui.viewmodel.tab.FollowedArtistImportUiState

internal enum class FavoriteArtistPlatform(val source: String, val labelRes: Int) {
    NETEASE(FAVORITE_SOURCE_NETEASE_ARTIST, CoreCommonR.string.library_tab_netease),
    BILIBILI(FAVORITE_SOURCE_BILI_ARTIST, CoreCommonR.string.library_artist_platform_bili),
    YOUTUBE(FAVORITE_SOURCE_YOUTUBE_ARTIST, CoreCommonR.string.library_artist_platform_youtube)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FavoriteArtistPlatformHeader(
    selected: FavoriteArtistPlatform,
    count: Int,
    onSelect: (FavoriteArtistPlatform) -> Unit,
    importState: FollowedArtistImportUiState,
    onImport: () -> Unit,
    offlineMode: Boolean,
    editMode: Boolean
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        shape = neriRoundedShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        AdvancedGlassSurface(
            role = AdvancedGlassRole.ScreenTopTab,
            modifier = Modifier.fillMaxWidth(),
            shape = neriRoundedShape(24.dp),
            fallbackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
            tintColor = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(CoreCommonR.string.library_artist_following),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        pluralStringResource(CoreCommonR.plurals.library_artist_count, count, count),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (NeriDesign.isClean) {
                    val platforms = FavoriteArtistPlatform.entries
                    CleanSegmentedTabs(
                        labels = platforms.map { stringResource(it.labelRes) },
                        selectedIndex = platforms.indexOf(selected),
                        onSelected = { index -> onSelect(platforms[index]) }
                    )
                } else {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        FavoriteArtistPlatform.entries.forEachIndexed { index, platform ->
                            SegmentedButton(
                                selected = selected == platform,
                                onClick = {
                                    context.performHapticFeedback()
                                    onSelect(platform)
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index, FavoriteArtistPlatform.entries.size
                                ),
                                label = { Text(stringResource(platform.labelRes), maxLines = 1) }
                            )
                        }
                    }
                }
                if (selected != FavoriteArtistPlatform.BILIBILI && !editMode) {
                    val importingHere = importState.loading && importState.source == selected.source
                    val importLabel = stringResource(
                        if (importingHere) CoreCommonR.string.library_artist_import_loading
                        else CoreCommonR.string.library_artist_import
                    )
                    if (NeriDesign.isClean) {
                        CleanImportPill(
                            label = importLabel,
                            loading = importingHere,
                            enabled = !offlineMode && !importState.loading,
                            onClick = onImport
                        )
                    } else {
                        HapticOutlinedButton(
                            onClick = onImport,
                            enabled = !offlineMode && !importState.loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (importingHere) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(
                                if (importingHere) CoreCommonR.string.library_artist_import_loading
                                else CoreCommonR.string.library_artist_import
                            ))
                        }
                    }
                }
            }
        }
    }
}

/** Borderless tonal pill; the outlined Material button reads as a hard frame inside a glass card. */
@Composable
private fun CleanImportPill(
    label: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val contentColor = if (enabled || loading) colors.primary else colors.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(colors.primary.copy(alpha = if (enabled) 0.12f else 0.06f))
            .clickable(enabled = enabled) {
                context.performHapticFeedback()
                onClick()
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedContent(targetState = loading, label = "clean_import_icon") { busy ->
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            } else {
                Icon(
                    Icons.Outlined.CloudDownload,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = contentColor)
    }
}
