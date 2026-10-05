package moe.ouom.neriplayer.ui.navigation

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.download.processing.ManagedLibraryProcessingCoordinator
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.data.local.media.displayArtist
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.ui.component.navigation.NeriBottomBar
import moe.ouom.neriplayer.ui.component.navigation.resolveBottomBarSelectionAlpha
import moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayer
import moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayerDefaults
import moe.ouom.neriplayer.ui.component.playback.resolvePlaybackWaiting
import moe.ouom.neriplayer.util.platform.PHONE_SMALLEST_SCREEN_WIDTH_DP
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassBackdrops
import moe.ouom.neriplayer.ui.effect.glass.captureAdvancedGlassBackdrop
import moe.ouom.neriplayer.ui.feedback.AppFeedbackHostEffect
import moe.ouom.neriplayer.ui.feedback.NeriSnackbarHost
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.ui.BottomBarLayoutInsets
import moe.ouom.neriplayer.ui.banner.MANAGED_LIBRARY_PROCESSING_DRAG_THRESHOLD
import moe.ouom.neriplayer.ui.banner.MANAGED_LIBRARY_PROCESSING_REVEAL_EDGE
import moe.ouom.neriplayer.ui.banner.MANAGED_LIBRARY_PROCESSING_Z_INDEX
import moe.ouom.neriplayer.ui.MINI_PLAYER_OVERLAY_Z_INDEX
import moe.ouom.neriplayer.ui.banner.ManagedLibraryProcessingBanner
import moe.ouom.neriplayer.ui.banner.OfflineModeBottomBanner
import moe.ouom.neriplayer.ui.banner.AppManagedProcessingBannerOwner
import moe.ouom.neriplayer.ui.banner.AppManagedProcessingBannerPresentation
import moe.ouom.neriplayer.ui.banner.managedProcessingRevealGesture

internal data class AppBottomBarPresentation(
    val items: List<Pair<Destinations, ImageVector>>,
    val currentDestination: NavDestination?,
    val showNowPlaying: Boolean,
    val offlineMode: Boolean,
    val alwaysUseNewTabStyle: Boolean,
    val backgroundImageUri: String?
) {
    val hasCustomBackground: Boolean get() = backgroundImageUri != null
}

internal data class AppMiniPlayerPresentation(
    val song: SongItem?,
    val coverUrl: String?,
    val visualCoverUrl: String?,
    val songVisualKey: String?,
    val visualCoverSongKey: String?,
    val enableBlur: Boolean
) {
    val hasSong: Boolean get() = song != null

    fun title(resources: Resources): String =
        if (song == null) resources.getString(CoreCommonR.string.nowplaying_no_playback) else song.displayName()

    fun artist(): String = if (song == null) "" else song.displayArtist()
}

internal fun shouldShowMiniPlayer(hasSong: Boolean, showNowPlaying: Boolean): Boolean =
    hasSong && !showNowPlaying

internal fun shouldSuppressPlaybackNavigation(
    playbackOpen: Boolean,
    overlayMounted: Boolean,
    smallestScreenWidthDp: Int
): Boolean =
    // 手机底栏与播放页退出同时开始恢复，避免两段动画串联等待
    playbackOpen || (smallestScreenWidthDp >= PHONE_SMALLEST_SCREEN_WIDTH_DP && overlayMounted)

internal fun playbackSourceNavigationAction(
    sourceRoute: String?,
    navigate: (String) -> Unit
): (() -> Unit)? = sourceRoute?.let { route -> { navigate(route) } }

internal fun reservedMiniPlayerHeight(hasSong: Boolean, showNowPlaying: Boolean, smallestScreenWidthDp: Int) =
    if (shouldShowMiniPlayer(hasSong, showNowPlaying)) {
        if (smallestScreenWidthDp >= PHONE_SMALLEST_SCREEN_WIDTH_DP) {
            NeriMiniPlayerDefaults.TabletHeight
        } else NeriMiniPlayerDefaults.Height
    } else 0.dp

internal fun appSnackbarStartInset(useNavigationRail: Boolean, playbackOverlayVisible: Boolean) =
    if (useNavigationRail && !playbackOverlayVisible) AppNavigationRailWidth else 0.dp

private fun positiveBottomBarHeight(nextHeight: Int, previousHeight: Int): Int =
    if (nextHeight > 0) nextHeight else previousHeight

