package moe.ouom.neriplayer.ui.component.playback

import moe.ouom.neriplayer.ui.i18n.asString

import moe.ouom.neriplayer.data.identity.stableKey

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.metadata.SongMetadataApplyOptions
import moe.ouom.neriplayer.data.model.settings.lyrics.LyricSourcePreference
import moe.ouom.neriplayer.data.local.media.isLocalSong
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.component.sheet.bottomSheetScrollGuard
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.haptic.HapticTextButton
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriMotion
import moe.ouom.neriplayer.ui.theme.neriRoundedShape
import moe.ouom.neriplayer.ui.theme.neriSearchFieldColors
import moe.ouom.neriplayer.ui.theme.neriSearchFieldShape
import moe.ouom.neriplayer.ui.viewmodel.ManualSearchState
import moe.ouom.neriplayer.ui.viewmodel.ManualMetadataApplicationState
import moe.ouom.neriplayer.ui.viewmodel.NowPlayingViewModel
import moe.ouom.neriplayer.ui.screen.playlist.curation.rememberLocalCurationWriteAccessRequester
import moe.ouom.neriplayer.util.media.offlineCachedImageRequest
import kotlin.time.Duration.Companion.milliseconds
import moe.ouom.neriplayer.R as AppR

private const val PlaybackStartupSearchWaitTimeoutMs = 2_000L
private const val PlaybackStartupSettleDelayMs = 260L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMetadataSearchContent(
    viewModel: NowPlayingViewModel,
    song: SongItem,
    offlineMode: Boolean,
    enabled: Boolean,
    onSongSelected: (SongSearchInfo, SongMetadataApplyOptions) -> Unit,
    onBusyChanged: (Boolean) -> Unit,
    onApplied: () -> Unit,
    onDone: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val songKey = song.stableKey()
    val searchSessionId = remember(songKey) { mutableStateOf<Long?>(null) }
    var isWaitingForPlayback by remember(songKey) { mutableStateOf(true) }
    val searchState by viewModel.manualSearchState.collectAsStateWithLifecycle()
    val application by viewModel.metadataApplicationState.collectAsStateWithLifecycle()
    var selectedForApplication by remember(songKey) { mutableStateOf<SongSearchInfo?>(null) }
    var pendingWriteApplication by remember(songKey) {
        mutableStateOf<Pair<SongSearchInfo, SongMetadataApplyOptions>?>(null)
    }
    var writeAccessDenied by remember(songKey) { mutableStateOf(false) }
    val preferredLyricSource by AppContainer.settingsRepo.defaultLyricSourceFlow
        .collectAsStateWithLifecycle(initialValue = LyricSourcePreference.Automatic)
    val preferWordTimed by AppContainer.settingsRepo.preferWordTimedLyricsFlow
        .collectAsStateWithLifecycle(initialValue = true)
    val autoShowKeyboard by AppContainer.settingsRepo.autoShowKeyboardFlow
        .collectAsStateWithLifecycle(initialValue = false)
    val pendingMediaLoad = PlayerManager.pendingMediaLoadFlow.collectAsStateWithLifecycle()
    val playbackState = PlayerManager.playerPlaybackStateFlow.collectAsStateWithLifecycle()
    val playWhenReady = PlayerManager.playWhenReadyFlow.collectAsStateWithLifecycle()
    val requestWriteAccess = rememberLocalCurationWriteAccessRequester(
        songs = { if (pendingWriteApplication != null) listOf(song) else emptyList() },
        onGranted = {
            pendingWriteApplication?.let { (selected, options) -> onSongSelected(selected, options) }
            pendingWriteApplication = null
            onBusyChanged(viewModel.metadataApplicationState.value.isRunning)
        },
        onDenied = {
            pendingWriteApplication = null
            writeAccessDenied = true
            onBusyChanged(false)
        }
    )
    SideEffect { onBusyChanged(application.isRunning || pendingWriteApplication != null) }

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

    val canFocusSearch = enabled && !isWaitingForPlayback && selectedForApplication == null &&
        pendingWriteApplication == null && !application.isRunning && !application.completed
    LaunchedEffect(songKey, autoShowKeyboard, canFocusSearch) {
        if (autoShowKeyboard && canFocusSearch) {
            delay(220.milliseconds)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    DisposableEffect(songKey) {
        onDispose {
            pendingWriteApplication = null
            searchSessionId.value?.let(viewModel::finishSearchSession)
        }
    }

    val canSearch = searchState.keyword.isNotBlank() &&
        enabled &&
        !isWaitingForPlayback &&
        !searchState.isLoading &&
        !searchState.isApplyingMetadata &&
        !application.completed &&
        pendingWriteApplication == null &&
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
            enabled = enabled && pendingWriteApplication == null && !application.completed,
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
            enabled = enabled && !isWaitingForPlayback && pendingWriteApplication == null,
            onSelectPlatform = viewModel::selectPlatform
        )
        MetadataSearchResults(
            state = searchState,
            offlineMode = offlineMode,
            enabled = enabled && pendingWriteApplication == null,
            isWaitingForPlayback = isWaitingForPlayback || pendingWriteApplication != null,
            application = application,
            requestKey = songKey,
            onApplied = onApplied,
            onCancelApplication = { viewModel.cancelMetadataApplication() },
            onSongSelected = {
                focusManager.clearFocus()
                keyboardController?.hide()
                selectedForApplication = it
            }
        )
        if (application.failed || writeAccessDenied) {
            Text(
                stringResource(if (writeAccessDenied) {
                    AppR.string.local_curation_write_access_denied
                } else AppR.string.metadata_apply_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        HapticTextButton(
            onClick = onDone,
            enabled = enabled && !application.isRunning && !application.completed && pendingWriteApplication == null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(stringResource(CoreCommonR.string.action_done))
        }
    }
    selectedForApplication?.let { selected ->
        ManualMetadataApplyOptionsDialog(
            selectedSong = selected,
            isLocalSong = song.isLocalSong(),
            initialOptions = SongMetadataApplyOptions(
                lyricSources = defaultMetadataLyricSources(preferredLyricSource, selected.source),
                preferWordTimed = preferWordTimed
            ),
            onDismiss = { selectedForApplication = null },
            onApply = { options ->
                selectedForApplication = null
                focusManager.clearFocus()
                keyboardController?.hide()
                writeAccessDenied = false
                if (options.writeLocalMetadata) {
                    pendingWriteApplication = selected to options
                    onBusyChanged(true)
                    requestWriteAccess()
                } else {
                    onSongSelected(selected, options)
                }
            }
        )
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
        shape = neriSearchFieldShape(OutlinedTextFieldDefaults.shape),
        colors = neriSearchFieldColors(),
        label = { Text(stringResource(CoreCommonR.string.search_keywords)) },
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
                    contentDescription = stringResource(CoreCommonR.string.cd_search)
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
            text = stringResource(CoreCommonR.string.netease_login_required_metadata),
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
    if (NeriDesign.isClean) {
        Row(
            Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(NeriDesign.tokens.controlShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            MusicPlatform.entries.forEachIndexed { index, platform ->
                val selected = state.selectedPlatform.ordinal == index
                Tab(
                    selected = selected,
                    onClick = { onSelectPlatform(platform) },
                    enabled = enabled && !state.isApplyingMetadata,
                    modifier = Modifier.weight(1f).clip(NeriDesign.tokens.chipShape).background(
                        if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(musicPlatformLabel(platform)) }
                )
            }
        }
        return
    }
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
internal fun MetadataSearchResults(
    state: ManualSearchState,
    offlineMode: Boolean,
    enabled: Boolean,
    isWaitingForPlayback: Boolean,
    application: ManualMetadataApplicationState,
    onCancelApplication: () -> Unit,
    onSongSelected: (SongSearchInfo) -> Unit,
    requestKey: String = "metadata",
    onApplied: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val lastApplication = remember(requestKey) { mutableStateOf(application) }
    val lastResults = remember(requestKey) { mutableStateOf(state.searchResults) }
    val showingApplication = application.isRunning || application.completed
    val displayedApplication = if (showingApplication) application else lastApplication.value
    val displayedResults = state.searchResults.ifEmpty { lastResults.value }
    SideEffect {
        if (showingApplication) lastApplication.value = application
        if (!state.isLoading && state.searchResults.isNotEmpty()) lastResults.value = state.searchResults
    }
    val page = when {
        showingApplication -> MetadataResultsPage.Application
        isWaitingForPlayback || state.isLoading -> MetadataResultsPage.Loading
        state.searchResults.isNotEmpty() -> MetadataResultsPage.Results
        else -> MetadataResultsPage.Empty
    }
    Crossfade(
        targetState = page,
        modifier = Modifier.fillMaxWidth().height(300.dp).testTag("metadata-results-host"),
        animationSpec = tween(180, easing = NeriMotion.Emphasized),
        label = "metadata search content"
    ) { currentPage ->
        Box(Modifier.fillMaxSize()) {
            when (currentPage) {
                MetadataResultsPage.Application -> ManualMetadataApplicationProgress(
                    displayedApplication, onCancelApplication,
                    Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                    requestKey, onCompleted = { if (showingApplication && application.completed) onApplied() }
                )
                MetadataResultsPage.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                MetadataResultsPage.Results -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().testTag("metadata-search-results").bottomSheetScrollGuard {
                            !listState.canScrollBackward
                        }
                    ) {
                        items(
                            items = displayedResults,
                            key = { result -> "${result.source.name}:${result.id}" },
                            contentType = { "search_result" }
                        ) { result ->
                            MetadataSearchResultItem(
                                result = result,
                                offlineMode = offlineMode,
                                enabled = page == MetadataResultsPage.Results && enabled && !state.isApplyingMetadata,
                                onClick = { onSongSelected(result) }
                            )
                        }
                    }
                }
                MetadataResultsPage.Empty -> {
                    Text(
                        text = state.error?.asString() ?: stringResource(CoreCommonR.string.nowplaying_no_search_result),
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                        color = if (state.error != null) MaterialTheme.colorScheme.error else LocalContentColor.current
                    )
                }
            }
        }
    }
}

private enum class MetadataResultsPage { Application, Loading, Results, Empty }

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
                    .clip(neriRoundedShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    )
}

private suspend fun awaitPlaybackStartupSettle(
    shouldDefer: () -> Boolean
) {
    withTimeoutOrNull(PlaybackStartupSearchWaitTimeoutMs.milliseconds) {
        do {
            snapshotFlow { shouldDefer() }.first { isBusy -> !isBusy }
            delay(PlaybackStartupSettleDelayMs.milliseconds)
        } while (shouldDefer())
    }
}

@Composable
private fun musicPlatformLabel(platform: MusicPlatform): String {
    return when (platform) {
        MusicPlatform.CLOUD_MUSIC -> stringResource(AppR.string.platform_netease_short)
        MusicPlatform.QQ_MUSIC -> stringResource(CoreCommonR.string.settings_qq_music)
    }
}
