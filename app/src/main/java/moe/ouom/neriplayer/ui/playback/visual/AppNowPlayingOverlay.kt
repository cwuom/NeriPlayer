package moe.ouom.neriplayer.ui.playback.visual

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
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
import moe.ouom.neriplayer.ui.component.common.blockUnderlyingTouches
import moe.ouom.neriplayer.ui.navigation.LocalMiniPlayerHeight
import moe.ouom.neriplayer.ui.theme.NeriTheme

internal data class NowPlayingOverlayCover(
    val url: String?,
    val songKey: String?,
    val song: SongItem?,
    val assetRefreshKey: Int
)

internal data class NowPlayingOverlayTheme(
    val dynamicColorEnabled: Boolean,
    val activeCoverSeedHex: String?,
    val seedColorHex: String,
    val paletteStyle: PaletteStyle,
    val colorSpec: ColorSpec.SpecVersion
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

@Composable
internal fun AppNowPlayingOverlay(
    visible: Boolean,
    cover: NowPlayingOverlayCover,
    queueFlow: StateFlow<List<SongItem>>,
    theme: NowPlayingOverlayTheme,
    background: NowPlayingOverlayBackground,
    onVisibilityChanged: (Boolean) -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    val latestOnVisibilityChanged by rememberUpdatedState(onVisibilityChanged)
    val focusManager = LocalFocusManager.current
    val textToolbar = LocalTextToolbar.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(visible) {
        if (visible) {
            releaseNowPlayingInputSession(focusManager, textToolbar, keyboardController)
        }
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
            colorSpec = theme.colorSpec
        ) {
            BackHandler(onBack = onClose)

            Box(
                modifier = Modifier
                    .fillMaxSize()
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
}
