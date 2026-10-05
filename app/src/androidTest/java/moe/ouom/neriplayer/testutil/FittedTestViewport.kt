package moe.ouom.neriplayer.testutil

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FittedTestViewport(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
    maximumDensityScale: Float = 1f,
    layoutOnlyTextInput: Boolean = false,
    content: @Composable () -> Unit
) {
    // 模拟窗口先适配真实宿主，避免横屏内容被 CI 的竖屏手机裁剪
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val scale = minOf(maxWidth / width, maxHeight / height, maximumDensityScale)
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, fontScale)) {
            Box(
                modifier = modifier.requiredSize(width, height)
                    .consumeWindowInsets(WindowInsets.safeDrawing)
            ) {
                if (layoutOnlyTextInput) {
                    // 编辑布局已显式模拟键盘剩余高度，避免宿主 IME 再次改变模拟窗口
                    InterceptPlatformTextInput(interceptor = { _, _ -> awaitCancellation() }, content = content)
                } else {
                    content()
                }
            }
        }
    }
}
