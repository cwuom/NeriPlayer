package moe.ouom.neriplayer.ui.screen.server

import android.view.View
import android.view.Window
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import moe.ouom.neriplayer.ui.component.overlay.ApplyOverlayWindowNavigationBarPolicy

@Composable
internal fun MusicServerDialogSystemBars() {
    ApplyOverlayWindowNavigationBarPolicy()
    val view = LocalView.current
    val darkIcons = MaterialTheme.colorScheme.background.luminance() > 0.5f
    SideEffect {
        val window = view.findMusicServerDialogWindow() ?: return@SideEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}

private fun View.findMusicServerDialogWindow(): Window? {
    var current: View? = this
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = current.parent as? View
    }
    return null
}
