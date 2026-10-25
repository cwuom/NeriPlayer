package moe.ouom.neriplayer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import moe.ouom.neriplayer.util.platform.detectColorOsPredictiveBackSupport

private const val FOREGROUND_DETECTION_BATCHES = 2
private const val FOREGROUND_DETECTION_RETRY_MILLIS = 5_000L

internal val LocalPredictiveBackPlatformSupported = staticCompositionLocalOf { false }

@Composable
internal fun rememberPredictiveBackPlatformSupport(
    detectSupport: suspend () -> Boolean? = ::detectColorOsPredictiveBackSupport
): State<Boolean> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentDetection by rememberUpdatedState(detectSupport)
    return produceState(false, lifecycle) {
        var confirmedSupport: Boolean? = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (confirmedSupport != null) return@repeatOnLifecycle
            repeat(FOREGROUND_DETECTION_BATCHES) { batch ->
                val support = currentDetection()
                if (support != null) {
                    confirmedSupport = support
                    value = support
                    return@repeatOnLifecycle
                }
                // 只在当前前台激活中追加一批，持续未知留待下次前台再试
                if (batch < FOREGROUND_DETECTION_BATCHES - 1) {
                    delay(FOREGROUND_DETECTION_RETRY_MILLIS)
                }
            }
        }
    }
}
