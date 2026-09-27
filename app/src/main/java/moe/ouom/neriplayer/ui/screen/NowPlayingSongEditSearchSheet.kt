package moe.ouom.neriplayer.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.ui.component.overlay.DensityScaledModalBottomSheet as ModalBottomSheet
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.viewmodel.ManualSearchState
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditSongSearchResultsSheet(
    viewModel: NowPlayingViewModel,
    offlineMode: Boolean,
    onDismiss: () -> Unit,
    onSelect: (SongSearchInfo) -> Unit
) {
    val searchState by viewModel.manualSearchState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EditSongSearchHeader(onDismiss)
            EditSongSearchInput(
                state = searchState,
                onKeywordChange = viewModel::onKeywordChange,
                onSearch = {
                    if (canSearchEditSong(searchState)) viewModel.performSearch()
                    focusManager.clearFocus()
                }
            )
            EditSongSearchPlatforms(
                selected = searchState.selectedPlatform,
                onSelect = viewModel::selectPlatform
            )
            EditSongSearchResults(searchState, offlineMode, Modifier.weight(1f), onSelect)
        }
    }
}

internal fun canSearchEditSong(state: ManualSearchState): Boolean =
    state.selectedPlatform != MusicPlatform.CLOUD_MUSIC || state.isCloudMusicAvailable

@Composable
private fun EditSongSearchHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.music_select_result), style = MaterialTheme.typography.titleMedium)
        HapticTextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun EditSongSearchInput(
    state: ManualSearchState,
    onKeywordChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    val searchEnabled = canSearchEditSong(state)
    OutlinedTextField(
        value = state.keyword,
        onValueChange = onKeywordChange,
        label = { Text(stringResource(R.string.music_auto_fill_custom_title)) },
        placeholder = { Text(stringResource(R.string.music_auto_fill_custom_title_hint)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        trailingIcon = {
            HapticIconButton(onClick = onSearch, enabled = searchEnabled) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.cd_search))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() })
    )
    EditSongSearchAvailabilityMessage(searchEnabled)
}

@Composable
private fun EditSongSearchAvailabilityMessage(searchEnabled: Boolean) {
    if (searchEnabled) return
    Text(
        text = stringResource(R.string.netease_login_required_metadata),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall
    )
}

internal fun musicPlatformLabelResource(platform: MusicPlatform): Int = when (platform) {
    MusicPlatform.CLOUD_MUSIC -> R.string.platform_netease_short
    MusicPlatform.QQ_MUSIC -> R.string.settings_qq_music
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSongSearchPlatforms(
    selected: MusicPlatform,
    onSelect: (MusicPlatform) -> Unit
) {
    androidx.compose.material3.PrimaryTabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        MusicPlatform.entries.forEachIndexed { index, platform ->
            Tab(
                selected = selected.ordinal == index,
                onClick = { onSelect(platform) },
                text = { Text(stringResource(musicPlatformLabelResource(platform))) }
            )
        }
    }
}

@Composable
private fun EditSongSearchResults(
    state: ManualSearchState,
    offlineMode: Boolean,
    modifier: Modifier,
    onSelect: (SongSearchInfo) -> Unit
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when {
            state.isLoading -> CircularProgressIndicator()
            state.searchResults.isNotEmpty() -> LazyColumn(
                modifier = Modifier.bottomSheetScrollGuard(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = state.searchResults,
                    key = { result -> "${result.source.name}:${result.id}" },
                    contentType = { "search_result" }
                ) { result ->
                    EditSongSearchResultCard(result, offlineMode) { onSelect(result) }
                }
            }
            else -> Text(
                text = state.error ?: stringResource(R.string.nowplaying_no_search_result),
                color = if (state.error != null) MaterialTheme.colorScheme.error else LocalContentColor.current
            )
        }
    }
}

@Composable
private fun EditSongSearchResultCard(
    result: SongSearchInfo,
    offlineMode: Boolean,
    onSelect: () -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.38f)
        )
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(result.songName, maxLines = 1) },
            supportingContent = { Text(result.singer, maxLines = 1) },
            leadingContent = {
                AsyncImage(
                    model = offlineCachedImageRequest(
                        context = context,
                        data = result.coverUrl?.replaceFirst("http://", "https://"),
                        offlineMode = offlineMode
                    ),
                    contentDescription = result.songName,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                )
            },
            modifier = Modifier.clickable(onClick = onSelect)
        )
    }
}
