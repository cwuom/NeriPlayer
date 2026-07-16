package moe.ouom.neriplayer.ui.component.playback

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.viewmodel.ManualSearchState
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest

private const val PlaybackStartupSearchWaitTimeoutMs = 2_000L
private const val PlaybackStartupSettleDelayMs = 260L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMetadataSearchContent(
    viewModel: NowPlayingViewModel,
    song: SongItem,
    offlineMode: Boolean,
    enabled: Boolean,
    onSongSelected: (SongSearchInfo) -> Unit,
    onDone: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val songKey = song.stableKey()
    val searchSessionId = remember(songKey) { mutableStateOf<Long?>(null) }
    var isWaitingForPlayback by remember(songKey) { mutableStateOf(true) }
    val searchState by viewModel.manualSearchState.collectAsStateWithLifecycle()
    val autoShowKeyboard by AppContainer.settingsRepo.autoShowKeyboardFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val pendingMediaLoad = PlayerManager.pendingMediaLoadFlow.collectAsStateWithLifecycle()
    val playbackState = PlayerManager.playerPlaybackStateFlow.collectAsStateWithLifecycle()
    val playWhenReady = PlayerManager.playWhenReadyFlow.collectAsStateWithLifecycle()

    LaunchedEffect(songKey) {
        searchSessionId.value = viewModel.prepareForSearch(song.displayName())
        val shouldDeferSearch = {
            shouldDeferMetadataAutoSearch(
                pendingMediaLoad = pendingMediaLoad.value,
                playbackState = playbackState.value,
                playWhenReady = playWhenReady.value
            )
        }
        val deferredForPlayback = shouldDeferSearch()
        val waitStartedAtMs = SystemClock.elapsedRealtime()
        if (deferredForPlayback) {
            NPLogger.d(
                "SongMetadataSearch",
                "等待播放启动窗口稳定后自动搜索: song=${song.displayName()}"
            )
        }
        awaitPlaybackStartupSettle(shouldDeferSearch)
        isWaitingForPlayback = false
        if (deferredForPlayback) {
            NPLogger.d(
                "SongMetadataSearch",
                "自动搜索等待结束: waitedMs=${SystemClock.elapsedRealtime() - waitStartedAtMs}, " +
                    "playbackBusy=${shouldDeferSearch()}"
            )
        }
        viewModel.performSearch()
    }

    LaunchedEffect(songKey, autoShowKeyboard, enabled, isWaitingForPlayback) {
        if (autoShowKeyboard && enabled && !isWaitingForPlayback) {
            delay(220)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    DisposableEffect(songKey) {
        onDispose {
            searchSessionId.value?.let(viewModel::finishSearchSession)
        }
    }

    val canSearch = searchState.keyword.isNotBlank() &&
        enabled &&
        !isWaitingForPlayback &&
        !searchState.isLoading &&
        !searchState.isApplyingMetadata &&
        (searchState.selectedPlatform != MusicPlatform.CLOUD_MUSIC ||
            searchState.isCloudMusicAvailable)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MetadataSearchInput(
            state = searchState,
            enabled = enabled,
            canSearch = canSearch,
            focusRequester = focusRequester,
            onKeywordChange = viewModel::onKeywordChange,
            onSearch = viewModel::performSearch,
            onSearchSubmitted = {
                if (canSearch) {
                    viewModel.performSearch()
                }
                focusManager.clearFocus()
            }
        )
        MetadataSearchPlatformTabs(
            state = searchState,
            enabled = enabled && !isWaitingForPlayback,
            onSelectPlatform = viewModel::selectPlatform
        )
        MetadataSearchResults(
            state = searchState,
            offlineMode = offlineMode,
            enabled = enabled,
            isWaitingForPlayback = isWaitingForPlayback,
            onSongSelected = onSongSelected
        )
        HapticTextButton(
            onClick = onDone,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(stringResource(R.string.action_done))
        }
    }
}

@Composable
private fun MetadataSearchInput(
    state: ManualSearchState,
    enabled: Boolean,
    canSearch: Boolean,
    focusRequester: FocusRequester,
    onKeywordChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchSubmitted: () -> Unit
) {
    OutlinedTextField(
        value = state.keyword,
        onValueChange = onKeywordChange,
        enabled = enabled && !state.isApplyingMetadata,
        label = { Text(stringResource(R.string.search_keywords)) },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        trailingIcon = {
            HapticIconButton(
                onClick = onSearch,
                enabled = canSearch
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.cd_search)
                )
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearchSubmitted() })
    )

    if (
        state.selectedPlatform == MusicPlatform.CLOUD_MUSIC &&
        !state.isCloudMusicAvailable
    ) {
        Text(
            text = stringResource(R.string.netease_login_required_metadata),
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun MetadataSearchPlatformTabs(
    state: ManualSearchState,
    enabled: Boolean,
    onSelectPlatform: (MusicPlatform) -> Unit
) {
    PrimaryTabRow(
        selectedTabIndex = state.selectedPlatform.ordinal,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        MusicPlatform.entries.forEachIndexed { index, platform ->
            Tab(
                selected = state.selectedPlatform.ordinal == index,
                onClick = { onSelectPlatform(platform) },
                enabled = enabled && !state.isApplyingMetadata,
                text = { Text(musicPlatformLabel(platform)) }
            )
        }
    }
}

@Composable
private fun MetadataSearchResults(
    state: ManualSearchState,
    offlineMode: Boolean,
    enabled: Boolean,
    isWaitingForPlayback: Boolean,
    onSongSelected: (SongSearchInfo) -> Unit
) {
    val listState = rememberLazyListState()
    Box(Modifier.height(300.dp)) {
        when {
            isWaitingForPlayback || state.isLoading || state.isApplyingMetadata -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            state.searchResults.isNotEmpty() -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.bottomSheetScrollGuard {
                        !listState.canScrollBackward
                    }
                ) {
                    items(
                        items = state.searchResults,
                        key = { result -> "${result.source.name}:${result.id}" },
                        contentType = { "search_result" }
                    ) { result ->
                        MetadataSearchResultItem(
                            result = result,
                            offlineMode = offlineMode,
                            enabled = enabled && !state.isApplyingMetadata,
                            onClick = { onSongSelected(result) }
                        )
                    }
                }
            }
            else -> {
                Text(
                    text = state.error ?: stringResource(R.string.nowplaying_no_search_result),
                    modifier = Modifier.align(Alignment.Center),
                    color = if (state.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        LocalContentColor.current
                    }
                )
            }
        }
    }
}

@Composable
private fun MetadataSearchResultItem(
    result: SongSearchInfo,
    offlineMode: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    ListItem(
        headlineContent = {
            Text(
                text = result.songName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = result.singer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            AsyncImage(
                model = offlineCachedImageRequest(
                    context = context,
                    data = result.coverUrl?.replaceFirst("http://", "https://"),
                    offlineMode = offlineMode
                ),
                contentDescription = result.songName,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    )
}

private suspend fun awaitPlaybackStartupSettle(
    shouldDefer: () -> Boolean
) {
    withTimeoutOrNull(PlaybackStartupSearchWaitTimeoutMs) {
        do {
            snapshotFlow { shouldDefer() }.first { isBusy -> !isBusy }
            delay(PlaybackStartupSettleDelayMs)
        } while (shouldDefer())
    }
}

@Composable
private fun musicPlatformLabel(platform: MusicPlatform): String {
    return when (platform) {
        MusicPlatform.CLOUD_MUSIC -> stringResource(R.string.platform_netease_short)
        MusicPlatform.QQ_MUSIC -> stringResource(R.string.settings_qq_music)
    }
}
