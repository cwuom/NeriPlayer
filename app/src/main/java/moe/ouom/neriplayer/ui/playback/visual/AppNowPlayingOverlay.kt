package moe.ouom.neriplayer.ui.playback.visual

import moe.ouom.neriplayer.ui.navigation.LocalPredictiveBackPreviewEnabled
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.unit.dp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.flow.StateFlow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.component.common.PredictiveDismissHandler
import moe.ouom.neriplayer.ui.component.common.PredictiveDismissState
import moe.ouom.neriplayer.ui.component.common.blockUnderlyingTouches
import moe.ouom.neriplayer.ui.component.common.predictiveDismissTransform
import moe.ouom.neriplayer.ui.component.common.rememberPredictiveDismissState
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.animation.LocalSharedCoverFlight
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.util.media.RetainedPlaybackCoverBitmapCache
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.util.hasKnownEmptyLocalCover
import moe.ouom.neriplayer.util.platform.PHONE_SMALLEST_SCREEN_WIDTH_DP

internal data class NowPlayingOverlayCover(
    val url: String?,
    val songKey: String?,
    val song: SongItem?,
    val assetRefreshKey: Int,
    val assetRefresh: NowPlayingCoverAssetRefresh? = null
) {
    val clearCover: Boolean get() = song.hasKnownEmptyLocalCover()
}

internal data class NowPlayingOverlayTheme(
    val dynamicColorEnabled: Boolean,
    val activeCoverSeedHex: String?,
    val seedColorHex: String,
    val paletteStyle: PaletteStyle,
    val colorSpec: ColorSpec.SpecVersion,
    val uiStyle: UiStyle = UiStyle.Material,
    val liquidGlass: Boolean = false
)

internal data class NowPlayingThemeSelection(
    val seedColorHex: String,
    val useSystemDynamic: Boolean
)

internal fun selectNowPlayingOverlayTheme(
    theme: NowPlayingOverlayTheme,
    coverUrl: String?
): NowPlayingThemeSelection = NowPlayingThemeSelection(
    seedColorHex = if (theme.dynamicColorEnabled) {
        theme.activeCoverSeedHex ?: theme.seedColorHex
    } else {
        theme.seedColorHex
    },
    useSystemDynamic = theme.dynamicColorEnabled &&
        theme.activeCoverSeedHex == null && coverUrl == null
)

internal data class NowPlayingOverlayBackground(
    val blurEnabled: Boolean,
    val blurAmount: Float,
    val blurDarken: Float,
    val dynamicEnabled: Boolean,
    val offlineMode: Boolean
)

internal fun releaseNowPlayingInputSession(
    focusManager: FocusManager,
    textToolbar: TextToolbar,
    keyboardController: SoftwareKeyboardController?
) {
    // 先结束底层输入会话，再关闭独立的系统选择菜单和键盘
    focusManager.clearFocus(force = true)
    textToolbar.hide()
    keyboardController?.hide()
}

internal fun resolveNowPlayingOverlayArtwork(
    songKey: String?,
    flight: SharedCoverFlightState?,
    clearCover: Boolean = false
): ImageBitmap? {
    if (clearCover) {
        flight?.clearArtwork()
        return null
    }
    return RetainedPlaybackCoverBitmapCache.getLatestForOwner(songKey)?.bitmap
        ?: flight?.miniCoverBitmap
}

internal fun shouldUseNowPlayingContainerMorph(
    smallestScreenWidthDp: Int
): Boolean = smallestScreenWidthDp < PHONE_SMALLEST_SCREEN_WIDTH_DP

@Composable
internal fun AppNowPlayingOverlay(
    visible: Boolean,
    cover: NowPlayingOverlayCover,
    queueFlow: StateFlow<List<SongItem>>,
    theme: NowPlayingOverlayTheme,
    background: NowPlayingOverlayBackground,
    onVisibilityChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
    dismissState: PredictiveDismissState = rememberPredictiveDismissState(),
    content: @Composable () -> Unit
) {
    val latestOnVisibilityChanged by rememberUpdatedState(onVisibilityChanged)
    val focusManager = LocalFocusManager.current
    val textToolbar = LocalTextToolbar.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(visible) {
        if (visible) {
            dismissState.reset()
            releaseNowPlayingInputSession(focusManager, textToolbar, keyboardController)
        }
    }
    val useContainerMorph = shouldUseNowPlayingContainerMorph(
        smallestScreenWidthDp = LocalConfiguration.current.smallestScreenWidthDp
    )
    val sharedCoverFlight = LocalSharedCoverFlight.current
    val overlayBody: @Composable () -> Unit = {
        DisposableEffect(Unit) {
            latestOnVisibilityChanged(true)
            onDispose {
                latestOnVisibilityChanged(false)
            }
        }
        val themeSelection = selectNowPlayingOverlayTheme(theme, cover.url)
        NeriTheme(
            followSystemDark = false,
            forceDark = true,
            dynamicColor = themeSelection.useSystemDynamic,
            seedColorHex = themeSelection.seedColorHex,
            paletteStyle = theme.paletteStyle,
            colorSpec = theme.colorSpec,
            uiStyle = theme.uiStyle,
            liquidGlass = theme.liquidGlass,
            // text sits on the cover-tinted backdrop, so keep the cover palette instead of greys
            neutralSurfaces = false
        ) {
            PredictiveDismissHandler(state = dismissState, onDismiss = onClose)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // phone morphs own the back preview, while tablets keep the default transform
                    .then(if (useContainerMorph) Modifier else Modifier.predictiveDismissTransform(dismissState))
                    .blockUnderlyingTouches()
            ) {
                NowPlayingBackdrop(
                    NowPlayingBackdropRequest(cover, queueFlow, background)
                )

                CompositionLocalProvider(LocalMiniPlayerHeight provides 0.dp) {
                    content()
                }
            }
        }
    }
    if (useContainerMorph) {
        val backPreviewEnabled = LocalPredictiveBackPreviewEnabled.current
        val morph = rememberNowPlayingMorphState(
            visible = visible,
            flight = sharedCoverFlight,
            artwork = { resolveNowPlayingOverlayArtwork(cover.songKey, sharedCoverFlight, cover.clearCover) },
            backProgress = {
                if (backPreviewEnabled) {
                    FastOutLinearInEasing.transform(dismissState.progress.coerceIn(0f, 1f))
                } else 0f
            }
        )
        if (morph.mounted) {
            NowPlayingContainerMorph(
                morph = morph,
                flight = sharedCoverFlight,
                capsuleInWindow = { sharedCoverFlight?.miniPlayerContainerBounds },
                cardColor = remember(theme.activeCoverSeedHex) {
                    nowPlayingMorphCardColor(theme.activeCoverSeedHex)
                },
                content = overlayBody
            )
        }
        return
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
            initialOffsetY = { fullHeight -> fullHeight }
        ) + fadeIn(animationSpec = tween(durationMillis = 150)),
        exit = slideOutVertically(
            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
            targetOffsetY = { fullHeight -> fullHeight }
        ) + fadeOut(animationSpec = tween(durationMillis = 150))
    ) {
        overlayBody()
    }
}
