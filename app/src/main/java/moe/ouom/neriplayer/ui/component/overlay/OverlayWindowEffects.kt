package moe.ouom.neriplayer.ui.component.overlay

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ui.theme.NeriDesign

// 只有卡片自身磨砂，背景是一层纯色遮罩；深色下压得更重才能托住浅色文字
private const val EnhancedScrimDark = 0.48f
private const val EnhancedScrimLight = 0.2f

/**
 * Plain scrim behind an enhanced dialog: the window dim is set once, so the page behind stays
 * sharp and only the dialog card itself is frosted
 */
@Composable
internal fun ApplyEnhancedOverlayScrim(dark: Boolean) {
    if (!NeriDesign.tokens.enhancedEffects) return
    val view = LocalView.current
    val window = remember(view) { view.findOverlayDialogWindow() } ?: return
    DisposableEffect(window, dark) {
        val attributes = window.attributes
        if ((attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
            attributes.dimAmount = if (dark) EnhancedScrimDark else EnhancedScrimLight
            window.attributes = attributes
        }
        onDispose { }
    }
}

/**
 * Callers usually close a dialog by dropping it from composition, which removes the window at
 * once. A system exit animation on the window lets those dismissals fade out as well, run by
 * the compositor at no app-side rendering cost
 */
@Composable
internal fun ApplyEnhancedOverlayWindowExit() {
    if (!NeriDesign.tokens.enhancedEffects) return
    val view = LocalView.current
    val window = remember(view) { view.findOverlayDialogWindow() } ?: return
    DisposableEffect(window) {
        window.setWindowAnimations(R.style.NeriOverlayWindowExit)
        // 窗口随对话框一起销毁，不还原动画，否则会把退场动画一并撤掉
        onDispose { }
    }
}