@Composable
internal fun AppNavigationScaffold(
    bottomBar: AppBottomBarPresentation,
    miniPlayer: AppMiniPlayerPresentation,
    baseBlurRequested: Boolean,
    snackbarHostState: SnackbarHostState,
    onMainTabSelected: (String) -> Unit,
    onExpandNowPlaying: () -> Unit,
    onOpenCurrentPlaybackSource: (() -> Unit)? = null,
    content: @Composable (BottomBarLayoutInsets) -> Unit
) {
    val configuration = LocalConfiguration.current
    val dragBoundsOwner = remember { AppDragBoundsOwner() }
    val reservedMiniPlayerHeight = reservedMiniPlayerHeight(
        miniPlayer.hasSong,
        bottomBar.showNowPlaying,
        configuration.smallestScreenWidthDp
    )
    CompositionLocalProvider(LocalMiniPlayerHeight provides reservedMiniPlayerHeight) {
        AppFeedbackHostEffect(snackbarHostState)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val useNavigationRail = shouldUseAppNavigationRail(
                configuration.smallestScreenWidthDp,
                configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
                maxWidth
            )
            Scaffold(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                snackbarHost = {
                    NeriSnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.padding(
                            start = appSnackbarStartInset(useNavigationRail, bottomBar.showNowPlaying)
                        ),
                        bottomPadding = LocalMiniPlayerHeight.current
                    )
                },
                bottomBar = {
                    if (!useNavigationRail) {
                        AppScaffoldBottomBar(
                            bottomBar = bottomBar,
                            onMainTabSelected = onMainTabSelected,
                            onTabBarBoundsChanged = dragBoundsOwner::updateTabBarBounds
                        )
                    }
                }
            ) { innerPadding ->
                val layoutInsets = resolveAppNavigationLayoutInsets(
                    useNavigationRail = useNavigationRail,
                    baseBlurRequested = baseBlurRequested,
                    bottomBarInset = innerPadding.calculateBottomPadding().coerceAtLeast(0.dp),
                    reservedMiniPlayerHeight = reservedMiniPlayerHeight
                )
                val dragBounds = dragBoundsOwner.visibleBounds(miniPlayer.hasSong, bottomBar.showNowPlaying)
                CompositionLocalProvider(
                    LocalMiniPlayerHeight provides layoutInsets.screenBottomInset,
                    LocalMiniPlayerBoundsInRoot provides dragBounds.miniPlayer,
                    LocalBottomTabBarBoundsInRoot provides dragBounds.bottomTabBar.takeUnless { useNavigationRail }
                ) {
                    AppAdaptiveNavigationContent(
                        useNavigationRail = useNavigationRail,
                        showNowPlaying = bottomBar.showNowPlaying,
                        offlineMode = bottomBar.offlineMode,
                        railContent = { AppNavigationRail(bottomBar, onMainTabSelected) },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        AppManagedProcessingLayer(
                            layoutInsets = layoutInsets,
                            miniPlayer = miniPlayer,
                            showNowPlaying = bottomBar.showNowPlaying,
                            offlineMode = bottomBar.offlineMode,
                            onExpandNowPlaying = onExpandNowPlaying,
                            onOpenCurrentPlaybackSource = onOpenCurrentPlaybackSource,
                            onMiniPlayerBoundsChanged = dragBoundsOwner::updateMiniPlayerBounds,
                            content = content
                        )
                    }
                }
            }
        }
    }
}

private fun bottomBarVisibilityTarget(showNowPlaying: Boolean): Float =
    if (showNowPlaying) 0f else 1f

private fun bottomBarVisibilityDuration(showNowPlaying: Boolean): Int =
    if (showNowPlaying) 220 else 280

