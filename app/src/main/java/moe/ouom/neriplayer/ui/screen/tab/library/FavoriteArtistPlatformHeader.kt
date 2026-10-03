package moe.ouom.neriplayer.ui.screen.tab.library

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
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        AdvancedGlassSurface(
            role = AdvancedGlassRole.ScreenTopTab,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
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
                if (selected != FavoriteArtistPlatform.BILIBILI && !editMode) {
                    val importingHere = importState.loading && importState.source == selected.source
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
