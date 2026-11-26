package moe.ouom.neriplayer.ui.screen.playlist.detail

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.ui.feedback.NeriSnackbarHost
import moe.ouom.neriplayer.ui.haptic.HapticFloatingActionButton
import moe.ouom.neriplayer.ui.haptic.HapticIconButton
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.navigation.scrollToTopForReselect
import moe.ouom.neriplayer.ui.navigation.scrollToTopOnTitleBarTap
import moe.ouom.neriplayer.ui.screen.playlist.LOCAL_PLAYLIST_ACTIONS_KEY
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistHeroMeta
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistModernActionSheet
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistModernDockedSearchSlotHeight
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistModernHeroHeader
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistModernHeroHeight
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistModernHeroSearchField
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistModernHeroSearchHeight
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistModernStableSearchField
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistModernVisualColorsProvider
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistTwoPaneBody
import moe.ouom.neriplayer.ui.screen.playlist.PlaylistTwoPaneListTopGap
import moe.ouom.neriplayer.ui.screen.playlist.LocalPlaylistTwoPaneAllowed
import moe.ouom.neriplayer.ui.screen.playlist.isPlaylistTwoPaneWindow
import moe.ouom.neriplayer.ui.screen.playlist.interpolatePlaylistColor
import moe.ouom.neriplayer.ui.screen.playlist.interpolatePlaylistDp
import moe.ouom.neriplayer.ui.screen.playlist.playlistModernCollapsedTopBarContentColor
import moe.ouom.neriplayer.ui.screen.playlist.playlistModernDockedSearchGlassColor
import moe.ouom.neriplayer.ui.screen.playlist.playlistModernSearchVisibilityProgress
import moe.ouom.neriplayer.ui.screen.playlist.rememberPlaylistModernHeroBackgroundColor
import moe.ouom.neriplayer.ui.screen.playlist.resolvePlaylistChromeCollapseProgress
import moe.ouom.neriplayer.ui.screen.playlist.resolvePlaylistDockedSearchRevealProgress
import moe.ouom.neriplayer.ui.screen.playlist.resolvePlaylistDockedSearchSlotProgress
import moe.ouom.neriplayer.ui.screen.playlist.resolvePlaylistHeaderSearchAlpha
import moe.ouom.neriplayer.ui.screen.playlist.resolvePlaylistSelectionTopBarColor
import moe.ouom.neriplayer.ui.screen.playlist.resolvePlaylistSelectionTopBarContentColor
import moe.ouom.neriplayer.ui.screen.playlist.resolvePlaylistSolidTopBarContentColor
import moe.ouom.neriplayer.ui.screen.playlist.resolvePlaylistTranslucentTopBarColor
import moe.ouom.neriplayer.ui.screen.playlist.shouldComposePlaylistSearchSlot
import moe.ouom.neriplayer.ui.screen.playlist.shouldRequestPlaylistSearchFocus
import moe.ouom.neriplayer.ui.screen.playlist.shouldShowPlaylistSearch
import moe.ouom.neriplayer.ui.screen.playlist.shouldTransferPlaylistSearchFocus
import kotlin.time.Duration.Companion.milliseconds
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.R as AppR

internal data class PlaylistModernDetailHeader(
    val key: Any,
    val headerKey: String,
    val title: String,
    val coverUrl: String?,
    val subtitle: String,
    val offlineMode: Boolean,
    val hasCustomBackground: Boolean,
    val metaItems: List<PlaylistHeroMeta>? = null
)