@Composable
private fun AppScaffoldBottomBar(
    bottomBar: AppBottomBarPresentation,
    onMainTabSelected: (String) -> Unit,
    onTabBarBoundsChanged: (Rect) -> Unit
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val tabInsets = NavigationBarDefaults.windowInsets
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val visibilityProgress by animateFloatAsState(
        targetValue = bottomBarVisibilityTarget(bottomBar.showNowPlaying),
        animationSpec = tween(
            durationMillis = bottomBarVisibilityDuration(bottomBar.showNowPlaying),
            easing = FastOutSlowInEasing
        ),
        label = "bottom_bar_visibility"
    )
    val selectAlpha = resolveBottomBarSelectionAlpha(
        hasCustomBackground = bottomBar.hasCustomBackground,
        alwaysUseNewTabStyle = bottomBar.alwaysUseNewTabStyle
    )
    Box(modifier = Modifier.fillMaxWidth().clipToBounds()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { size ->
                    bottomBarHeightPx = positiveBottomBarHeight(size.height, bottomBarHeightPx)
                }
                .graphicsLayer {
                    translationY = (1f - visibilityProgress) * bottomBarHeightPx.toFloat()
                    alpha = visibilityProgress
                }
        ) {
            AnimatedVisibility(visible = bottomBar.offlineMode) {
                OfflineModeBottomBanner()
            }
            NeriBottomBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("appBottomNavigation")
                    .reportBottomTabBounds(tabInsets, density, layoutDirection, onTabBarBoundsChanged),
                selectAlpha = selectAlpha,
                items = bottomBar.items,
                currentDestination = bottomBar.currentDestination,
                onItemSelected = { onMainTabSelected(it.route) }
            )
        }
    }
}

@Composable
private fun rememberManagedProcessingBannerSession(): Pair<
        AppManagedProcessingBannerOwner,
        AppManagedProcessingBannerPresentation
> {
    val currentState by ManagedLibraryProcessingCoordinator.state.collectAsStateWithLifecycle()
    val currentProgress by ManagedDownloadStorage.migrationProgressFlow.collectAsStateWithLifecycle()
    val collapsedState = rememberSaveable { mutableStateOf(false) }
    val owner = remember(collapsedState) { AppManagedProcessingBannerOwner(collapsedState) }
    LaunchedEffect(currentState, currentProgress) {
        owner.observe(currentState, currentProgress)
    }
    LaunchedEffect(currentState.operationId) {
        owner.onOperationChanged(currentState)
    }
    return owner to owner.presentation(currentState, currentProgress)
}

@Composable
private fun AppManagedProcessingLayer(
    layoutInsets: BottomBarLayoutInsets,
    miniPlayer: AppMiniPlayerPresentation,
    showNowPlaying: Boolean,
    offlineMode: Boolean,
    onExpandNowPlaying: () -> Unit,
    onOpenCurrentPlaybackSource: (() -> Unit)?,
    onMiniPlayerBoundsChanged: (Rect) -> Unit,
    content: @Composable (BottomBarLayoutInsets) -> Unit
) {
    val (owner, presentation) = rememberManagedProcessingBannerSession()
    val backdrops = LocalAdvancedGlassBackdrops.current
    val contentCaptureModifier = if (backdrops == null) {
        Modifier
    } else {
        Modifier.captureAdvancedGlassBackdrop(backdrops.content)
    }
    val edgePx = with(LocalDensity.current) { MANAGED_LIBRARY_PROCESSING_REVEAL_EDGE.toPx() }
    val thresholdPx = with(LocalDensity.current) { MANAGED_LIBRARY_PROCESSING_DRAG_THRESHOLD.toPx() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = layoutInsets.navContentBottomPadding)
            .clipToBounds()
            .managedProcessingRevealGesture(
                collapsed = presentation.revealGestureEnabled,
                edgePx = edgePx,
                thresholdPx = thresholdPx,
                onExpand = owner::expand
            )
    ) {
        AppManagedProcessingBanner(presentation, owner::collapse)
        Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
            // 页面和拖拽歌曲一起进入底栏模糊取样，播放器自身保持在取样层外
            AppDragOverlayHost(modifier = Modifier.fillMaxSize().then(contentCaptureModifier)) {
                content(layoutInsets)
            }
            AppMiniPlayerOverlay(
                miniPlayer = miniPlayer,
                showNowPlaying = showNowPlaying,
                offlineMode = offlineMode,
                bottomPadding = layoutInsets.miniPlayerBottomPadding,
                onExpandNowPlaying = onExpandNowPlaying,
                onOpenCurrentPlaybackSource = onOpenCurrentPlaybackSource,
                onBoundsChanged = onMiniPlayerBoundsChanged
            )
        }
    }
}

private fun managedProcessingBannerEnterTransition() =
    fadeIn(animationSpec = tween(durationMillis = 180)) +
        slideInVertically(
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
            initialOffsetY = { -it / 2 }
        ) +
        expandVertically(
            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
            expandFrom = Alignment.Top
        )

private fun managedProcessingBannerExitTransition() =
    fadeOut(animationSpec = tween(durationMillis = 160)) +
        slideOutVertically(
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            targetOffsetY = { -it / 2 }
        ) +
        shrinkVertically(
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            shrinkTowards = Alignment.Top
        )

