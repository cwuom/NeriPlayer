package moe.ouom.neriplayer.ui.screen.server.artist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.ServerArtist
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.screen.artist.CreatorDetailAdaptiveLayout
import moe.ouom.neriplayer.ui.screen.artist.CreatorDetailProfileCard
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistModernDockedSearchField
import moe.ouom.neriplayer.ui.screen.playlist.rememberPlaylistSearchResults
import moe.ouom.neriplayer.ui.screen.server.ServerAlbumRow
import moe.ouom.neriplayer.ui.screen.server.ServerBrowseMessage
import moe.ouom.neriplayer.ui.viewmodel.server.ServerArtistDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerArtistDetailScreen(
    artist: ServerArtist,
    onBack: () -> Unit,
    onAlbumClick: (ServerAlbum) -> Unit,
    modifier: Modifier = Modifier,
    offlineMode: Boolean = false,
    vm: ServerArtistDetailViewModel = viewModel(key = serverArtistDetailKey(artist))
) {
    LaunchedEffect(artist) { vm.start(artist) }
    LaunchedEffect(vm) { vm.observeArtwork() }
    val state by vm.state.collectAsStateWithLifecycle()
    val visibleState = state.takeIf { it.artist?.profileId == artist.profileId && it.artist.id == artist.id }
    val displayedArtist = visibleState?.artist ?: artist
    val loading = visibleState?.loading ?: true
    val albums = visibleState?.albums.orEmpty()
    val location = serverArtistDetailKey(artist)
    var showSearch by rememberSaveable(location) { mutableStateOf(false) }
    val inputState = rememberServerArtistSearchInputState(location)
    val query = inputState.value.text
    val scrollState = rememberServerArtistListState(location)
    var previousInputText by remember(location) { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (previousInputText != query) scrollState.cancelPendingRestore()
        previousInputText = query
    }
    val focusRequester = remember(location) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val autoKeyboard by AppContainer.settingsRepo.autoShowKeyboardFlow.collectAsStateWithLifecycle(false)
    val displayedAlbums = rememberPlaylistSearchResults(query, albums, { listOf(it.name, it.artist) })
    fun back() {
        if (showSearch) {
            scrollState.cancelPendingRestore()
            showSearch = false; inputState.value = TextFieldValue(); focusManager.clearFocus(); keyboard?.hide()
        } else onBack()
    }
    BackHandler(showSearch, onBack = ::back)
    LaunchedEffect(showSearch, autoKeyboard) {
        if (showSearch && autoKeyboard) {
            withFrameNanos { }
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    Surface(modifier.fillMaxSize(), color = Color.Transparent) {
        Scaffold(containerColor = Color.Transparent, topBar = {
            TopAppBar(
                title = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    HapticIconButton(onClick = ::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(CoreCommonR.string.action_back))
                    }
                },
                actions = {
                    HapticIconButton(onClick = { if (showSearch) back() else showSearch = true }) {
                        Icon(Icons.Filled.Search, stringResource(CoreCommonR.string.server_search_artist_albums_hint))
                    }
                    HapticIconButton(onClick = vm::refresh, enabled = !loading) {
                        Icon(Icons.Outlined.Refresh, stringResource(CoreCommonR.string.server_refresh))
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }) { padding ->
            ServerArtistDetailContent(
                displayedArtist, displayedAlbums, scrollState, loading, visibleState?.error, showSearch, query,
                onQueryChange = { inputState.value = TextFieldValue(it, TextRange(it.length)) },
                focusRequester = focusRequester, onRetry = vm::retry,
                onAlbumClick = { album ->
                    focusManager.clearFocus(); keyboard?.hide(); onAlbumClick(album)
                }, offlineMode = offlineMode, modifier = Modifier.padding(padding), inputState = inputState
            )
        }
    }
}

@Composable
internal fun ServerArtistDetailContent(
    artist: ServerArtist,
    albums: List<ServerAlbum>,
    scrollState: ServerArtistListState,
    loading: Boolean,
    error: Int?,
    showSearch: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onRetry: () -> Unit,
    onAlbumClick: (ServerAlbum) -> Unit,
    offlineMode: Boolean,
    modifier: Modifier = Modifier,
    inputState: MutableState<TextFieldValue>? = null
) {
    val miniPlayerHeight = LocalMiniPlayerHeight.current
    val summary = pluralStringResource(CoreCommonR.plurals.server_artist_album_count, artist.albumCount, artist.albumCount)
    CreatorDetailAdaptiveLayout(
        tabletDevice = LocalConfiguration.current.smallestScreenWidthDp >= 600,
        miniPlayerHeight = miniPlayerHeight, modifier = modifier,
        profile = {
            CreatorDetailProfileCard(artist.name, artist.imageUrl, summary, offlineMode, tabletProfile = true,
                artwork = { ServerArtistArtwork(artist, offlineMode, it, sizePx = 512) })
        }
    ) { splitLayout ->
        val itemKeys = remember(splitLayout, loading, error, albums) {
            buildList {
                if (!splitLayout) add("artist-profile")
                if (loading) add("loading")
                if (error != null) add("error")
                albums.forEach { add("album:${it.id}") }
                if (!loading && error == null && albums.isEmpty()) add("empty")
            }
        }
        LaunchedEffect(scrollState, itemKeys) {
            // 冷恢复时先保留锚点，避免加载提示把保存的位置夹到列表开头
            if (albums.isNotEmpty()) scrollState.restorePosition(itemKeys)
        }
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(showSearch) {
                PlaylistModernDockedSearchField(query, onQueryChange,
                    stringResource(CoreCommonR.string.server_search_artist_albums_hint), focusRequester = focusRequester,
                    inputState = inputState)
            }
            androidx.compose.foundation.lazy.LazyColumn(
                state = scrollState.listState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("serverArtistAlbums"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp,
                    bottom = 12.dp + if (splitLayout) 0.dp else miniPlayerHeight),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!splitLayout) item(key = "artist-profile") {
                    CreatorDetailProfileCard(artist.name, artist.imageUrl, summary, offlineMode,
                        artwork = { ServerArtistArtwork(artist, offlineMode, it, sizePx = 256) })
                }
                if (loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { item(key = "error") { ServerBrowseMessage(stringResource(it), error = true, onRetry = onRetry) } }
                items(albums, key = { "album:${it.id}" }) { album ->
                    ServerAlbumRow(album, offlineMode = offlineMode) { onAlbumClick(album) }
                }
                if (!loading && error == null && albums.isEmpty()) item(key = "empty") {
                    ServerBrowseMessage(stringResource(CoreCommonR.string.server_no_results))
                }
            }
        }
    }
}

internal fun serverArtistDetailKey(artist: ServerArtist): String =
    "server-artist:${artist.profileId.length}:${artist.profileId}${artist.id}"

@Composable
internal fun rememberServerArtistSearchInputState(location: String): MutableState<TextFieldValue> =
    rememberSaveable(location, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