internal class PlaylistModernDetailSearch(
    val visible: Boolean,
    val onVisibleChange: (Boolean) -> Unit,
    val query: String,
    val onQueryChange: (String) -> Unit,
    val inputState: MutableState<TextFieldValue>,
    val focusRequester: FocusRequester,
    val autoShowKeyboard: Boolean,
    val headerFocused: Boolean,
    val onHeaderFocusChanged: (Boolean) -> Unit,
    val dockedFocused: Boolean,
    val onDockedFocusChanged: (Boolean) -> Unit
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PlaylistModernDetailFrame(
    header: PlaylistModernDetailHeader,
    search: PlaylistModernDetailSearch,
    listState: LazyListState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    selectionCount: Int?,
    onExitSelection: () -> Unit,
    onLocatePlaying: (() -> Unit)?,
    searchPlaceholder: String? = null,
    modifier: Modifier = Modifier,
    listModifier: Modifier = Modifier,
    normalActions: @Composable RowScope.(Color) -> Unit = {},
    selectionActions: @Composable RowScope.() -> Unit = {},
    beforeList: @Composable ColumnScope.(Color) -> Unit = {},
    playbackActions: @Composable () -> Unit,
    content: LazyListScope.() -> Unit,
    overlays: @Composable () -> Unit = {}
) {
    val headerCover = header.coverUrl
    val offlineMode = header.offlineMode
    val showSearch = search.visible
    val searchQuery = search.query
    val searchInputState = search.inputState
    val searchFocusRequester = search.focusRequester
    val autoShowKeyboard = search.autoShowKeyboard
    val headerSearchFocused = search.headerFocused
    val dockedSearchFocused = search.dockedFocused
    val selectionMode = selectionCount != null
    val searchPlaceholderText = searchPlaceholder
        ?: stringResource(CoreCommonR.string.search_playlist)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // 按框架实际可用尺寸判断两栏, 分屏或嵌在小容器里时自动回到单列
    BoxWithConstraints(modifier) {
        val twoPane = LocalPlaylistTwoPaneAllowed.current &&
            isPlaylistTwoPaneWindow(maxWidth.value.toInt(), maxHeight.value.toInt())
        PlaylistModernVisualColorsProvider(
            coverUrl = headerCover,
            offlineMode = offlineMode
        ) {
            val playlistChromeColor = rememberPlaylistModernHeroBackgroundColor(
                coverUrl = headerCover,
                offlineMode = offlineMode
            )
            val density = LocalDensity.current
            val searchVisible = shouldShowPlaylistSearch(
                showSearch = showSearch,
                selectionMode = selectionMode
            )
            val searchVisibilityProgress = playlistModernSearchVisibilityProgress(
                searchVisible = searchVisible,
                label = "playlist-search-visibility"
            )
            val searchSlotProgress = searchVisibilityProgress.coerceIn(0f, 1f)
            val searchVisibilityEased = FastOutSlowInEasing.transform(searchSlotProgress)
            val searchDockedRevealProgress by remember(
                listState,
                density,
                twoPane
            ) {
                derivedStateOf {
                    if (twoPane) return@derivedStateOf 0f
                    resolvePlaylistDockedSearchRevealProgress(
                        firstVisibleItemIndex = listState.firstVisibleItemIndex,
                        firstVisibleItemScrollOffsetPx =
                            listState.firstVisibleItemScrollOffset,
                        revealDistancePx = with(density) {
                            PlaylistModernDockedSearchSlotHeight.roundToPx()
                        }
                    )
                }
            }
            val searchDockedVisualProgress = FastOutSlowInEasing.transform(
                searchDockedRevealProgress
            )
            val dockedSearchProgress = resolvePlaylistDockedSearchSlotProgress(
                searchVisibilityProgress = searchSlotProgress,
                dockedRevealProgress = searchDockedRevealProgress
            )
            val searchSlotVisible = shouldComposePlaylistSearchSlot(
                searchVisible = searchVisible,
                visibilityProgress = dockedSearchProgress
            )
            val searchSlotHeight = interpolatePlaylistDp(
                start = 0.dp,
                end = PlaylistModernDockedSearchSlotHeight,
                fraction = dockedSearchProgress
            )
            val searchSlotAlpha = FastOutSlowInEasing.transform(dockedSearchProgress)
            val playlistHeroHeight = interpolatePlaylistDp(
                start = PlaylistModernHeroHeight,
                end = PlaylistModernHeroSearchHeight,
                fraction = searchVisibilityEased
            )
            val playlistChromeCollapseProgress by remember(
                listState,
                density,
                playlistHeroHeight,
                twoPane
            ) {
                derivedStateOf {
                    if (twoPane) return@derivedStateOf 1f
                    resolvePlaylistChromeCollapseProgress(
                        firstVisibleItemIndex = listState.firstVisibleItemIndex,
                        firstVisibleItemScrollOffsetPx =
                            listState.firstVisibleItemScrollOffset,
                        expandedHeroHeightPx = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.index == 0 }?.size ?: with(density) {
                            playlistHeroHeight.roundToPx()
                        }
                    )
                }
            }
            val playlistChromeVisualProgress =
                FastOutSlowInEasing.transform(playlistChromeCollapseProgress)
            val headerSearchAlpha = resolvePlaylistHeaderSearchAlpha(
                searchVisibilityProgress = searchSlotProgress,
                chromeCollapseProgress = if (twoPane) 0f else playlistChromeCollapseProgress
            )
            val headerSearchVisible = shouldComposePlaylistSearchSlot(
                searchVisible = searchVisible,
                visibilityProgress = headerSearchAlpha
            )
            val playlistTopBarColor = resolvePlaylistTranslucentTopBarColor(
                playlistColor = playlistChromeColor,
                collapseProgress = playlistChromeVisualProgress
            )
            val playlistTopBarContentColor = interpolatePlaylistColor(
                start = resolvePlaylistSolidTopBarContentColor(playlistChromeColor),
                end = playlistModernCollapsedTopBarContentColor(),
                fraction = playlistChromeVisualProgress
            )
            val playlistSelectionTopBarColor = resolvePlaylistSelectionTopBarColor(
                playlistColor = playlistChromeColor,
                collapseProgress = playlistChromeCollapseProgress
            )
            val playlistSelectionTopBarContentColor = resolvePlaylistSelectionTopBarContentColor(
                playlistColor = playlistChromeColor,
                collapsedContentColor = playlistModernCollapsedTopBarContentColor(),
                collapseProgress = playlistChromeCollapseProgress
            )
            val dockedSearchGlassColor = playlistModernDockedSearchGlassColor(
                playlistColor = playlistChromeColor
            )
            val searchFieldFocusInHeader =
                headerSearchVisible && searchDockedRevealProgress < 0.5f
            val searchFieldComposed = headerSearchVisible || searchSlotVisible
            LaunchedEffect(
                showSearch,
                selectionMode,
                autoShowKeyboard,
                searchFieldComposed,
                searchFieldFocusInHeader
            ) {
                if (!searchFieldComposed) return@LaunchedEffect
                val shouldAutoFocus = shouldRequestPlaylistSearchFocus(
                    showSearch,
                    selectionMode,
                    autoShowKeyboard
                )
                val shouldTransferFocus = shouldTransferPlaylistSearchFocus(
                    showSearch = showSearch,
                    selectionMode = selectionMode,
                    searchFieldComposed = searchFieldComposed,
                    searchInputFocused = headerSearchFocused || dockedSearchFocused,
                    searchQuery = searchQuery
                )
                if (!shouldAutoFocus && !shouldTransferFocus) return@LaunchedEffect
                if (shouldAutoFocus) delay(120.milliseconds)
                searchFocusRequester.requestFocus()
                keyboardController?.show()
            }
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
                snackbarHost = {
                    NeriSnackbarHost(
                        hostState = snackbarHostState,
                        bottomPadding = LocalMiniPlayerHeight.current
                    )
                },
                topBar = {
                    if (!selectionMode) {
                        TopAppBar(
                            title = {
                                Text(
                                    header.title,
                                    // 标题已在头部大字展示, 顶栏只在头部滚走后淡入, 避免同屏出现两遍
                                    modifier = Modifier.graphicsLayer {
                                        alpha = if (twoPane) 0f else playlistChromeVisualProgress
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            navigationIcon = {
                                HapticIconButton(onClick = onBack) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(CoreCommonR.string.action_back)
                                    )
                                }
                            },
                            actions = {
                                HapticIconButton(onClick = {
                                    val openingSearch = !showSearch
                                    if (!openingSearch) {
                                        search.onQueryChange("")
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                    }
                                    search.onVisibleChange(openingSearch)
                                }) {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = stringResource(CoreCommonR.string.cd_search_songs)
                                    )
                                }

                                normalActions(playlistTopBarContentColor)
                            },
                            modifier = Modifier.scrollToTopOnTitleBarTap {
                                listState.scrollToTopForReselect()
                            },
                            windowInsets = WindowInsets.statusBars,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = playlistTopBarColor,
                                scrolledContainerColor = playlistTopBarColor,
                                titleContentColor = playlistTopBarContentColor,
                                navigationIconContentColor = playlistTopBarContentColor,
                                actionIconContentColor = playlistTopBarContentColor
                            )
                        )
                    } else {
                        TopAppBar(
                            title = {
                                Text(
                                    pluralStringResource(
                                        AppR.plurals.common_selected_count,
                                        selectionCount,
                                        selectionCount
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            navigationIcon = {
                                HapticIconButton(onClick = onExitSelection) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(CoreCommonR.string.cd_exit_select)
                                    )
                                }
                            },
                            actions = {
                                selectionActions()
                            },
                            windowInsets = WindowInsets.statusBars,
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = playlistSelectionTopBarColor,
                                scrolledContainerColor = playlistSelectionTopBarColor,
                                titleContentColor = playlistSelectionTopBarContentColor,
                                navigationIconContentColor = playlistSelectionTopBarContentColor,
                                actionIconContentColor = playlistSelectionTopBarContentColor
                            )
                        )
                    }
                }
            ) { padding ->
                val miniPlayerHeight = LocalMiniPlayerHeight.current
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                ) {
                    beforeList(playlistTopBarColor)
                    if (searchSlotVisible) {
                        PlaylistModernVisualColorsProvider(
                            coverUrl = headerCover,
                            offlineMode = offlineMode
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(searchSlotHeight)
                                    .clipToBounds()
                                    .graphicsLayer {
                                        alpha = searchSlotAlpha
                                    }
                            ) {
                                PlaylistModernStableSearchField(
                                    query = searchQuery,
                                    onQueryChange = search.onQueryChange,
                                    placeholder = searchPlaceholderText,
                                    inputState = searchInputState,
                                    onFocusChanged = search.onDockedFocusChanged,
                                    focusRequester = if (searchFieldFocusInHeader) {
                                        null
                                    } else {
                                        searchFocusRequester
                                    },
                                    dockedProgress = searchDockedVisualProgress,
                                    glassColor = dockedSearchGlassColor,
                                    modifier = Modifier.graphicsLayer {
                                        translationY = with(density) {
                                            ((1f - searchSlotAlpha) * -8.dp.toPx())
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        key(header.key) {
                            PlaylistModernVisualColorsProvider(
                                coverUrl = headerCover,
                                offlineMode = offlineMode
                            ) {
                                val heroBlock: @Composable () -> Unit = {
                                    PlaylistModernHeroHeader(
                                        displayName = header.title,
                                        coverUrl = headerCover,
                                        subtitle = header.subtitle,
                                        offlineMode = offlineMode,
                                        height = playlistHeroHeight,
                                        metaItems = header.metaItems,
                                        actionsRevealProgress = searchVisibilityEased,
                                        actions = if (headerSearchVisible) {
                                            {
                                                Box(
                                                    modifier = Modifier.graphicsLayer {
                                                        alpha = headerSearchAlpha
                                                    }
                                                ) {
                                                    PlaylistModernHeroSearchField(
                                                        query = searchQuery,
                                                        onQueryChange = search.onQueryChange,
                                                        placeholder = searchPlaceholderText,
                                                        inputState = searchInputState,
                                                        onFocusChanged = {
                                                            search.onHeaderFocusChanged(it)
                                                        },
                                                        focusRequester = if (searchFieldFocusInHeader) {
                                                            searchFocusRequester
                                                        } else {
                                                            null
                                                        }
                                                    )
                                                }
                                            }
                                        } else {
                                            null
                                        }
                                    )
                                }
                                val actionsBlock: @Composable () -> Unit = {
                                    PlaylistModernActionSheet(
                                        coverUrl = headerCover,
                                        offlineMode = offlineMode,
                                        hasCustomBackground = header.hasCustomBackground
                                    ) {
                                        playbackActions()
                                    }
                                }
                                PlaylistTwoPaneBody(
                                    twoPane = twoPane,
                                    modifier = Modifier.fillMaxSize(),
                                    pane = {
                                        heroBlock()
                                        actionsBlock()
                                    }
                                ) {
                                    LazyColumn(
                                        state = listState,
                                        contentPadding = PaddingValues(bottom = 24.dp + miniPlayerHeight),
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .then(listModifier)
                                    ) {
                                        // 两栏时头部和操作区搬进侧栏, 这里留空位保住索引, 定位和恢复滚动仍按固定偏移计算
                                        item(
                                            key = header.headerKey,
                                            contentType = "playlist_header"
                                        ) {
                                            if (twoPane) PlaylistTwoPaneListTopGap() else heroBlock()
                                        }

                                        item(
                                            key = LOCAL_PLAYLIST_ACTIONS_KEY,
                                            contentType = "playlist_actions"
                                        ) {
                                            if (!twoPane) actionsBlock()
                                        }

                                        content()
                                    }
                                }
                            }
                        }
                        if (onLocatePlaying != null) {
                            HapticFloatingActionButton(
                                onClick = onLocatePlaying,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(
                                        bottom = 16.dp + miniPlayerHeight,
                                        end = 16.dp
                                    )
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.PlaylistPlay,
                                    contentDescription = stringResource(CoreCommonR.string.cd_locate_playing)
                                )
                            }
                        }


                    }
                }

                overlays()
            }
        }
    }
}