@Composable
private fun BoxScope.AppManagedProcessingBanner(
    presentation: AppManagedProcessingBannerPresentation,
    onCollapsedChange: (Boolean) -> Unit
) {
    AnimatedVisibility(
        visible = presentation.visible,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .zIndex(MANAGED_LIBRARY_PROCESSING_Z_INDEX),
        enter = managedProcessingBannerEnterTransition(),
        exit = managedProcessingBannerExitTransition()
    ) {
        ManagedLibraryProcessingBanner(
            state = presentation.state,
            migrationProgress = presentation.progress,
            modifier = Modifier,
            interactive = presentation.visible,
            onCollapsedChange = onCollapsedChange
        )
    }
}

private data class AppMiniPlayerPlaybackControls(
    val playbackRequested: Boolean,
    val playing: Boolean,
    val usbPreparing: Boolean,
    val routeMuted: Boolean
)

@Composable
private fun rememberMiniPlayerPlaybackControls(): AppMiniPlayerPlaybackControls {
    val playbackRequested by PlayerManager.playbackControlPlayingFlow.collectAsStateWithLifecycle()
    val playing by PlayerManager.isPlayingFlow.collectAsStateWithLifecycle()
    val usbPreparing by PlayerManager.usbExclusivePlaybackPreparingFlow.collectAsStateWithLifecycle()
    val routeMuted by PlayerManager.audioRouteMuteSuppressedFlow.collectAsStateWithLifecycle()
    return AppMiniPlayerPlaybackControls(
        playbackRequested = playbackRequested,
        playing = playing,
        usbPreparing = usbPreparing,
        routeMuted = routeMuted
    )
}

@Composable
private fun AppMiniPlayerOverlay(
    miniPlayer: AppMiniPlayerPresentation,
    showNowPlaying: Boolean,
    offlineMode: Boolean,
    bottomPadding: Dp,
    onExpandNowPlaying: () -> Unit,
    onOpenCurrentPlaybackSource: (() -> Unit)?,
    onBoundsChanged: (Rect) -> Unit
) {
    val controls = rememberMiniPlayerPlaybackControls()
    val resources = LocalResources.current
    val tabletControls = if (LocalConfiguration.current.smallestScreenWidthDp >= PHONE_SMALLEST_SCREEN_WIDTH_DP) {
        rememberMiniPlayerTabletActions(
            visible = shouldShowMiniPlayer(miniPlayer.hasSong, showNowPlaying),
            offlineMode = offlineMode,
            onOpenCurrentPlaybackSource = onOpenCurrentPlaybackSource
        )
    } else null
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = shouldShowMiniPlayer(miniPlayer.hasSong, showNowPlaying),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = bottomPadding)
                .zIndex(MINI_PLAYER_OVERLAY_Z_INDEX),
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                initialOffsetY = { it / 2 }
            ) + fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                targetOffsetY = { it / 2 }
            ) + fadeOut(animationSpec = tween(durationMillis = 120))
        ) {
            NeriMiniPlayer(
                title = miniPlayer.title(resources),
                artist = miniPlayer.artist(),
                coverUrl = miniPlayer.coverUrl,
                visualCoverUrl = miniPlayer.visualCoverUrl,
                coverIdentityKey = miniPlayer.songVisualKey,
                visualCoverIdentityKey = miniPlayer.visualCoverSongKey,
                hasCurrentSong = miniPlayer.hasSong,
                isPlaying = controls.playbackRequested,
                playPauseEnabled = !controls.usbPreparing,
                modifier = Modifier.testTag("appMiniPlayer")
                    .onGloballyPositioned { coordinates ->
                        onBoundsChanged(coordinates.boundsInRoot())
                    },
                onPlayPause = { PlayerManager.togglePlayPause() },
                onPrevious = { PlayerManager.previous() },
                onNext = { PlayerManager.next() },
                onExpand = onExpandNowPlaying,
                enableBlur = miniPlayer.enableBlur,
                offlineMode = offlineMode,
                isPlaybackWaiting = resolvePlaybackWaiting(
                    playbackRequested = controls.playbackRequested,
                    isPlaying = controls.playing,
                    usbPlaybackPreparing = controls.usbPreparing
                ),
                isAudioRouteMuted = controls.routeMuted,
                tabletControls = tabletControls
            )
        }
    }
}
