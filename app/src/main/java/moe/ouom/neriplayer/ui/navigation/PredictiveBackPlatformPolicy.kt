package moe.ouom.neriplayer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import moe.ouom.neriplayer.util.platform.detectColorOsPredictiveBackSupport

internal val LocalPredictiveBackPlatformSupported = staticCompositionLocalOf { false }

@Composable
internal fun rememberPredictiveBackPlatformSupport(): State<Boolean> = produceState(false) {
    value = detectColorOsPredictiveBackSupport()
}
